package com.example.food.memory;

import java.time.LocalDateTime;

public record MemoryFeedbackStatusResponse(
        boolean eligible,
        MemoryFeedbackType feedbackType,
        LocalDateTime updatedAt
) { }
