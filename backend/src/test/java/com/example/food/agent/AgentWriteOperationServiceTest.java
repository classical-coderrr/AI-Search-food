package com.example.food.agent;

import com.example.food.agent.dto.AgentWriteOperationStatusResponse;
import com.example.food.security.AppRole;
import com.example.food.security.AuthPrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentWriteOperationServiceTest {

    private final AgentWriteOperationMapper mapper = mock(AgentWriteOperationMapper.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AgentWriteOperationService service = new AgentWriteOperationService(mapper, objectMapper);

    @Test
    void claimsNewKeyAndMarksItAsProcessing() {
        AgentWriteOperationService.ClaimResult result = service.claim(7L, 9L, "PANTRY_UPDATE", "key-1");

        assertThat(result.acquired()).isTrue();
        assertThat(result.operation().getStatus()).isEqualTo("PROCESSING");
        assertThat(result.operation().getIdempotencyKey()).isEqualTo("key-1");
        verify(mapper).insert(any(AgentWriteOperation.class));
    }

    @Test
    void returnsExistingOperationInsteadOfClaimingTheSameKeyTwice() {
        AgentWriteOperation existing = operation("COMPLETED");
        when(mapper.findOwnedByKey(7L, "key-1")).thenReturn(existing);

        AgentWriteOperationService.ClaimResult result = service.claim(7L, 9L, "PANTRY_UPDATE", "key-1");

        assertThat(result.acquired()).isFalse();
        assertThat(result.operation()).isSameAs(existing);
    }

    @Test
    void returnsStoredResultAsJsonForClientQuery() {
        AgentWriteOperation existing = operation("COMPLETED");
        existing.setResultJson("{\"itemId\":12,\"quantity\":3}");
        existing.setResultMessage("库存已更新");
        when(mapper.findOwnedByKey(7L, "key-1")).thenReturn(existing);

        AgentWriteOperationStatusResponse response = service.status(
                new AuthPrincipal(7L, "13800138000", AppRole.USER), " key-1 ");

        assertThat(response.status()).isEqualTo("COMPLETED");
        assertThat(response.result().path("itemId").asLong()).isEqualTo(12L);
        assertThat(response.resultMessage()).isEqualTo("库存已更新");
    }

    @Test
    void persistsDeterministicFailureForLaterStatusQuery() {
        service.fail(7L, "key-1", "400", "操作参数不正确");

        verify(mapper).markFailed(7L, "key-1", "400", "操作参数不正确");
    }

    private AgentWriteOperation operation(String status) {
        AgentWriteOperation operation = new AgentWriteOperation();
        operation.setId(1L);
        operation.setUserId(7L);
        operation.setConfirmationId(9L);
        operation.setActionType("PANTRY_UPDATE");
        operation.setIdempotencyKey("key-1");
        operation.setStatus(status);
        operation.setCreatedAt(LocalDateTime.now());
        return operation;
    }
}
