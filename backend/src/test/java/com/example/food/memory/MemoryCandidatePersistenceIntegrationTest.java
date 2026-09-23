package com.example.food.memory;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

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
