package com.example.food.memory;

public record MemoryVectorDocument(Long userId, String sourceKind, Long sourceId, String memoryType,
                                   Integer sourceVersion, String model, float[] embedding) { }
