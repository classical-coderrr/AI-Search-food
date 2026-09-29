package com.example.food.memory;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MemorySessionRetentionIntegrationTest {

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private MemorySessionService sessionService;
    @Autowired private MemoryEpisodeService episodeService;

    @Test
    void redactsExpiredContextAndPreservesEpisodeProvenanceAndSessionKeyReuse() {
        Long userId = insertUser("session-retention");
        LocalDateTime now = LocalDateTime.now();
        MemorySession expired = sessionService.open(userId, openCommand(
                "expired-session", "旧任务", "{\"ingredients\":[\"香菜\"]}", now.plusHours(1)));
        MemorySession active = sessionService.open(userId, openCommand(
                "active-session", "仍有效", "{\"ingredients\":[\"鸡胸肉\"]}", now.plusHours(2)));
        MemoryEpisode episode = episodeService.record(userId, new MemoryEpisodeCommand(
                expired.getId(), null, "RECIPE_SEARCH", "SEARCH", "search-123",
                "session-retention-event", "session-retention-idempotency",
                "到期会话来源事件", "{}", now, new BigDecimal("0.5000")
        )).episode();
        LocalDateTime cutoff = now.plusMinutes(1);
        jdbcTemplate.update("UPDATE agent_sessions SET expires_at = ? WHERE id = ?",
                now.minusMinutes(1), expired.getId());

        assertThatThrownBy(() -> sessionService.findOwned(userId, expired.getId()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("记忆会话不存在");
        assertThat(sessionService.expireDue(cutoff, 1)).isEqualTo(1);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM agent_sessions WHERE id = ?", String.class, expired.getId()))
                .isEqualTo("EXPIRED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_sessions WHERE id = ? AND current_task IS NULL "
                        + "AND current_goal IS NULL AND context_json IS NULL "
                        + "AND selected_memory_ids_json IS NULL AND retrieved_knowledge_ids_json IS NULL "
                        + "AND agent_state_json IS NULL",
                Integer.class, expired.getId())).isEqualTo(1);
        assertThat(episodeService.listOwned(userId, expired.getId(), null, 10))
                .extracting(MemoryEpisode::getId).containsExactly(episode.getId());
        assertThatThrownBy(() -> sessionService.findOwned(userId, expired.getId()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("记忆会话不存在");
        assertThat(sessionService.findOwned(userId, active.getId()).getCurrentTask()).isEqualTo("仍有效");

        MemorySession reopened = sessionService.open(userId, openCommand(
                "expired-session", "新任务", "{\"mealType\":\"dinner\"}", now.plusHours(3)));
        assertThat(reopened.getId()).isEqualTo(expired.getId());
        assertThat(reopened.getStatus()).isEqualTo("ACTIVE");
        assertThat(reopened.getCurrentTask()).isEqualTo("新任务");
        assertThat(reopened.getContextJson()).contains("dinner");
    }

    private MemorySessionOpenCommand openCommand(String key, String task, String context, LocalDateTime expiry) {
        return new MemorySessionOpenCommand(null, key, task, "RECOMMEND_RECIPE", context,
                "[]", "[]", "{}", expiry);
    }

    private Long insertUser(String label) {
        String phone = "139" + Long.toUnsignedString(System.nanoTime()).substring(0, 8);
        jdbcTemplate.update("INSERT INTO users (phone, nickname) VALUES (?, ?)", phone, label);
        return jdbcTemplate.queryForObject("SELECT id FROM users WHERE phone = ?", Long.class, phone);
    }
}
