package com.example.food.memory;

import java.math.BigDecimal;

public record TagNormalizationResult(
        String originalEntity,
        String canonicalId,
        Long canonicalTagId,
        String canonicalName,
        String category,
        String parentCanonicalId,
        String canonicalGroupId,
        BigDecimal confidence,
        String source
) {

    public boolean mapped() {
        return canonicalTagId != null;
    }
}
