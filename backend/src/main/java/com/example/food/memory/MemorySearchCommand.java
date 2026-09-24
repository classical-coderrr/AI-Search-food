package com.example.food.memory;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** User-scoped input to the personal-memory retrieval pipeline. */
public record MemorySearchCommand(
        String query,
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
        Integer limit
) {
    public static MemorySearchCommand query(String query) {
        return query(query, null);
    }

    public static MemorySearchCommand query(String query, Long sessionId) {
        return new MemorySearchCommand(query, sessionId, null, null, null, null,
                null, null, null, null, null, null, null);
    }
}
