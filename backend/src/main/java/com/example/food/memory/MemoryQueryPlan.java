package com.example.food.memory;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** Structured rewrite; Knowledge RAG deliberately has no fields or source here. */
public record MemoryQueryPlan(
        String originalQuery,
        String intent,
        String rewrittenQuery,
        Long sessionId,
        List<String> memoryTypes,
        List<String> episodeTypes,
        List<String> scenes,
        List<String> mealTypes,
        LocalDateTime timeFrom,
        LocalDateTime timeTo,
        List<String> ingredients,
        List<String> dietGoals,
        BigDecimal minConfidence,
        BigDecimal minImportance,
        int limit
) {
}
