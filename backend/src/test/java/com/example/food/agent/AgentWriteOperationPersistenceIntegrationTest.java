package com.example.food.agent;

import com.example.food.agent.dto.AgentWriteOperationStatusResponse;
import com.example.food.security.AppRole;
import com.example.food.security.AuthPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class AgentWriteOperationPersistenceIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AgentWriteOperationService operationService;

    private Long userId;
    private Long confirmationId;

    @BeforeEach
    void setUp() {
        String phone = "139" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        jdbcTemplate.update(
                "INSERT INTO users (phone, nickname, role, enabled) VALUES (?, ?, 'USER', 1)",
                phone,
                "Agent 写幂等集成测试"
        );
        userId = jdbcTemplate.queryForObject("SELECT id FROM users WHERE phone = ?", Long.class, phone);
        jdbcTemplate.update(
                "INSERT INTO agent_conversations (user_id, title) VALUES (?, ?)",
                userId,
                "写操作幂等测试"
        );
        Long conversationId = jdbcTemplate.queryForObject(
                "SELECT id FROM agent_conversations WHERE user_id = ? ORDER BY id DESC LIMIT 1",
                Long.class,
                userId
        );
        jdbcTemplate.update(
                "INSERT INTO agent_confirmations (conversation_id, user_id, action_type, idempotency_key, payload_json, status) VALUES (?, ?, ?, ?, ?, 'PENDING')",
                conversationId,
                userId,
                "PANTRY_CREATE",
                key(),
                "{}"
        );
        confirmationId = jdbcTemplate.queryForObject(
                "SELECT id FROM agent_confirmations WHERE user_id = ? ORDER BY id DESC LIMIT 1",
                Long.class,
                userId
        );
    }

    @AfterEach
    void tearDown() {
        if (userId != null) {
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
        }
    }

    @Test
    void persistsOneCompletedLedgerRowAndReplaysItAfterTheSameKeyIsSubmittedAgain() {
        String idempotencyKey = confirmationKey();
        AgentWriteOperationService.ClaimResult first = operationService.claim(
                userId, confirmationId, "PANTRY_CREATE", idempotencyKey);
        assertThat(first.acquired()).isTrue();

        operationService.complete(userId, idempotencyKey, "食材已加入库存", java.util.Map.of("itemId", 12));

        AgentWriteOperationService.ClaimResult second = operationService.claim(
                userId, confirmationId, "PANTRY_CREATE", idempotencyKey);
        assertThat(second.acquired()).isFalse();
        assertThat(second.operation().getStatus()).isEqualTo(AgentWriteOperationService.STATUS_COMPLETED);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_write_operations WHERE user_id = ? AND idempotency_key = ?",
                Integer.class,
                userId,
                idempotencyKey
        )).isEqualTo(1);

        AgentWriteOperationStatusResponse status = operationService.status(
                new AuthPrincipal(userId, "agent-test", AppRole.USER), idempotencyKey);
        assertThat(status.status()).isEqualTo(AgentWriteOperationService.STATUS_COMPLETED);
        assertThat(status.resultMessage()).isEqualTo("食材已加入库存");
        assertThat(status.result().path("itemId").asLong()).isEqualTo(12L);
    }

    @Test
    void keepsProcessingRowVisibleAfterAWriteWindowCrashInsteadOfAllowingAnUnsafeRetry() {
        String idempotencyKey = confirmationKey();
        AgentWriteOperationService.ClaimResult first = operationService.claim(
                userId, confirmationId, "PANTRY_CREATE", idempotencyKey);
        assertThat(first.acquired()).isTrue();

        AgentWriteOperationService.ClaimResult retry = operationService.claim(
                userId, confirmationId, "PANTRY_CREATE", idempotencyKey);
        assertThat(retry.acquired()).isFalse();
        assertThat(retry.operation().getStatus()).isEqualTo(AgentWriteOperationService.STATUS_PROCESSING);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_write_operations WHERE user_id = ? AND idempotency_key = ? AND status = 'PROCESSING'",
                Integer.class,
                userId,
                idempotencyKey
        )).isEqualTo(1);
    }

    private String key() {
        return "integration-key-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private String confirmationKey() {
        return jdbcTemplate.queryForObject(
                "SELECT idempotency_key FROM agent_confirmations WHERE id = ?",
                String.class,
                confirmationId
        );
    }
}
