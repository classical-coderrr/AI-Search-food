package com.example.food.memory;

import java.util.List;

/** Provider boundary for query and memory-document embeddings. */
public interface MemoryEmbeddingClient {
    boolean isAvailable();
    String model();
    List<float[]> embed(List<String> texts);

    default float[] embedOne(String text) {
        List<float[]> vectors = embed(List.of(text));
        return vectors.size() == 1 ? vectors.get(0) : null;
    }
}
