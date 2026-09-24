package com.example.food.memory;

import java.time.LocalDateTime;
import java.util.List;

public record MemoryRetrievalResult(
        MemoryQueryPlan queryPlan,
        List<MemorySearchHit> hits,
        RetrievalTrace trace
) {
    public record RetrievalTrace(
            Long userId,
            Long sessionId,
            LocalDateTime retrievedAt,
            int memoryItemCandidates,
            int episodeCandidates,
            List<Long> selectedMemoryItemIds,
            List<Long> selectedEpisodeIds
    ) { }
}
