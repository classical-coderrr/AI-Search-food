package com.example.food.memory;

import java.util.List;

/** Pluggable personal-memory vector store. Every read and mutation is scoped by userId. */
public interface MemoryVectorStoreAdapter {
    void upsert(MemoryVectorDocument document);
    List<MemoryVectorMatch> search(Long userId, String model, float[] queryVector, int limit);
    void deleteSource(Long userId, String sourceKind, Long sourceId);
    void deleteAll(Long userId);
}
