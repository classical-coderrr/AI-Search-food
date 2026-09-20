package com.example.food.agent.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

public record AgentEventResponse(
        String runId,
        long eventSeq,
        String event,
        JsonNode data,
        Instant createdAt
) {
}
