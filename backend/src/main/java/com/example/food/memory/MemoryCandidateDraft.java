package com.example.food.memory;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The extractor's bounded output before it is attached to a persisted
 * candidate and its provenance record.
 */
public record MemoryCandidateDraft(
        String candidateType,
        String entity,
        String preference,
        BigDecimal strength,
        BigDecimal confidence,
        String sourceType,
        String scope,
        String temporalType,
        String evidenceText,
        boolean explicitConfirmed,
        Map<String, Object> evidence
) {

    public MemoryCandidateDraft {
        evidence = evidence == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(evidence));
    }
}
