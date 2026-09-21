package com.example.food.admin.dashboard.dto;

import java.time.Instant;

public record AdminAgentObservabilityResponse(
        Instant generatedAt,
        Metrics metrics,
        RecoveryConfig recovery
) {

    public record Metrics(
            long runsStarted,
            long runsCompleted,
            long runsFailed,
            long runsRecovered,
            long eventsPersisted,
            long eventsReplayed,
            long duplicateWrites,
            long durationSamples,
            double averageRunDurationMs,
            double successRate,
            double recoveryRate,
            double duplicateWriteRate
    ) {
    }

    public record RecoveryConfig(
            boolean enabled,
            String scanDelay,
            String staleAfter,
            String leaseDuration,
            String stateStore
    ) {
    }
}
