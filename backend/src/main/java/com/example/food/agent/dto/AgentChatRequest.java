package com.example.food.agent.dto;

import jakarta.validation.constraints.Size;

public record AgentChatRequest(
        Long conversationId,
        @Size(max = 1000)
        String message,
        Long confirmationId,
        @Size(max = 64)
        String idempotencyKey,
        Long targetRecipeSearchLogId,
        @Size(max = 200)
        String previousRecipeTitle
) {
    public AgentChatRequest(
            Long conversationId,
            String message,
            Long confirmationId,
            String idempotencyKey
    ) {
        this(conversationId, message, confirmationId, idempotencyKey, null, null);
    }
}
