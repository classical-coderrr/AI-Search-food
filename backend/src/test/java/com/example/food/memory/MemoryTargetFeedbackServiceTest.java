package com.example.food.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MemoryTargetFeedbackServiceTest {
    @Mock private MemoryTargetFeedbackMapper feedbackMapper;
    @Mock private MemoryRetrievalTraceMapper traceMapper;
    @Mock private MemoryItemMapper itemMapper;
    @Mock private MemoryEpisodeMapper episodeMapper;

    @Test
    void acceptsFeedbackOnlyForOwnedMemoryActuallyUsedInTheTrace() {
        MemoryRetrievalTrace trace = trace("[41]", "[]");
        MemoryItem item = item(41L, 7L);
        when(traceMapper.findOwnedByTraceId(7L, "run-1")).thenReturn(trace);
        when(itemMapper.findActiveOwned(7L, 41L)).thenReturn(item);
        when(feedbackMapper.findMemoryItemForUpdate(7L, "run-1", 41L)).thenReturn(null);
        doAnswer(invocation -> {
            MemoryTargetFeedback feedback = invocation.getArgument(0);
            feedback.setId(81L);
            return 1;
        }).when(feedbackMapper).insert(any(MemoryTargetFeedback.class));

        MemoryTargetFeedbackResponse response = service().submit(7L, request(41L, MemoryFeedbackType.HELPFUL));

        assertThat(response.id()).isEqualTo(81L);
        assertThat(response.sourceKind()).isEqualTo(MemoryTargetFeedbackSource.MEMORY_ITEM);
        assertThat(response.feedbackType()).isEqualTo(MemoryFeedbackType.HELPFUL);
        assertThat(response.updated()).isFalse();
        verify(feedbackMapper).insert(any(MemoryTargetFeedback.class));
    }

    @Test
    void rejectsFeedbackForAnItemThatWasNotUsedByThatAnswer() {
        when(traceMapper.findOwnedByTraceId(7L, "run-1")).thenReturn(trace("[]", "[]"));

        assertThatThrownBy(() -> service().submit(7L, request(41L, MemoryFeedbackType.INCORRECT)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("没有使用这条记忆");
        verify(itemMapper, never()).findActiveOwned(any(), any());
        verify(feedbackMapper, never()).insert(any(MemoryTargetFeedback.class));
    }

    @Test
    void rejectsTargetsBelongingToAnotherUser() {
        when(traceMapper.findOwnedByTraceId(7L, "run-1")).thenReturn(trace("[41]", "[]"));
        when(itemMapper.findActiveOwned(7L, 41L)).thenReturn(null);

        assertThatThrownBy(() -> service().submit(7L, request(41L, MemoryFeedbackType.OUTDATED)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("记忆记录不存在");
        verify(feedbackMapper, never()).insert(any(MemoryTargetFeedback.class));
    }

    @Test
    void allowsCorrectionOfAnExistingPerTargetFeedback() {
        MemoryTargetFeedback existing = feedback(82L, 7L, "run-1", 41L, MemoryFeedbackType.HELPFUL);
        when(traceMapper.findOwnedByTraceId(7L, "run-1")).thenReturn(trace("[41]", "[]"));
        when(itemMapper.findActiveOwned(7L, 41L)).thenReturn(item(41L, 7L));
        when(feedbackMapper.findMemoryItemForUpdate(7L, "run-1", 41L)).thenReturn(existing);
        when(feedbackMapper.updateOwned(82L, 7L, "INCORRECT", now())).thenReturn(1);

        MemoryTargetFeedbackResponse response = service().submit(7L, request(41L, MemoryFeedbackType.INCORRECT));

        assertThat(response.updated()).isTrue();
        assertThat(response.feedbackType()).isEqualTo(MemoryFeedbackType.INCORRECT);
        verify(feedbackMapper, never()).insert(any(MemoryTargetFeedback.class));
    }

    @Test
    void targetListingReturnsOnlyTraceTargetsWithOwnedReadableLabels() {
        when(traceMapper.findOwnedByTraceId(7L, "run-1")).thenReturn(trace("[41]", "[52]"));
        when(itemMapper.findActiveOwnedByIds(7L, List.of(41L))).thenReturn(List.of(item(41L, 7L)));
        MemoryEpisode episode = new MemoryEpisode();
        episode.setId(52L);
        episode.setUserId(7L);
        episode.setEpisodeType("RECIPE_SAVED");
        episode.setSummary("收藏了香煎鸡胸肉");
        when(episodeMapper.findOwnedByIds(7L, List.of(52L))).thenReturn(List.of(episode));
        when(feedbackMapper.findOwnedByTrace(7L, "run-1"))
                .thenReturn(List.of(feedback(83L, 7L, "run-1", 41L, MemoryFeedbackType.OUTDATED)));

        List<MemoryTargetFeedbackTargetResponse> targets = service().targets(7L, "run-1");

        assertThat(targets).containsExactly(
                new MemoryTargetFeedbackTargetResponse(MemoryTargetFeedbackSource.MEMORY_ITEM, 41L,
                        "鸡胸肉", "LIKE · INGREDIENT_PREFERENCE", MemoryFeedbackType.OUTDATED),
                new MemoryTargetFeedbackTargetResponse(MemoryTargetFeedbackSource.EPISODE, 52L,
                        "收藏了香煎鸡胸肉", "RECIPE_SAVED", null));
    }

    @Test
    void recentSignalsAreScopedToTheUserIntentAndCandidateIds() {
        MemoryTargetFeedback signal = feedback(84L, 7L, "run-1", 41L, MemoryFeedbackType.INCORRECT);
        when(feedbackMapper.findRecentMemoryItemSignals(7L, "POST_WORKOUT", List.of(41L), now().minusDays(60)))
                .thenReturn(List.of(signal));

        var signals = service().recentSignals(7L, "POST_WORKOUT",
                List.of(new MemorySearchHit("MEMORY_ITEM", 41L, "PREFERENCE", "鸡胸肉", "", null,
                        "LIKE", "LONG_TERM", null, null, now(), null)), now().minusDays(60));

        assertThat(signals).containsEntry(new MemoryVectorKey("MEMORY_ITEM", 41L), MemoryFeedbackType.INCORRECT);
        verify(feedbackMapper, never()).findRecentEpisodeSignals(any(), any(), any(), any());
    }

    private MemoryTargetFeedbackService service() {
        return new MemoryTargetFeedbackService(feedbackMapper, traceMapper, itemMapper, episodeMapper,
                new ObjectMapper(), Clock.fixed(Instant.parse("2026-09-25T10:00:00Z"), ZoneOffset.UTC));
    }

    private MemoryTargetFeedbackRequest request(Long sourceId, MemoryFeedbackType type) {
        return new MemoryTargetFeedbackRequest("run-1", MemoryTargetFeedbackSource.MEMORY_ITEM, sourceId, type);
    }

    private MemoryRetrievalTrace trace(String itemIds, String episodeIds) {
        MemoryRetrievalTrace trace = new MemoryRetrievalTrace();
        trace.setTraceId("run-1");
        trace.setUserId(7L);
        trace.setIntent("POST_WORKOUT");
        trace.setUsedMemoryItemIdsJson(itemIds);
        trace.setUsedEpisodeIdsJson(episodeIds);
        return trace;
    }

    private MemoryItem item(Long id, Long userId) {
        MemoryItem item = new MemoryItem();
        item.setId(id);
        item.setUserId(userId);
        item.setCanonicalEntity("鸡胸肉");
        item.setPreference("LIKE");
        item.setMemoryType("INGREDIENT_PREFERENCE");
        return item;
    }

    private MemoryTargetFeedback feedback(Long id, Long userId, String traceId,
                                          Long itemId, MemoryFeedbackType type) {
        MemoryTargetFeedback feedback = new MemoryTargetFeedback();
        feedback.setId(id);
        feedback.setUserId(userId);
        feedback.setTraceId(traceId);
        feedback.setIntent("POST_WORKOUT");
        feedback.setSourceKind(MemoryTargetFeedbackSource.MEMORY_ITEM.name());
        feedback.setMemoryItemId(itemId);
        feedback.setFeedbackType(type.name());
        feedback.setCreatedAt(now());
        feedback.setUpdatedAt(now());
        return feedback;
    }

    private LocalDateTime now() {
        return LocalDateTime.of(2026, 9, 25, 10, 0);
    }
}
