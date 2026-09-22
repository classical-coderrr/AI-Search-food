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
class MemoryPersistenceIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MemorySessionService sessionService;

    @Autowired
    private MemoryEpisodeService episodeService;

    @Test
    void flywayCreatesMemoryTablesAndPreservesUserIsolationAndIdempotency() {
        assertThat(tableExists("agent_sessions")).isTrue();
        assertThat(tableExists("memory_episodes")).isTrue();
        assertThat(columnExists("memory_episodes", "payload_json")).isTrue();
        assertThat(indexExists("uq_memory_episodes_user_key")).isTrue();

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
}
