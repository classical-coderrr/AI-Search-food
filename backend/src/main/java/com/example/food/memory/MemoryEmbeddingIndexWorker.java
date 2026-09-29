package com.example.food.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class MemoryEmbeddingIndexWorker {
    private static final Logger log = LoggerFactory.getLogger(MemoryEmbeddingIndexWorker.class);
    private final MemoryEmbeddingIndexService indexService;

    public MemoryEmbeddingIndexWorker(MemoryEmbeddingIndexService indexService) {
        this.indexService = indexService;
    }

    @Scheduled(fixedDelayString = "${app.memory.embedding.poll-interval:PT30S}")
    public void indexPending() {
        try {
            int indexed = indexService.indexPending();
            if (indexed > 0) log.info("Indexed {} personal-memory vectors", indexed);
        } catch (RuntimeException failure) {
            log.warn("Memory vector indexing deferred; lexical retrieval remains available ({})",
                    failure.getClass().getSimpleName());
        }
    }
}
