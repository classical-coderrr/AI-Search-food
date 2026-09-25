package com.example.food.memory;

public record MemoryClearResult(
        int episodesDeleted,
        int candidatesDeleted,
        int memoriesDeleted,
        int sessionsDeleted,
        int retrievalTracesDeleted
) { }
