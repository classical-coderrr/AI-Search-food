package com.example.food.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class MemoryBehaviorEpisodeRecorderTest {

    @Mock
    private MemoryEpisodeService episodeService;

    @Test
    void convertsBusinessEventToTraceableEpisodeCommand() {
        MemoryBehaviorEpisodeRecorder recorder = new MemoryBehaviorEpisodeRecorder(episodeService, new ObjectMapper());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("recipeId", 42L);
        payload.put("reaction", "LIKE");

        recorder.record(new MemoryBehaviorEpisodeEvent(
                7L,
                9L,
                11L,
                "RECIPE_FEEDBACK",
                "RECOMMENDATION_FEEDBACK",
                "feedback-1",
                "feedback-event-1",
                "feedback-key-1",
                "用户喜欢番茄炒蛋",
                payload,
                LocalDateTime.of(2026, 9, 22, 10, 0),
                new BigDecimal("0.7500")
        ));

        ArgumentCaptor<MemoryEpisodeCommand> captor = ArgumentCaptor.forClass(MemoryEpisodeCommand.class);
        verify(episodeService).record(eq(7L), captor.capture());
        MemoryEpisodeCommand command = captor.getValue();
        assertThat(command.sessionId()).isEqualTo(9L);
        assertThat(command.sourceId()).isEqualTo("feedback-1");
        assertThat(command.idempotencyKey()).isEqualTo("feedback-key-1");
        assertThat(command.payloadJson()).contains("\"recipeId\":42");
        assertThat(command.payloadJson()).contains("\"reaction\":\"LIKE\"");
    }

    @Test
    void skipsAnonymousEventWithoutCallingEpisodeService() {
        MemoryBehaviorEpisodeRecorder recorder = new MemoryBehaviorEpisodeRecorder(episodeService, new ObjectMapper());

        recorder.record(new MemoryBehaviorEpisodeEvent(
                null, null, null, "RECIPE_SEARCH", "SEARCH_LOG", "1", "event-1", "key-1",
                "匿名搜索", Map.of(), null, null
        ));

        verify(episodeService, never()).record(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(MemoryEpisodeCommand.class));
    }
}
