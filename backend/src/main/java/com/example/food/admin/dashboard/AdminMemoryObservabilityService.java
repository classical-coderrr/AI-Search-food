package com.example.food.admin.dashboard;

import com.example.food.admin.dashboard.dto.AdminMemoryObservabilityResponse;
import com.example.food.memory.MemoryFeedbackService;
import com.example.food.memory.MemoryRetrievalTraceService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;

@Service
public class AdminMemoryObservabilityService {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final MemoryRetrievalTraceService traceService;
    private final MemoryFeedbackService feedbackService;
    private final Clock clock;

    public AdminMemoryObservabilityService(MemoryRetrievalTraceService traceService,
                                          MemoryFeedbackService feedbackService,
                                          Clock clock) {
        this.traceService = traceService;
        this.feedbackService = feedbackService;
        this.clock = clock;
    }

    public AdminMemoryObservabilityResponse snapshot(String requestedRange) {
        String range = requestedRange == null || requestedRange.isBlank()
                ? "24h" : requestedRange.trim().toLowerCase();
        Duration duration = switch (range) {
            case "24h" -> Duration.ofHours(24);
            case "7d" -> Duration.ofDays(7);
            case "30d" -> Duration.ofDays(30);
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "记忆观测时间范围无效");
        };
        LocalDateTime from = LocalDateTime.ofInstant(Instant.now(clock).minus(duration), ZONE);
        Map<String, Object> summary = traceService.summarizeSince(from);
        Map<String, Object> feedback = feedbackService.summarizeSince(from);
        long feedbackCount = integer(feedback, "feedbackCount");
        return new AdminMemoryObservabilityResponse(Instant.now(clock), range,
                new AdminMemoryObservabilityResponse.Metrics(
                        integer(summary, "totalCount"), integer(summary, "successCount"),
                        integer(summary, "degradedCount"), integer(summary, "truncatedCount"),
                        decimal(summary, "averageCandidates"), decimal(summary, "averageEstimatedTokens"),
                        decimal(summary, "averageLatencyMs"),
                        feedbackCount,
                        integer(feedback, "helpfulCount"),
                        integer(feedback, "notRelevantCount"),
                        integer(feedback, "incorrectCount"),
                        integer(feedback, "outdatedCount"),
                        ratio(feedback, "helpfulCount", feedbackCount),
                        ratio(feedback, "notRelevantCount", feedbackCount),
                        ratio(feedback, "incorrectCount", feedbackCount),
                        ratio(feedback, "outdatedCount", feedbackCount)
                ));
    }

    private long integer(Map<String, Object> summary, String key) {
        return Math.max(0L, number(summary, key).longValue());
    }

    private double decimal(Map<String, Object> summary, String key) {
        return Math.max(0D, number(summary, key).doubleValue());
    }

    private double ratio(Map<String, Object> summary, String key, long total) {
        return total == 0 ? 0D : (double) integer(summary, key) / total;
    }

    private Number number(Map<String, Object> summary, String key) {
        if (summary == null) return 0;
        Object value = summary.entrySet().stream()
                .filter(entry -> key.equalsIgnoreCase(entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst().orElse(0);
        return value instanceof Number number ? number : 0;
    }
}
