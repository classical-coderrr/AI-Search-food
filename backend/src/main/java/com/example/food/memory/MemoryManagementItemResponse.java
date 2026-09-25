package com.example.food.memory;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record MemoryManagementItemResponse(
        Long id,
        String memoryType,
        String entity,
        String preference,
        BigDecimal strength,
        BigDecimal confidence,
        Integer evidenceCount,
        Integer occurrenceCount,
        Integer sourceCount,
        String scope,
        String temporalType,
        LocalDateTime firstSeenAt,
        LocalDateTime lastSeenAt,
        Integer version,
        boolean userModified,
        boolean editable
) { }
