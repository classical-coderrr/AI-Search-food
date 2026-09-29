package com.example.food.memory;

import com.example.food.admin.dashboard.AdminMemoryObservabilityService;
import com.example.food.admin.dashboard.dto.AdminMemoryObservabilityResponse.OnlineLabelMetrics;
import com.example.food.memory.ContextBuilder.ContextBuildResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MemoryTargetFeedbackIntegrationTest {
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private MemoryRetrievalTraceService traceService;
    @Autowired private MemoryTargetFeedbackService targetFeedbackService;
    @Autowired private AdminMemoryObservabilityService observabilityService;

    @Test
    void feedbackIsOwnedIdempotentIntentScopedAndAffectsLaterRetrievalSignals() {
        Long userId = insertUser("逐条反馈用户");
        Long otherUserId = insertUser("隔离检查用户");
        Long memoryId = insertMemoryItem(userId);
        LocalDateTime now = LocalDateTime.now();
        String traceId = "target-feedback-" + Long.toUnsignedString(System.nanoTime());
        MemoryQueryPlan plan = new MemoryQueryPlan("健身后晚餐", "POST_WORKOUT_RECIPE_RECALL",
                "健身后晚餐 高蛋白", null, List.of(), List.of(), List.of(), List.of(),
                null, null, List.of(), List.of(), null, null, 5);
        MemorySearchHit hit = new MemorySearchHit("MEMORY_ITEM", memoryId, 0,
                "INGREDIENT_PREFERENCE", "鸡胸肉", "鸡胸肉 LIKE INGREDIENT_PREFERENCE", null,
                "LIKE", "LONG_TERM", new BigDecimal("0.9000"), new BigDecimal("0.8000"), now, null);
        MemoryRetrievalResult retrieval = new MemoryRetrievalResult(plan, List.of(hit),
                new MemoryRetrievalResult.RetrievalTrace(userId, null, now, 1, 0,
                        List.of(memoryId), List.of()));
        ContextBuildResult context = new ContextBuildResult("使用鸡胸肉偏好", Map.of("PERSONAL_MEMORY", List.of("鸡胸肉")),
                List.of(memoryId), List.of(), List.of(), 20, 500, false);

        assertThat(traceService.record(userId, traceId, "健身后晚餐", retrieval, context,
                "SUCCESS", null, 25)).isTrue();
        assertThat(targetFeedbackService.targets(userId, traceId)).containsExactly(
                new MemoryTargetFeedbackTargetResponse(MemoryTargetFeedbackSource.MEMORY_ITEM,
                        memoryId, "鸡胸肉", "LIKE · INGREDIENT_PREFERENCE", null));

        MemoryTargetFeedbackRequest incorrect = new MemoryTargetFeedbackRequest(traceId,
                MemoryTargetFeedbackSource.MEMORY_ITEM, memoryId, MemoryFeedbackType.INCORRECT);
        MemoryTargetFeedbackResponse first = targetFeedbackService.submit(userId, incorrect);
        MemoryTargetFeedbackResponse repeated = targetFeedbackService.submit(userId, incorrect);
        assertThat(repeated.id()).isEqualTo(first.id());
        assertThat(repeated.updated()).isFalse();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM memory_target_feedback WHERE trace_id = ?",
                Integer.class, traceId)).isEqualTo(1);

        MemoryTargetFeedbackResponse corrected = targetFeedbackService.submit(userId,
                new MemoryTargetFeedbackRequest(traceId, MemoryTargetFeedbackSource.MEMORY_ITEM,
                        memoryId, MemoryFeedbackType.OUTDATED));
        assertThat(corrected.updated()).isTrue();
        assertThat(targetFeedbackService.recentSignals(userId, plan.intent(), List.of(hit), now.minusDays(30)))
                .containsEntry(new MemoryVectorKey("MEMORY_ITEM", memoryId), MemoryFeedbackType.OUTDATED);
        assertThat(targetFeedbackService.recentSignals(userId, "FRIDGE_RECIPE", List.of(hit), now.minusDays(30)))
                .isEmpty();

        OnlineLabelMetrics onlineLabels = observabilityService.snapshot("30d").onlineLabels();
        assertThat(onlineLabels.usedTargetCount()).isEqualTo(1);
        assertThat(onlineLabels.labeledTargetCount()).isEqualTo(1);
        assertThat(onlineLabels.labelCoverageRate()).isEqualTo(1D);
        assertThat(onlineLabels.outdatedCount()).isEqualTo(1);
        assertThat(onlineLabels.outdatedFeedbackRate()).isEqualTo(1D);
        assertThat(onlineLabels.minimumSampleCount()).isEqualTo(20);
        assertThat(onlineLabels.sampleSufficient()).isFalse();
        assertThat(onlineLabels.sampleStatus()).isEqualTo("INSUFFICIENT_LABELS");

        assertThatThrownBy(() -> targetFeedbackService.targets(otherUserId, traceId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("记忆追踪记录不存在");
        assertThatThrownBy(() -> targetFeedbackService.submit(userId,
                new MemoryTargetFeedbackRequest(traceId, MemoryTargetFeedbackSource.MEMORY_ITEM,
                        memoryId + 1000, MemoryFeedbackType.HELPFUL)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("没有使用这条记忆");
    }

    private Long insertUser(String nickname) {
        String phone;
        do {
            long suffix = Math.floorMod(UUID.randomUUID().getLeastSignificantBits(), 100_000_000L);
            phone = "137" + String.format("%08d", suffix);
        } while (jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users WHERE phone = ?", Integer.class, phone) > 0);
        jdbcTemplate.update("INSERT INTO users (phone, nickname) VALUES (?, ?)", phone, nickname);
        return jdbcTemplate.queryForObject("SELECT id FROM users WHERE phone = ?", Long.class, phone);
    }

    private Long insertMemoryItem(Long userId) {
        String key = "target-feedback-" + Long.toUnsignedString(System.nanoTime());
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update("""
                INSERT INTO memory_items (
                    user_id, memory_type, canonical_entity, preference, scope, temporal_type,
                    strength, confidence, importance, evidence_count, occurrence_count, source_count,
                    source_candidate_ids_json, source_episode_ids_json, first_seen_at, last_seen_at,
                    consolidation_key, status, version
                ) VALUES (?, 'INGREDIENT_PREFERENCE', '鸡胸肉', 'LIKE', 'USER', 'LONG_TERM',
                          0.9000, 0.9000, 0.8000, 2, 2, 2, '[]', '[]', ?, ?, ?, 'ACTIVE', 0)
                """, userId, now, now, key);
        return jdbcTemplate.queryForObject("SELECT id FROM memory_items WHERE user_id = ? AND consolidation_key = ?",
                Long.class, userId, key);
    }
}
