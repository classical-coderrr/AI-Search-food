package com.example.food.admin.dashboard.dto;

import java.time.Instant;

public record AdminMemoryVectorIndexStatusResponse(
        Instant generatedAt,
        boolean enabled,
        String embeddingModel,
        String qdrantStatus,
        boolean qdrantAvailable,
        long databaseVectorCount,
        long indexedVectorCount,
        long unindexedVectorCount,
        long pendingJobCount,
        long processingJobCount,
        long retryingJobCount,
        long completedJobCount,
        long qdrantPointCount,
        long qdrantIndexedVectorCount
) { }
