package com.example.food.memory;

import java.util.List;

public record MemoryManagementResponse(
        MemoryPersonalizationState personalization,
        long total,
        List<MemoryManagementItemResponse> memories
) { }
