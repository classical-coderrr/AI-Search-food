package com.example.food.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MemoryFeedbackServiceTest {

    @Mock
    private MemoryFeedbackMapper feedbackMapper;

    @Mock
    private MemoryRetrievalTraceMapper traceMapper;

    @Test
    void persistsFeedbackOnlyForOwnedTraceWithInjectedMemoryAndUsesTraceAsIdempotencyKey() {
        when(traceMapper.findOwnedByTraceId(7L, "run-1")).thenReturn(trace("[41]", "[]", "[]"));
        when(feedbackMapper.findByTraceId("run-1")).thenReturn(null);
        doAnswer(invocation -> {
            MemoryFeedback feedback = invocation.getArgument(0);
            feedback.setId(81L);
            return 1;
        }).when(feedbackMapper).insert(any(MemoryFeedback.class));
        MemoryFeedbackService service = service();

        MemoryFeedbackResponse result = service.submit(7L,
                new MemoryFeedbackRequest(" run-1 ", MemoryFeedbackType.HELPFUL));

        assertThat(result.id()).isEqualTo(81L);
        assertThat(result.traceId()).isEqualTo("run-1");
        assertThat(result.feedbackType()).isEqualTo(MemoryFeedbackType.HELPFUL);
        assertThat(result.updated()).isFalse();
        verify(traceMapper).findOwnedByTraceId(7L, "run-1");
        verify(feedbackMapper).insert(any(MemoryFeedback.class));
    }

    @Test
    void repeatingTheSameFeedbackDoesNotCreateOrUpdateAnotherRow() {
        MemoryFeedback existing = feedback(81L, 7L, "run-1", MemoryFeedbackType.HELPFUL);
        when(traceMapper.findOwnedByTraceId(7L, "run-1")).thenReturn(trace("[41]", "[]", "[]"));
        when(feedbackMapper.findByTraceId("run-1")).thenReturn(existing);

        MemoryFeedbackResponse result = service().submit(7L,
                new MemoryFeedbackRequest("run-1", MemoryFeedbackType.HELPFUL));

        assertThat(result.id()).isEqualTo(81L);
        assertThat(result.updated()).isFalse();
        verify(feedbackMapper, never()).insert(any(MemoryFeedback.class));
        verify(feedbackMapper, never()).updateOwned(any(), any(), any(), any());
    }

    @Test
    void allowsUserToCorrectFeedbackForTheSameTrace() {
        MemoryFeedback existing = feedback(81L, 7L, "run-1", MemoryFeedbackType.HELPFUL);
        when(traceMapper.findOwnedByTraceId(7L, "run-1")).thenReturn(trace("[]", "[52]", "[]"));
        when(feedbackMapper.findByTraceId("run-1")).thenReturn(existing);
        when(feedbackMapper.updateOwned(81L, 7L, "OUTDATED", now())).thenReturn(1);

        MemoryFeedbackResponse result = service().submit(7L,
                new MemoryFeedbackRequest("run-1", MemoryFeedbackType.OUTDATED));

        assertThat(result.updated()).isTrue();
        assertThat(result.feedbackType()).isEqualTo(MemoryFeedbackType.OUTDATED);
        verify(feedbackMapper).updateOwned(81L, 7L, "OUTDATED", now());
    }

    @Test
    void rejectsTraceOwnedByAnotherUserWithoutLookingUpFeedback() {
        when(traceMapper.findOwnedByTraceId(7L, "other-run")).thenReturn(null);

        assertThatThrownBy(() -> service().submit(7L,
                new MemoryFeedbackRequest("other-run", MemoryFeedbackType.INCORRECT)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("记忆追踪记录不存在");
        verify(feedbackMapper, never()).findByTraceId(any());
    }

    @Test
    void rejectsRunWithoutPersonalMemoryInTheModelContext() {
        when(traceMapper.findOwnedByTraceId(7L, "empty-run")).thenReturn(trace("[]", "[]", "[]"));

        assertThatThrownBy(() -> service().submit(7L,
                new MemoryFeedbackRequest("empty-run", MemoryFeedbackType.NOT_RELEVANT)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("未注入个人记忆");
        verify(feedbackMapper, never()).findByTraceId(any());
    }

    @Test
    void allowsFeedbackWhenOnlyStructuredProfileWasInjected() {
        when(traceMapper.findOwnedByTraceId(7L, "profile-run"))
                .thenReturn(trace("[]", "[]", "[\"STRUCTURED_PROFILE\"]"));
        when(feedbackMapper.findByTraceId("profile-run")).thenReturn(null);
        doAnswer(invocation -> {
            MemoryFeedback feedback = invocation.getArgument(0);
            feedback.setId(82L);
            return 1;
        }).when(feedbackMapper).insert(any(MemoryFeedback.class));

        MemoryFeedbackResponse result = service().submit(7L,
                new MemoryFeedbackRequest("profile-run", MemoryFeedbackType.HELPFUL));

        assertThat(result.id()).isEqualTo(82L);
    }

    @Test
    void statusReturnsEligibilityAndExistingFeedbackOnlyForOwnedTrace() {
        when(traceMapper.findOwnedByTraceId(7L, "run-1")).thenReturn(trace("[41]", "[]", "[]"));
        when(feedbackMapper.findByTraceId("run-1"))
                .thenReturn(feedback(81L, 7L, "run-1", MemoryFeedbackType.HELPFUL));

        MemoryFeedbackStatusResponse status = service().status(7L, " run-1 ");

        assertThat(status.eligible()).isTrue();
        assertThat(status.feedbackType()).isEqualTo(MemoryFeedbackType.HELPFUL);
        assertThat(status.updatedAt()).isEqualTo(now());
    }

    @Test
    void statusHidesFeedbackForTraceWithoutPersonalContext() {
        when(traceMapper.findOwnedByTraceId(7L, "run-1")).thenReturn(trace("[]", "[]", "[]"));

        MemoryFeedbackStatusResponse status = service().status(7L, "run-1");

        assertThat(status.eligible()).isFalse();
        assertThat(status.feedbackType()).isNull();
        verify(feedbackMapper, never()).findByTraceId(any());
    }

    private MemoryFeedbackService service() {
        return new MemoryFeedbackService(feedbackMapper, traceMapper, new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-09-25T10:00:00Z"), ZoneOffset.UTC));
    }

    private MemoryRetrievalTrace trace(String itemIds, String episodeIds, String sections) {
        MemoryRetrievalTrace trace = new MemoryRetrievalTrace();
        trace.setTraceId("run-1");
        trace.setUserId(7L);
        trace.setUsedMemoryItemIdsJson(itemIds);
        trace.setUsedEpisodeIdsJson(episodeIds);
        trace.setContextSectionsJson(sections);
        return trace;
    }

    private MemoryFeedback feedback(Long id, Long userId, String traceId, MemoryFeedbackType type) {
        MemoryFeedback feedback = new MemoryFeedback();
        feedback.setId(id);
        feedback.setUserId(userId);
        feedback.setTraceId(traceId);
        feedback.setFeedbackType(type.name());
        feedback.setCreatedAt(now());
        feedback.setUpdatedAt(now());
        return feedback;
    }

    private java.time.LocalDateTime now() {
        return java.time.LocalDateTime.ofInstant(Instant.parse("2026-09-25T10:00:00Z"), ZoneOffset.UTC);
    }
}
