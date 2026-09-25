package com.example.food.admin.dashboard.dto;

import java.time.Instant;
import java.util.List;

public record AdminMemoryEvaluationResponse(
        Long id,
        String suiteVersion,
        String status,
        Instant startedAt,
        Instant finishedAt,
        int totalCases,
        int passedCases,
        int failedCases,
        long durationMs,
        Metrics metrics,
        List<CaseResult> cases,
        List<UnmeasuredMetric> unmeasuredMetrics
) {
    public AdminMemoryEvaluationResponse {
        cases = cases == null ? List.of() : List.copyOf(cases);
        unmeasuredMetrics = unmeasuredMetrics == null ? List.of() : List.copyOf(unmeasuredMetrics);
    }

    public record Metrics(
            double extractionPrecision,
            double extractionRecall,
            double rerankRecallAt3,
            double canonicalDedupAccuracy
    ) { }

    public record CaseResult(
            String caseKey,
            String metric,
            String description,
            boolean passed,
            String expected,
            String actual
    ) { }

    public record UnmeasuredMetric(
            String metric,
            String status,
            String reason
    ) { }
}
