package com.example.food.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class MemoryRetrievalTraceRetentionScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger(MemoryRetrievalTraceRetentionScheduler.class);

    private final MemoryRetrievalTraceService traceService;
    private final Duration retention;

    public MemoryRetrievalTraceRetentionScheduler(
            MemoryRetrievalTraceService traceService,
            @Value("${app.memory.observability.trace-retention:P30D}") Duration retention
    ) {
        this.traceService = traceService;
        this.retention = retention;
    }

    @Scheduled(fixedDelayString = "${app.memory.observability.trace-cleanup-interval:PT6H}")
    public void removeExpiredTraces() {
        try {
            int removed = traceService.deleteExpired(retention);
            if (removed > 0) LOGGER.info("Expired memory retrieval traces removed, count={}", removed);
        } catch (RuntimeException exception) {
            LOGGER.warn("Memory retrieval trace retention failed, errorType={}",
                    exception.getClass().getSimpleName());
        }
    }
}
