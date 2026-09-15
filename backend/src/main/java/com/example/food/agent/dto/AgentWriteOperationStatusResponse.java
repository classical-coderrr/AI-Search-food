package com.example.food.agent.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDateTime;

public record AgentWriteOperationStatusResponse(
        Long operationId,
        Long confirmationId,
        String actionType,
        String idempotencyKey,
        String status,
        LocalDateTime createdAt,
        LocalDateTime startedAt,
        LocalDateTime completedAt,
        String resultMessage,
        JsonNode result,
        String errorCode,
        String errorMessage
) {
}
