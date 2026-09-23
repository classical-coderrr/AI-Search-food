package com.example.food.memory;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A normalized business-behavior event waiting to be persisted as an Episode.
 */
public record MemoryBehaviorEpisodeEvent(
        Long userId,
        Long sessionId,
        Long conversationId,
        String episodeType,
        String sourceType,
        String sourceId,
        String eventId,
        String idempotencyKey,
        String summary,
        Map<String, Object> payload,
        LocalDateTime occurredAt,
        BigDecimal importance
) {

    public MemoryBehaviorEpisodeEvent {
        payload = payload == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(payload));
    }
}
