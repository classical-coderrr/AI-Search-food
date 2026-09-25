package com.example.food.memory;

import jakarta.validation.constraints.NotNull;

public record MemoryConfirmationDecisionRequest(
        @NotNull MemoryConfirmationDecision decision,
        @NotNull Integer version
) {
}
