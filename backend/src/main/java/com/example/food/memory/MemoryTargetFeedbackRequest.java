package com.example.food.memory;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record MemoryTargetFeedbackRequest(
        @NotBlank @Size(max = 64) String traceId,
        @NotNull MemoryTargetFeedbackSource sourceKind,
        @NotNull @Positive Long sourceId,
        @NotNull MemoryFeedbackType feedbackType
) { }
