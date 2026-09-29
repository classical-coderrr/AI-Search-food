package com.example.food.admin.dashboard;

import com.example.food.admin.dashboard.dto.AdminMemoryObservabilityResponse.OnlineLabelMetrics;
import com.example.food.memory.MemoryFeedbackService;
import com.example.food.memory.MemoryRetrievalTraceService;
import com.example.food.memory.MemoryTargetFeedbackService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminMemoryObservabilityServiceTest {

    @Test
    void reportsRatesOnlyAsVoluntaryLabelProxiesWithCoverageAndSampleStatus() {
        MemoryRetrievalTraceService traceService = mock(MemoryRetrievalTraceService.class);
        MemoryFeedbackService feedbackService = mock(MemoryFeedbackService.class);
        MemoryTargetFeedbackService targetFeedbackService = mock(MemoryTargetFeedbackService.class);
        when(traceService.summarizeSince(any())).thenReturn(Map.of(
                "usedMemoryTargetCount", 10L,
                "llmInputTokens", 120L,
                "llmOutputTokens", 45L,
                "llmTotalTokens", 165L,
                "llmUsageCallCount", 3L));
        when(feedbackService.summarizeSince(any())).thenReturn(Map.of("feedbackCount", 0L));
        when(targetFeedbackService.summarizeUsageFeedbackSince(any())).thenReturn(Map.of(
                "labeledTargetCount", 3L,
                "helpfulCount", 1L,
                "notRelevantCount", 1L,
                "incorrectCount", 1L,
                "outdatedCount", 0L));

        AdminMemoryObservabilityService service = service(traceService, feedbackService, targetFeedbackService, 4);

        var snapshot = service.snapshot("30d");
        OnlineLabelMetrics online = snapshot.onlineLabels();
        assertThat(online.usedTargetCount()).isEqualTo(10);
        assertThat(online.labeledTargetCount()).isEqualTo(3);
        assertThat(online.labelCoverageRate()).isEqualTo(0.3D);
        assertThat(online.incorrectFeedbackRate()).isEqualTo(1D / 3D);
        assertThat(online.notRelevantFeedbackRate()).isEqualTo(1D / 3D);
        assertThat(online.outdatedFeedbackRate()).isZero();
        assertThat(online.minimumSampleCount()).isEqualTo(4);
        assertThat(online.sampleSufficient()).isFalse();
        assertThat(online.sampleStatus()).isEqualTo("INSUFFICIENT_LABELS");
        assertThat(snapshot.metrics().llmInputTokens()).isEqualTo(120L);
        assertThat(snapshot.metrics().llmOutputTokens()).isEqualTo(45L);
        assertThat(snapshot.metrics().llmTotalTokens()).isEqualTo(165L);
        assertThat(snapshot.metrics().llmUsageCallCount()).isEqualTo(3L);
    }

    @Test
    void separatesNoUsageFromUsageAwaitingLabelsAndHonorsConfiguredMinimum() {
        MemoryRetrievalTraceService traceService = mock(MemoryRetrievalTraceService.class);
        MemoryFeedbackService feedbackService = mock(MemoryFeedbackService.class);
        MemoryTargetFeedbackService targetFeedbackService = mock(MemoryTargetFeedbackService.class);
        when(traceService.summarizeSince(any())).thenReturn(Map.of("usedMemoryTargetCount", 0L));
        when(feedbackService.summarizeSince(any())).thenReturn(Map.of());
        when(targetFeedbackService.summarizeUsageFeedbackSince(any())).thenReturn(Map.of());
        AdminMemoryObservabilityService service = service(traceService, feedbackService, targetFeedbackService, 2);

        assertThat(service.snapshot("7d").onlineLabels().sampleStatus()).isEqualTo("NO_MEMORY_USAGE");

        when(traceService.summarizeSince(any())).thenReturn(Map.of("usedMemoryTargetCount", 2L));
        assertThat(service.snapshot("7d").onlineLabels().sampleStatus()).isEqualTo("AWAITING_FEEDBACK");

        when(targetFeedbackService.summarizeUsageFeedbackSince(any())).thenReturn(Map.of(
                "labeledTargetCount", 2L, "helpfulCount", 2L));
        OnlineLabelMetrics sufficient = service.snapshot("7d").onlineLabels();
        assertThat(sufficient.sampleSufficient()).isTrue();
        assertThat(sufficient.sampleStatus()).isEqualTo("SAMPLE_SUFFICIENT");
        assertThat(sufficient.labelCoverageRate()).isEqualTo(1D);
    }

    private AdminMemoryObservabilityService service(MemoryRetrievalTraceService traces,
                                                    MemoryFeedbackService feedback,
                                                    MemoryTargetFeedbackService targetFeedback,
                                                    int minimum) {
        return new AdminMemoryObservabilityService(traces, feedback, targetFeedback,
                Clock.fixed(Instant.parse("2026-09-29T00:00:00Z"), ZoneOffset.UTC), minimum);
    }
}
