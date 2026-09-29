package com.example.food.memory;

import java.util.List;

/** One task-scoped adjudication; both source memories remain active and unchanged. */
public record MemoryConflictResolution(
        String conflictKey,
        String domain,
        String canonicalEntity,
        Long likeMemoryItemId,
        Long dislikeMemoryItemId,
        Long selectedMemoryItemId,
        String selectedPreference,
        String resolutionType,
        String reason,
        String explanation,
        List<String> contextSignals
) {
    public MemoryConflictResolution {
        contextSignals = contextSignals == null ? List.of() : List.copyOf(contextSignals);
    }
}
