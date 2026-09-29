package com.example.food.admin.dashboard.dto;

import java.time.Instant;

public record AdminMemoryObservabilityResponse(
        Instant generatedAt,
        String range,
        Metrics metrics,
        OnlineLabelMetrics onlineLabels
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
            double outdatedFeedbackRate,
            long llmInputTokens,
            long llmOutputTokens,
            long llmTotalTokens,
            long llmUsageCallCount
    ) { }

    public record OnlineLabelMetrics(
            long usedTargetCount,
            long labeledTargetCount,
            double labelCoverageRate,
            long helpfulCount,
            long notRelevantCount,
            long incorrectCount,
            long outdatedCount,
            double notRelevantFeedbackRate,
            double incorrectFeedbackRate,
            double outdatedFeedbackRate,
            int minimumSampleCount,
            boolean sampleSufficient,
            String sampleStatus
    ) { }
}
