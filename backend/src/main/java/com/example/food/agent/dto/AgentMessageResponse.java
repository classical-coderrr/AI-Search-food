package com.example.food.agent.dto;

import java.time.LocalDateTime;

public record AgentMessageResponse(
        Long id,
        String role,
        String blockType,
        String content,
        LocalDateTime createdAt
) {
}
