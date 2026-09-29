package com.example.food.admin.dashboard;

import com.example.food.admin.dashboard.dto.AdminMemoryObservabilityResponse;
import com.example.food.admin.dashboard.dto.AdminMemoryObservabilityResponse.OnlineLabelMetrics;
import com.example.food.memory.MemoryFeedbackService;
import com.example.food.memory.MemoryRetrievalTraceService;
import com.example.food.memory.MemoryTargetFeedbackService;
import org.springframework.beans.factory.annotation.Value;
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
    private final MemoryTargetFeedbackService targetFeedbackService;
    private final Clock clock;
    private final int minimumLabelCount;

    public AdminMemoryObservabilityService(MemoryRetrievalTraceService traceService,
                                          MemoryFeedbackService feedbackService,
                                          MemoryTargetFeedbackService targetFeedbackService,
                                          Clock clock,
                                          @Value("${app.memory.evaluation.minimum-online-label-count:20}") int minimumLabelCount) {
        this.traceService = traceService;
        this.feedbackService = feedbackService;
        this.targetFeedbackService = targetFeedbackService;
        this.clock = clock;
        this.minimumLabelCount = Math.max(1, Math.min(minimumLabelCount, 100_000));
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
        Map<String, Object> targetFeedback = targetFeedbackService.summarizeUsageFeedbackSince(from);
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
                        ratio(feedback, "outdatedCount", feedbackCount),
                        integer(summary, "llmInputTokens"),
                        integer(summary, "llmOutputTokens"),
                        integer(summary, "llmTotalTokens"),
                        integer(summary, "llmUsageCallCount")
                ), onlineLabels(summary, targetFeedback));
    }

    private OnlineLabelMetrics onlineLabels(Map<String, Object> usage, Map<String, Object> feedback) {
        long usedTargets = integer(usage, "usedMemoryTargetCount");
        long labeledTargets = integer(feedback, "labeledTargetCount");
        boolean sufficient = labeledTargets >= minimumLabelCount;
        String status = usedTargets == 0 ? "NO_MEMORY_USAGE"
                : labeledTargets == 0 ? "AWAITING_FEEDBACK"
                : sufficient ? "SAMPLE_SUFFICIENT" : "INSUFFICIENT_LABELS";
        return new OnlineLabelMetrics(usedTargets, labeledTargets, ratio(labeledTargets, usedTargets),
                integer(feedback, "helpfulCount"), integer(feedback, "notRelevantCount"),
                integer(feedback, "incorrectCount"), integer(feedback, "outdatedCount"),
                ratio(feedback, "notRelevantCount", labeledTargets),
                ratio(feedback, "incorrectCount", labeledTargets),
                ratio(feedback, "outdatedCount", labeledTargets),
                minimumLabelCount, sufficient, status);
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

    private double ratio(long numerator, long denominator) {
        return denominator == 0 ? 0D : (double) numerator / denominator;
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
