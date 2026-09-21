package com.example.food.admin.dashboard.dto;

import java.time.Instant;
import java.util.List;

public record AdminAgentObservabilityResponse(
        Instant generatedAt,
        Metrics metrics,
        RecoveryConfig recovery,
        PersistenceConfig persistence,
        List<HistoryPoint> history,
        List<Alert> alerts
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

    public record PersistenceConfig(
            boolean enabled,
            String snapshotInterval,
            String retention,
            int minimumAlertSamples,
            double failureRateThreshold,
            double recoveryRateThreshold,
            double duplicateWriteRateThreshold
    ) {
    }

    public record HistoryPoint(
            Instant capturedAt,
            String instanceId,
            long runsStarted,
            long runsCompleted,
            long runsFailed,
            long runsRecovered,
            long eventsPersisted,
            long eventsReplayed,
            long duplicateWrites,
            long durationSamples,
            double averageRunDurationMs
    ) {
    }

    public record Alert(
            Long id,
            String alertType,
            String severity,
            String status,
            String title,
            String message,
            double metricValue,
            double thresholdValue,
            Instant firstSeenAt,
            Instant lastSeenAt,
            Instant resolvedAt
    ) {
    }
}
