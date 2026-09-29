package com.example.food.memory;

public record MemoryTargetFeedbackTargetResponse(
        MemoryTargetFeedbackSource sourceKind,
        Long sourceId,
        String title,
        String detail,
        MemoryFeedbackType feedbackType
) { }
