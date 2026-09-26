package com.example.food.memory;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record MemoryFeedbackRequest(
        @NotBlank @Size(max = 64) String traceId,
        @NotNull MemoryFeedbackType feedbackType
) { }
