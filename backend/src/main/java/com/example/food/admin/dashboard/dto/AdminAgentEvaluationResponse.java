package com.example.food.admin.dashboard.dto;

import java.time.Instant;
import java.util.List;

public record AdminAgentEvaluationResponse(
        Long id,
        String status,
        Instant startedAt,
        Instant finishedAt,
        int totalCases,
        int passedCases,
        int failedCases,
        long durationMs,
        List<CaseResult> cases
) {

    public record CaseResult(
            String caseKey,
            String description,
            String inputMessage,
            boolean passed,
            List<String> expectedTools,
            List<String> actualTools,
            String failureReason
    ) {
    }
}
