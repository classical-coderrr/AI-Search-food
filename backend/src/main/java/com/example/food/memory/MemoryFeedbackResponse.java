package com.example.food.memory;

import java.time.LocalDateTime;

public record MemoryFeedbackResponse(
        Long id,
        String traceId,
        MemoryFeedbackType feedbackType,
        boolean updated,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) { }
