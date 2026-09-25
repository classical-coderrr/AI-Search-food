package com.example.food.memory;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

public record MemoryItemUpdateRequest(
        @NotBlank String preference,
        @NotNull @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal strength,
        @NotNull @PositiveOrZero Integer version
) { }
