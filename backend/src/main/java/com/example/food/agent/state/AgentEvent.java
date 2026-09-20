package com.example.food.agent.state;

import java.time.Instant;

/**
 * Durable SSE event envelope. The sequence is scoped to one agent run and is
 * intentionally independent from the HTTP connection that delivered it.
 */
public record AgentEvent(
        String runId,
        long sequence,
        String event,
        String dataJson,
        Instant createdAt
) {
}
