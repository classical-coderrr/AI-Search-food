package com.example.food.admin.dashboard.dto;

import java.time.Instant;

public record AdminMemoryObservabilityResponse(
        Instant generatedAt,
        String range,
        Metrics metrics
) {
    public record Metrics(
            long retrievalCount,
            long successCount,
            long degradedCount,
            long truncatedCount,
            double averageCandidates,
            double averageEstimatedTokens,
            double averageLatencyMs,
            long feedbackCount,
            long helpfulFeedbackCount,
            long notRelevantFeedbackCount,
            long inaccurateFeedbackCount,
            long outdatedFeedbackCount,
            double helpfulFeedbackRate,
            double notRelevantFeedbackRate,
            double inaccurateFeedbackRate,
            double outdatedFeedbackRate
    ) { }
}
