package com.example.food.agent.dto;

import com.example.food.agent.state.AgentNode;
import com.example.food.agent.state.AgentStatus;

import java.time.Instant;

public record AgentRunStatusResponse(
        String runId,
        Long conversationId,
        AgentStatus status,
        AgentNode currentNode,
        AgentNode nextNode,
        String errorCode,
        String errorMessage,
        Instant updatedAt
) {
}
