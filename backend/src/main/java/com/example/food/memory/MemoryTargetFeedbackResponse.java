package com.example.food.memory;

import java.time.LocalDateTime;

public record MemoryTargetFeedbackResponse(
        Long id,
        String traceId,
        MemoryTargetFeedbackSource sourceKind,
        Long sourceId,
        MemoryFeedbackType feedbackType,
        boolean updated,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) { }
