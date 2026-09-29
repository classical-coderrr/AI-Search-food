package com.example.food.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MemoryProcessingJobServiceTest {

    @Mock private MemoryProcessingJobMapper mapper;

    private final LocalDateTime now = LocalDateTime.of(2026, 9, 26, 10, 0);
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-26T02:00:00Z"), ZoneId.of("Asia/Shanghai"));

    @Test
    void enqueuesOnlyAnEpisodeOwnedByTheSameUserAndTreatsDuplicateAsIdempotent() {
        MemoryProcessingJobService service = service();
        when(mapper.episodeOwned(7L, 31L)).thenReturn(true);
        when(mapper.insertPending(eq(7L), eq(31L), any(LocalDateTime.class)))
                .thenThrow(new DuplicateKeyException("duplicate"));

        service.enqueue(7L, 31L);
        service.enqueue(8L, 31L);

        verify(mapper).episodeOwned(7L, 31L);
        verify(mapper).episodeOwned(8L, 31L);
        verify(mapper).insertPending(eq(7L), eq(31L), eq(now));
        verify(mapper, never()).insertPending(eq(8L), eq(31L), any(LocalDateTime.class));
    }

    @Test
    void recoversUnqueuedEpisodesWithTheirOriginalUserOwnership() {
        MemoryProcessingJobCandidate candidate = new MemoryProcessingJobCandidate(7L, 31L);
        when(mapper.findUnqueuedEpisodes(20)).thenReturn(List.of(candidate));
        when(mapper.episodeOwned(7L, 31L)).thenReturn(true);
        when(mapper.insertPending(7L, 31L, now)).thenReturn(1);

        int recovered = service().recoverUnqueuedEpisodes(20);

        assertThat(recovered).isEqualTo(1);
        verify(mapper).insertPending(7L, 31L, now);
    }

    @Test
    void claimsWithLeaseTokenAndIncrementsAttemptNumber() {
        MemoryProcessingJob candidate = job(12L, null, 0);
        MemoryProcessingJob claimed = job(12L, "lease", 1);
        when(mapper.findNextDue(now)).thenReturn(candidate);
        when(mapper.claim(eq(12L), any(String.class), eq(now), eq(now.plusMinutes(2)))).thenReturn(1);
        when(mapper.findClaimed(eq(12L), any(String.class))).thenReturn(claimed);

        MemoryProcessingJob result = service().claimNext();

        assertThat(result.getLeaseToken()).isNotBlank();
        assertThat(result.getAttempts()).isEqualTo(1);
        verify(mapper).findClaimed(eq(12L), eq(result.getLeaseToken()));
    }

    @Test
    void retriesWithCappedExponentialBackoffAndFailsAfterConfiguredAttempts() {
        MemoryProcessingJob job = job(12L, "lease-12", 5);
        ArgumentCaptor<LocalDateTime> dueAt = ArgumentCaptor.forClass(LocalDateTime.class);
        when(mapper.retryOrFail(eq(12L), eq("lease-12"), dueAt.capture(), eq(4), eq("暂时不可用")))
                .thenReturn(1);

        boolean changed = new MemoryProcessingJobService(
                mapper, clock, Duration.ofMinutes(2), Duration.ofSeconds(5), Duration.ofSeconds(30), 4
        ).retryOrFail(job, new IllegalStateException("暂时不可用"));

        assertThat(changed).isTrue();
        assertThat(dueAt.getValue()).isEqualTo(now.plusSeconds(30));
        verify(mapper).retryOrFail(12L, "lease-12", now.plusSeconds(30), 4, "暂时不可用");
    }

    private MemoryProcessingJobService service() {
        return new MemoryProcessingJobService(mapper, clock, Duration.ofMinutes(2),
                Duration.ofSeconds(5), Duration.ofSeconds(30), 8);
    }

    private MemoryProcessingJob job(Long id, String lease, int attempts) {
        MemoryProcessingJob job = new MemoryProcessingJob();
        job.setId(id);
        job.setUserId(7L);
        job.setEpisodeId(31L);
        job.setAttempts(attempts);
        job.setLeaseToken(lease);
        return job;
    }
}
