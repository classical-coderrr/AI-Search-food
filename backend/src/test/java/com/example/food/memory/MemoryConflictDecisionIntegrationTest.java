package com.example.food.memory;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MemoryConflictDecisionIntegrationTest {

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private MemoryConflictDecisionService conflictDecisionService;
    @Autowired private MemoryManagementService memoryManagementService;
    @Autowired private MemorySessionService memorySessionService;

    @Test
    void recordsPerTraceIdempotentlyAndDeletesOnlyTheOwningUsersDecisions() {
        Long userA = insertUser("13900000931", "冲突裁决用户A");
        Long userB = insertUser("13900000932", "冲突裁决用户B");
        Long aLike = insertMemoryItem(userA, "user-a-like", "香菜", "LIKE");
        Long aDislike = insertMemoryItem(userA, "user-a-dislike", "香菜", "DISLIKE");
        Long bLike = insertMemoryItem(userB, "user-b-like", "香菜", "LIKE");
        Long bDislike = insertMemoryItem(userB, "user-b-dislike", "香菜", "DISLIKE");
        Long sessionA = openSession(userA, "conflict-session-a");
        Long sessionB = openSession(userB, "conflict-session-b");

        assertThat(conflictDecisionService.record(userA, "conflict-trace-a", sessionA,
                List.of(resolution(aLike, aDislike)))).isEqualTo(1);
        assertThat(conflictDecisionService.record(userA, "conflict-trace-a", sessionA,
                List.of(resolution(aLike, aDislike)))).isZero();
        assertThat(conflictDecisionService.record(userB, "foreign-item-trace", sessionB,
                List.of(resolution(aLike, aDislike)))).isZero();
        assertThat(conflictDecisionService.record(userB, "foreign-session-trace", sessionA,
                List.of(resolution(bLike, bDislike)))).isZero();
        assertThat(conflictDecisionService.record(userB, "missing-session-trace", Long.MAX_VALUE,
                List.of(resolution(bLike, bDislike)))).isZero();
        assertThat(conflictDecisionService.record(userB, "conflict-trace-b", sessionB,
                List.of(resolution(bLike, bDislike)))).isEqualTo(1);
        assertThat(conflictDecisionService.record(userA, "foreign-selected-memory-trace", sessionA,
                List.of(resolutionWithSelected(aLike, aDislike, bLike)))).isZero();

        assertThat(countDecisions(userA)).isEqualTo(1);
        assertThat(countDecisions(userB)).isEqualTo(1);

        memoryManagementService.deleteItem(userA, aLike, 0);
        assertThat(countDecisions(userA)).isZero();
        assertThat(countDecisions(userB)).isEqualTo(1);

        Long secondALike = insertMemoryItem(userA, "user-a-like-2", "辣椒", "LIKE");
        Long secondADislike = insertMemoryItem(userA, "user-a-dislike-2", "辣椒", "DISLIKE");
        assertThat(conflictDecisionService.record(userA, "conflict-trace-a-2", sessionA,
                List.of(resolution(secondALike, secondADislike)))).isEqualTo(1);

        memoryManagementService.clearAll(userA);
        assertThat(countDecisions(userA)).isZero();
        assertThat(countDecisions(userB)).isEqualTo(1);
    }

    private MemoryConflictResolution resolution(Long likeId, Long dislikeId) {
        return new MemoryConflictResolution("INGREDIENT:CANONICAL:INGREDIENT_CILANTRO", "INGREDIENT", "香菜",
                likeId, dislikeId, dislikeId, "DISLIKE", "SCENE_MATCH", "当前餐次与该反向记忆吻合",
                "训练后晚餐本轮暂按不喜欢处理；另一方向保留。", List.of("训练后", "晚餐"));
    }

    private MemoryConflictResolution resolutionWithSelected(Long likeId, Long dislikeId, Long selectedId) {
        return new MemoryConflictResolution("INGREDIENT:CANONICAL:INGREDIENT_CILANTRO", "INGREDIENT", "香菜",
                likeId, dislikeId, selectedId, "LIKE", "SCENE_MATCH", "当前餐次与该反向记忆吻合",
                "冲突双方之外的记忆不能作为裁决结果。", List.of("训练后", "晚餐"));
    }

    private Long openSession(Long userId, String sessionKey) {
        return memorySessionService.open(userId, new MemorySessionOpenCommand(null, sessionKey,
                null, null, null, null, null, null, null)).getId();
    }

    private Long insertUser(String phone, String nickname) {
        jdbcTemplate.update("INSERT INTO users (phone, nickname) VALUES (?, ?)", phone, nickname);
        return jdbcTemplate.queryForObject("SELECT id FROM users WHERE phone = ?", Long.class, phone);
    }

    private Long insertMemoryItem(Long userId, String key, String entity, String preference) {
        jdbcTemplate.update("""
                INSERT INTO memory_items (
                    user_id, memory_type, canonical_entity, preference, strength, confidence, importance,
                    evidence_count, occurrence_count, source_count, source_candidate_ids_json,
                    source_episode_ids_json, first_seen_at, last_seen_at, consolidation_key, status, version
                ) VALUES (?, 'INGREDIENT_PREFERENCE', ?, ?, 0.8000, 0.8000, 0.7000,
                          1, 1, 1, '[]', '[]', NOW(), NOW(), ?, 'ACTIVE', 0)
                """, userId, entity, preference, key);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM memory_items WHERE user_id = ? AND consolidation_key = ?", Long.class, userId, key);
    }

    private int countDecisions(Long userId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM memory_conflicts WHERE user_id = ?", Integer.class, userId);
    }
}
