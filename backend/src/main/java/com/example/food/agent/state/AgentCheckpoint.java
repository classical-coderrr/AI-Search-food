package com.example.food.agent.state;

import java.time.Instant;

public record AgentCheckpoint(
        String runId,
        long version,
        AgentState state,
        Instant savedAt
) {
}
