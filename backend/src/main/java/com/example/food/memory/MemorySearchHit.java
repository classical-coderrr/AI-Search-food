package com.example.food.memory;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** A memory-only candidate plus an explainable score breakdown. */
public record MemorySearchHit(
        String sourceKind,
        Long id,
        String memoryType,
        String title,
        String content,
        String payload,
        String preference,
        String temporalType,
        BigDecimal confidence,
        BigDecimal importance,
        LocalDateTime occurredAt,
        ScoreBreakdown scores
) {
    public record ScoreBreakdown(
            double semantic,
            double recency,
            double importance,
            double confidence,
            double feedback,
            double contextMatch,
            double total
    ) { }
}
