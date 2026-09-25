package com.example.food.memory;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record MemoryPersonalizationRequest(
        @NotNull Boolean enabled,
        @NotNull @PositiveOrZero Integer version
) { }
