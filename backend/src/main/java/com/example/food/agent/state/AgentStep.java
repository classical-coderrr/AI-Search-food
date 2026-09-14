package com.example.food.agent.state;

import java.time.Instant;

public record AgentStep(
        String runId,
        long stepNo,
        AgentNode node,
        String action,
        String toolName,
        String status,
        String requestJson,
        String responseJson,
        String idempotencyKey,
        Instant startedAt,
        Instant finishedAt,
        String errorMessage
) {
}
