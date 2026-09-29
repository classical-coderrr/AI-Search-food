package com.example.food.memory;

import com.example.food.admin.dashboard.MemoryEvaluationService;
import com.example.food.admin.dashboard.dto.AdminMemoryEvaluationResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MemoryCandidatePersistenceIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MemoryEpisodeService episodeService;

    @Autowired
    private MemoryCandidateService candidateService;

    @Autowired
    private MemoryConsolidationService consolidationService;

    @Autowired
    private MemoryEvaluationService memoryEvaluationService;

    @Autowired
    private MemoryProcessingJobService processingJobService;

    @Test
    void persistsCandidateAndEvidenceWithUserIsolationAndIdempotency() {
        assertThat(tableExists("memory_candidates")).isTrue();
        assertThat(tableExists("memory_evidence")).isTrue();
        assertThat(columnExists("memory_candidates", "extraction_model")).isTrue();
        assertThat(columnExists("memory_evidence", "source_event_id")).isTrue();

        Long userId = insertUser("13900000801", "候选记忆用户");
        Long otherUserId = insertUser("13900000802", "其他候选用户");
        MemoryEpisode episode = episodeService.record(userId, new MemoryEpisodeCommand(
                null,
                null,
                "RECIPE_FEEDBACK",
                "RECOMMENDATION_FEEDBACK",
                "feedback-1",
                "event-1",
                "candidate-test-1",
                "明确喜欢鸡胸肉意面",
                "{\"action\":\"REACTION\",\"reaction\":\"LIKE\",\"recipeTitle\":\"鸡胸肉意面\"}",
                null,
                null
        )).episode();

        MemoryCandidateService.ExtractionResult first = candidateService.extractAndPersist(userId, episode.getId());
        MemoryCandidateService.ExtractionResult retry = candidateService.extractAndPersist(userId, episode.getId());

        assertThat(first.candidates()).hasSize(1);
        assertThat(first.candidates().get(0).getUserId()).isEqualTo(userId);
        assertThat(first.candidates().get(0).getConfidence()).isEqualByComparingTo("0.8500");
        assertThat(retry.duplicateCount()).isEqualTo(1);
        assertThat(candidateService.listOwned(otherUserId, null, null, 20)).isEmpty();

        Integer evidenceCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM memory_evidence WHERE user_id = ? AND candidate_id = ?",
                Integer.class,
                userId,
                first.candidates().get(0).getId()
        );
        assertThat(evidenceCount).isEqualTo(1);
        String evidenceJson = jdbcTemplate.queryForObject(
                "SELECT evidence_json FROM memory_evidence WHERE candidate_id = ?",
                String.class,
                first.candidates().get(0).getId()
        );
        assertThat(evidenceJson).contains("\"action\":\"LIKE\"");
    }

    @Test
    void repeatedRecipeBehaviorCreatesCanonicalIngredientCandidatesWithEpisodeEvidence() {
        Long userId = insertUser("13900000803", "重复行为记忆用户");
        MemoryEpisode saved = recordEpisode(userId, "RECIPE_SAVED", "recipe-1", "saved-1",
                "{\"recipeId\":\"recipe-1\",\"title\":\"鸡胸肉沙拉\",\"ingredients\":[\"鸡胸\"]}");
        MemoryEpisode cooked = recordEpisode(userId, "RECIPE_FEEDBACK", "feedback-2", "cooked-2",
                "{\"action\":\"COOKED\",\"recipeTitle\":\"鸡胸肉意面\",\"ingredients\":[\"chicken breast\"]}");
        MemoryEpisode liked = recordEpisode(userId, "RECIPE_FEEDBACK", "feedback-3", "liked-3",
                "{\"action\":\"REACTION\",\"reaction\":\"LIKE\",\"recipeTitle\":\"鸡胸肉饭\","
                        + "\"ingredients\":[\"鸡胸肉\"]}");

        candidateService.extractAndPersist(userId, saved.getId());
        candidateService.extractAndPersist(userId, cooked.getId());
        MemoryCandidateService.ExtractionResult learned = candidateService.extractAndPersist(userId, liked.getId());

        List<MemoryCandidate> ingredientCandidates = candidateService.listOwned(
                        userId, null, "INGREDIENT_PREFERENCE", 20).stream()
                .filter(candidate -> "INGREDIENT_CHICKEN".equals(candidate.getCanonicalGroupId()))
                .toList();
        assertThat(ingredientCandidates).hasSize(3);
        assertThat(ingredientCandidates).allSatisfy(candidate -> {
            assertThat(candidate.getPreference()).isEqualTo("LIKE");
            assertThat(candidate.getSourceType()).isEqualTo("IMPLICIT_BEHAVIOR");
            assertThat(candidate.getTemporalType()).isEqualTo("RECENT");
            assertThat(candidate.getConfidence()).isLessThanOrEqualTo(new java.math.BigDecimal("0.7000"));
        });
        assertThat(ingredientCandidates).extracting(MemoryCandidate::getEpisodeId)
                .containsExactlyInAnyOrder(saved.getId(), cooked.getId(), liked.getId());

        Integer evidenceCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM memory_evidence WHERE user_id = ? AND candidate_id IN "
                        + "(SELECT id FROM memory_candidates WHERE user_id = ? AND candidate_type = 'INGREDIENT_PREFERENCE')",
                Integer.class,
                userId,
                userId
        );
        assertThat(evidenceCount).isEqualTo(3);

        candidateService.extractAndPersist(userId, liked.getId());
        assertThat(candidateService.listOwned(userId, null, "INGREDIENT_PREFERENCE", 20))
                .filteredOn(candidate -> "INGREDIENT_CHICKEN".equals(candidate.getCanonicalGroupId()))
                .hasSize(3);
        assertThat(learned.candidates()).hasSizeGreaterThanOrEqualTo(4);
    }

    @Test
    void fixedOfflineMemoryEvaluationCoversImplicitBehaviorPatterns() {
        AdminMemoryEvaluationResponse result = memoryEvaluationService.run();

        assertThat(result.suiteVersion()).isEqualTo("memory-evaluation-v6");
        assertThat(result.cases()).allSatisfy(testCase -> assertThat(testCase.passed())
                .as(testCase.caseKey() + " expected=" + testCase.expected() + " actual=" + testCase.actual())
                .isTrue());
        assertThat(result.cases())
                .filteredOn(testCase -> "IMPLICIT_BEHAVIOR_PATTERN".equals(testCase.metric()))
                .hasSize(6)
                .allSatisfy(testCase -> assertThat(testCase.passed()).isTrue());
        assertThat(result.status()).isEqualTo("PASSED");
    }

    @Test
    void unsavingRecipeRetractsOnlyItsImplicitEvidenceAndRebuildsProfileItem() {
        Long userId = insertUser("13900000804", "取消收藏记忆用户");
        MemoryEpisode saved = recordEpisode(userId, "RECIPE_SAVED", "recipe-1", "saved-1",
                "{\"recipeId\":\"recipe-1\",\"title\":\"鸡胸肉沙拉\",\"ingredients\":[\"鸡胸肉\"]}");
        MemoryEpisode cooked = recordEpisode(userId, "RECIPE_FEEDBACK", "feedback-2", "cooked-2",
                "{\"action\":\"COOKED\",\"recipeTitle\":\"鸡胸肉意面\",\"ingredients\":[\"鸡胸肉\"]}");
        MemoryEpisode liked = recordEpisode(userId, "RECIPE_FEEDBACK", "feedback-3", "liked-3",
                "{\"action\":\"REACTION\",\"reaction\":\"LIKE\",\"recipeTitle\":\"鸡胸肉饭\","
                        + "\"ingredients\":[\"鸡胸肉\"]}");

        candidateService.extractAndPersist(userId, saved.getId());
        candidateService.extractAndPersist(userId, cooked.getId());
        candidateService.extractAndPersist(userId, liked.getId());
        consolidationService.consolidate(userId);

        MemoryItem beforeUnsave = ingredientItem(userId);
        assertThat(beforeUnsave).isNotNull();
        assertThat(beforeUnsave.getSourceEpisodeIdsJson()).contains(
                saved.getId().toString(), cooked.getId().toString(), liked.getId().toString());

        MemoryEpisode unsaved = recordEpisode(userId, "RECIPE_UNSAVED", "recipe-1", "unsaved-1",
                "{\"recipeId\":\"recipe-1\",\"title\":\"鸡胸肉沙拉\"}");
        candidateService.extractAndPersist(userId, unsaved.getId());

        MemoryCandidate retracted = candidateService.listOwned(userId, saved.getId(), "INGREDIENT_PREFERENCE", 20)
                .stream().filter(candidate -> "INGREDIENT_CHICKEN".equals(candidate.getCanonicalGroupId()))
                .findFirst().orElseThrow();
        assertThat(retracted.getStatus()).isEqualTo("SUPERSEDED");

        consolidationService.consolidate(userId);
        MemoryItem afterUnsave = ingredientItem(userId);
        assertThat(afterUnsave).isNotNull();
        assertThat(afterUnsave.getSourceEpisodeIdsJson())
                .contains(cooked.getId().toString(), liked.getId().toString())
                .doesNotContain(saved.getId().toString());
        assertThat(afterUnsave.getEvidenceCount()).isEqualTo(2);
    }

    @Test
    void recoveryQueueIncludesUnsaveEpisodes() {
        Long userId = insertUser("13900000805", "撤销队列恢复用户");
        MemoryEpisode unsaved = recordEpisode(userId, "RECIPE_UNSAVED", "recipe-9", "unsaved-9",
                "{\"recipeId\":\"recipe-9\",\"title\":\"测试菜谱\"}");

        assertThat(processingJobService.recoverUnqueuedEpisodes(20)).isEqualTo(1);
        Integer queuedJobs = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM memory_processing_jobs WHERE user_id = ? AND episode_id = ?",
                Integer.class, userId, unsaved.getId());
        assertThat(queuedJobs).isEqualTo(1);
    }

    @Test
    void delayedUnsaveDoesNotRetractAResaveWithTheSameTimestamp() {
        Long userId = insertUser("13900000806", "同时间戳重新收藏用户");
        LocalDateTime occurredAt = LocalDateTime.now().minusMinutes(1);
        MemoryEpisode saved = recordEpisodeAt(userId, "RECIPE_SAVED", "recipe-1", "saved-1",
                "{\"recipeId\":\"recipe-1\",\"title\":\"鸡胸肉沙拉\",\"ingredients\":[\"鸡胸肉\"]}",
                occurredAt);
        MemoryEpisode cooked = recordEpisodeAt(userId, "RECIPE_FEEDBACK", "feedback-2", "cooked-2",
                "{\"action\":\"COOKED\",\"recipeTitle\":\"鸡胸肉意面\",\"ingredients\":[\"鸡胸肉\"]}",
                occurredAt);
        MemoryEpisode liked = recordEpisodeAt(userId, "RECIPE_FEEDBACK", "feedback-3", "liked-3",
                "{\"action\":\"REACTION\",\"reaction\":\"LIKE\",\"recipeTitle\":\"鸡胸肉饭\","
                        + "\"ingredients\":[\"鸡胸肉\"]}", occurredAt);
        candidateService.extractAndPersist(userId, saved.getId());
        candidateService.extractAndPersist(userId, cooked.getId());
        candidateService.extractAndPersist(userId, liked.getId());

        MemoryEpisode unsaved = recordEpisodeAt(userId, "RECIPE_UNSAVED", "recipe-1", "unsaved-1",
                "{\"recipeId\":\"recipe-1\"}", occurredAt);
        MemoryEpisode savedAgain = recordEpisodeAt(userId, "RECIPE_SAVED", "recipe-1", "saved-again-1",
                "{\"recipeId\":\"recipe-1\",\"title\":\"鸡胸肉沙拉\",\"ingredients\":[\"鸡胸肉\"]}",
                occurredAt);
        candidateService.extractAndPersist(userId, savedAgain.getId());

        candidateService.extractAndPersist(userId, unsaved.getId());

        MemoryCandidate oldSaveCandidate = ingredientCandidate(userId, saved.getId());
        MemoryCandidate resaveCandidate = ingredientCandidate(userId, savedAgain.getId());
        assertThat(oldSaveCandidate.getStatus()).isEqualTo("SUPERSEDED");
        assertThat(resaveCandidate.getStatus()).isNotEqualTo("SUPERSEDED");

        consolidationService.consolidate(userId);
        MemoryItem item = ingredientItem(userId);
        assertThat(item.getSourceEpisodeIdsJson())
                .contains(cooked.getId().toString(), liked.getId().toString(), savedAgain.getId().toString())
                .doesNotContain(saved.getId().toString());
        assertThat(item.getEvidenceCount()).isEqualTo(3);
    }

    private MemoryItem ingredientItem(Long userId) {
        return consolidationService.listOwnedItems(userId, 100).stream()
                .filter(item -> "INGREDIENT_PREFERENCE".equals(item.getMemoryType()))
                .filter(item -> "INGREDIENT_CHICKEN".equals(item.getCanonicalGroupId()))
                .filter(item -> "LIKE".equals(item.getPreference()))
                .findFirst().orElse(null);
    }

    private MemoryCandidate ingredientCandidate(Long userId, Long episodeId) {
        return candidateService.listOwned(userId, episodeId, "INGREDIENT_PREFERENCE", 20).stream()
                .filter(candidate -> "INGREDIENT_CHICKEN".equals(candidate.getCanonicalGroupId()))
                .findFirst().orElseThrow();
    }

    private MemoryEpisode recordEpisode(Long userId, String type, String sourceId,
                                        String eventId, String payload) {
        return recordEpisodeAt(userId, type, sourceId, eventId, payload, LocalDateTime.now());
    }

    private MemoryEpisode recordEpisodeAt(Long userId, String type, String sourceId,
                                          String eventId, String payload, LocalDateTime occurredAt) {
        return episodeService.record(userId, new MemoryEpisodeCommand(
                null,
                null,
                type,
                "TEST_BEHAVIOR",
                sourceId,
                eventId,
                "behavior-pattern-" + eventId,
                "测试重复行为：" + eventId,
                payload,
                occurredAt,
                null
        )).episode();
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
}
