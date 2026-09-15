package com.example.food.agent.state;

import java.time.Instant;

public record AgentRun(
        String runId,
        Long userId,
        Long conversationId,
        AgentStatus status,
        AgentNode currentNode,
        AgentNode nextNode,
        boolean recoverable,
        Instant lastHeartbeatAt,
        Instant createdAt,
        Instant updatedAt,
        String errorCode,
        String errorMessage
) {
}
