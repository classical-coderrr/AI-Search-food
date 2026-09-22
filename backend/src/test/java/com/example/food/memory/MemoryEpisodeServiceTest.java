package com.example.food.memory;

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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MemoryEpisodeServiceTest {

    @Mock
    private MemoryEpisodeMapper mapper;

    @Test
    void returnsExistingEpisodeWhenAnEventIsRetried() {
        MemoryEpisode existing = episode(31L, 7L);
        when(mapper.findOwnedByIdempotencyKey(7L, "recipe-feedback-1")).thenReturn(existing);

        MemoryEpisodeService service = new MemoryEpisodeService(mapper);
        MemoryEpisodeService.RecordResult result = service.record(7L, command("recipe-feedback-1"));

        assertThat(result.duplicate()).isTrue();
        assertThat(result.episode().getId()).isEqualTo(31L);
        verify(mapper, never()).insert(any(MemoryEpisode.class));
    }

    @Test
    void requiresOriginalPayloadForTraceability() {
        MemoryEpisodeService service = new MemoryEpisodeService(mapper);
        MemoryEpisodeCommand invalid = new MemoryEpisodeCommand(
                null, null, "RECIPE_EXPERIENCE", "RECIPE_FEEDBACK", "31", null,
                "event-1", "评分", "", null, null
        );

        assertThatThrownBy(() -> service.record(7L, invalid))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("原始载荷");
    }

    @Test
    void clampsListLimitAndKeepsUserIdInMapperCall() {
        when(mapper.listOwned(7L, null, null, 100)).thenReturn(java.util.List.of());
        MemoryEpisodeService service = new MemoryEpisodeService(mapper);

        assertThat(service.listOwned(7L, null, null, 1000)).isEmpty();
        verify(mapper).listOwned(7L, null, null, 100);
    }

    private MemoryEpisodeCommand command(String idempotencyKey) {
        return new MemoryEpisodeCommand(
                12L,
                null,
                "RECIPE_EXPERIENCE",
                "RECIPE_FEEDBACK",
                "31",
                "event-1",
                " " + idempotencyKey + " ",
                "用户给菜谱评分",
                "{\"rating\":5}",
                null,
                null
        );
    }

    private MemoryEpisode episode(Long id, Long userId) {
        MemoryEpisode episode = new MemoryEpisode();
        episode.setId(id);
        episode.setUserId(userId);
        episode.setIdempotencyKey("recipe-feedback-1");
        episode.setStatus(MemoryEpisodeStatus.RAW.name());
        episode.setVersion(0);
        return episode;
    }
}
