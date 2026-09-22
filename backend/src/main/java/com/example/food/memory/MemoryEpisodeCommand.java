package com.example.food.memory;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record MemoryEpisodeCommand(
        Long sessionId,
        Long conversationId,
        String episodeType,
        String sourceType,
        String sourceId,
        String eventId,
        String idempotencyKey,
        String summary,
        String payloadJson,
        LocalDateTime occurredAt,
        BigDecimal importance
) {
}
