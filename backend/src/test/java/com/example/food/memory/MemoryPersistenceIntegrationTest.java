package com.example.food.memory;

import com.example.food.admin.dashboard.AdminMemoryObservabilityService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MemoryPersistenceIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MemorySessionService sessionService;

    @Autowired
    private MemoryEpisodeService episodeService;

    @Autowired
    private MemoryRetrievalTraceService retrievalTraceService;

    @Autowired
    private MemoryFeedbackService memoryFeedbackService;

    @Autowired
    private AdminMemoryObservabilityService adminMemoryObservabilityService;

    @Test
    void flywayCreatesMemoryTablesAndPreservesUserIsolationAndIdempotency() {
        assertThat(tableExists("agent_sessions")).isTrue();
        assertThat(tableExists("memory_episodes")).isTrue();
        assertThat(tableExists("memory_retrieval_traces")).isTrue();
        assertThat(tableExists("memory_feedback")).isTrue();
        assertThat(tableExists("memory_evaluation_runs")).isTrue();
        assertThat(columnExists("memory_episodes", "payload_json")).isTrue();
        assertThat(indexExists("uq_memory_episodes_user_key")).isTrue();
        assertThat(indexExists("uq_memory_retrieval_traces_trace")).isTrue();
        assertThat(uniqueConstraintExists("memory_feedback", "uq_memory_feedback_trace")).isTrue();

        Long userId = insertUser("13900000901", "记忆测试用户");
        Long otherUserId = insertUser("13900000902", "其他记忆用户");
        MemorySession session = sessionService.open(userId, new MemorySessionOpenCommand(
                null,
                "memory-test-session",
                "RECOMMEND_RECIPE",
                "训练后晚餐",
                "{\"timeLimit\":20}",
                null,
                null,
                null,
                null
        ));

        MemoryEpisodeCommand command = new MemoryEpisodeCommand(
                session.getId(),
                null,
                "RECIPE_EXPERIENCE",
                "RECIPE_FEEDBACK",
                "recipe-1",
                "feedback-event-1",
                "feedback-idempotency-1",
                "用户给鸡胸肉菜谱评分",
                "{\"rating\":5,\"recipeId\":\"recipe-1\"}",
                null,
                null
        );
        MemoryEpisodeService.RecordResult first = episodeService.record(userId, command);
        MemoryEpisodeService.RecordResult retry = episodeService.record(userId, command);

        assertThat(first.duplicate()).isFalse();
        assertThat(retry.duplicate()).isTrue();
        assertThat(retry.episode().getId()).isEqualTo(first.episode().getId());
        assertThat(episodeService.listOwned(otherUserId, null, null, 20)).isEmpty();
        assertThat(episodeService.listOwned(userId, session.getId(), "RECIPE_EXPERIENCE", 20))
                .extracting(MemoryEpisode::getId)
                .containsExactly(first.episode().getId());
    }

    @Test
    void retrievalTraceStoresScoreAndUsageWithoutRawQueryAndSupportsAggregates() {
        Long userId = insertUser("13900000903", "记忆追踪测试用户");
        String query = "这句个人问题不得明文进入追踪记录";
        MemoryQueryPlan plan = new MemoryQueryPlan(query, "GENERAL_MEMORY_RECALL", query, null,
                List.of(), List.of(), List.of(), List.of(), null, null, List.of(), List.of(),
                null, null, 8);
        MemorySearchHit hit = new MemorySearchHit("MEMORY_ITEM", 13L, "INGREDIENT_PREFERENCE", "敏感标题",
                "个人记忆文本", null, "DISLIKE", "LONG_TERM", new BigDecimal("0.95"),
                new BigDecimal("0.90"), LocalDateTime.now(),
                new MemorySearchHit.ScoreBreakdown(0.8, 0.9, 0.9, 0.95, 1.0, 0.5, 0.87));
        MemoryRetrievalResult retrieval = new MemoryRetrievalResult(plan, List.of(hit),
                new MemoryRetrievalResult.RetrievalTrace(userId, null, LocalDateTime.now(),
                        2, 1, List.of(13L), List.of(22L)));
        ContextBuilder.ContextBuildResult context = new ContextBuilder.ContextBuildResult(
                "personal context", Map.of("PERSONAL_MEMORY", List.of("safe summary")),
                List.of(13L), List.of(), List.of(9L), 120, 500, false);

        assertThat(retrievalTraceService.record(userId, "trace-memory-test", query, retrieval,
                context, "SUCCESS", null, 31)).isTrue();
        assertThat(retrievalTraceService.record(userId, "trace-memory-test", query, retrieval,
                context, "SUCCESS", null, 31)).isFalse();

        String queryHash = jdbcTemplate.queryForObject(
                "SELECT query_hash FROM memory_retrieval_traces WHERE trace_id = ?", String.class,
                "trace-memory-test");
        String ranking = jdbcTemplate.queryForObject(
                "SELECT ranking_json FROM memory_retrieval_traces WHERE trace_id = ?", String.class,
                "trace-memory-test");
        assertThat(queryHash).hasSize(64).doesNotContain(query);
        assertThat(ranking).contains("MEMORY_ITEM", "13", "0.87", "true")
                .doesNotContain(query, "敏感标题", "个人记忆文本");

        MemoryFeedbackRequest helpfulRequest = new MemoryFeedbackRequest("trace-memory-test", MemoryFeedbackType.HELPFUL);
        MemoryFeedbackResponse helpful = memoryFeedbackService.submit(userId, helpfulRequest);
        MemoryFeedbackResponse retry = memoryFeedbackService.submit(userId, helpfulRequest);
        assertThat(retry.id()).isEqualTo(helpful.id());
        assertThat(retry.updated()).isFalse();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM memory_feedback WHERE trace_id = ?", Integer.class,
                "trace-memory-test")).isEqualTo(1);

        MemoryFeedbackResponse corrected = memoryFeedbackService.submit(userId,
                new MemoryFeedbackRequest("trace-memory-test", MemoryFeedbackType.OUTDATED));
        assertThat(corrected.updated()).isTrue();
        assertThat(corrected.feedbackType()).isEqualTo(MemoryFeedbackType.OUTDATED);

        var metrics = adminMemoryObservabilityService.snapshot("24h").metrics();
        assertThat(metrics.retrievalCount()).isEqualTo(1);
        assertThat(metrics.successCount()).isEqualTo(1);
        assertThat(metrics.averageCandidates()).isEqualTo(3D);
        assertThat(metrics.averageEstimatedTokens()).isEqualTo(120D);
        assertThat(metrics.feedbackCount()).isEqualTo(1);
        assertThat(metrics.outdatedFeedbackCount()).isEqualTo(1);
        assertThat(metrics.outdatedFeedbackRate()).isEqualTo(1D);
    }

    private Long insertUser(String phone, String nickname) {
        jdbcTemplate.update("INSERT INTO users (phone, nickname) VALUES (?, ?)", phone, nickname);
        return jdbcTemplate.queryForObject("SELECT id FROM users WHERE phone = ?", Long.class, phone);
    }

    private boolean tableExists(String tableName) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE LOWER(TABLE_NAME) = ?",
                Integer.class,
                tableName
        );
        return count != null && count == 1;
    }

    private boolean columnExists(String tableName, String columnName) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS "
                        + "WHERE LOWER(TABLE_NAME) = ? AND LOWER(COLUMN_NAME) = ?",
                Integer.class,
                tableName,
                columnName
        );
        return count != null && count == 1;
    }

    private boolean indexExists(String indexName) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.INDEXES WHERE LOWER(INDEX_NAME) = ?",
                Integer.class,
                indexName
        );
        return count != null && count == 1;
    }

    private boolean uniqueConstraintExists(String tableName, String constraintName) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS "
                        + "WHERE LOWER(TABLE_NAME) = ? AND LOWER(CONSTRAINT_NAME) = ? AND CONSTRAINT_TYPE = 'UNIQUE'",
                Integer.class,
                tableName,
                constraintName
        );
        return count != null && count == 1;
    }
}
