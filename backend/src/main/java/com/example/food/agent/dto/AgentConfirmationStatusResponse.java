package com.example.food.agent.dto;

import java.time.LocalDateTime;

public record AgentConfirmationStatusResponse(
        Long confirmationId,
        Long conversationId,
        String actionType,
        String status,
        LocalDateTime createdAt,
        LocalDateTime processingAt,
        LocalDateTime confirmedAt,
        String resultMessage,
        String errorCode,
        String errorMessage
) {
}
