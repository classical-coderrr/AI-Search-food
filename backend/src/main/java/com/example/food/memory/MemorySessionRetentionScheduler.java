package com.example.food.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/** Redacts expired short-lived session state while preserving episode provenance. */
@Component
public class MemorySessionRetentionScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger(MemorySessionRetentionScheduler.class);

    private final MemorySessionService sessionService;
    private final int batchSize;

    public MemorySessionRetentionScheduler(
            MemorySessionService sessionService,
            @Value("${app.memory.session.cleanup-batch-size:200}") int batchSize
    ) {
        this.sessionService = sessionService;
        this.batchSize = Math.max(1, Math.min(batchSize, 1000));
    }

    @Scheduled(fixedDelayString = "${app.memory.session.cleanup-interval:PT5M}")
    public void redactExpiredSessions() {
        try {
            int expired = sessionService.expireDue(LocalDateTime.now(), batchSize);
            if (expired > 0) {
                LOGGER.info("Expired memory sessions redacted, count={}", expired);
            }
        } catch (RuntimeException exception) {
            LOGGER.warn("Expired memory session cleanup failed, errorType={}",
                    exception.getClass().getSimpleName());
        }
    }
}
