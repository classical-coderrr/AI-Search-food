package com.example.food.agent.dto;

import java.time.LocalDateTime;
import java.util.List;

public record AgentConversationHistoryResponse(
        Long conversationId,
        String title,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        List<AgentMessageResponse> messages,
        AgentRunStatusResponse activeRun
) {
    public AgentConversationHistoryResponse {
        messages = messages == null ? List.of() : List.copyOf(messages);
    }
}
