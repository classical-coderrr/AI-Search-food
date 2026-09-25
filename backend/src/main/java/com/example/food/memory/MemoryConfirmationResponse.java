package com.example.food.memory;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record MemoryConfirmationResponse(
        Long candidateId,
        String candidateType,
        String entity,
        String proposedPreference,
        BigDecimal confidence,
        int evidenceCount,
        List<String> evidenceSummaries,
        LocalDateTime firstSeenAt,
        LocalDateTime lastSeenAt,
        Integer version
) {
    public MemoryConfirmationResponse {
        evidenceSummaries = evidenceSummaries == null ? List.of() : List.copyOf(evidenceSummaries);
    }
}
