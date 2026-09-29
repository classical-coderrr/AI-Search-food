package com.example.food.admin.dashboard.dto;

import java.time.Instant;

public record AdminMemoryVectorIndexRebuildResponse(
        Instant requestedAt,
        String embeddingModel,
        int vectorRowsReset,
        int upsertJobsQueued,
        String status
) { }
