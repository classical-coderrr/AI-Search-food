package com.example.food.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MemoryProcessingJobWorkerTest {

    @Mock private MemoryProcessingJobService jobService;
    @Mock private MemoryCandidateService candidateService;
    @Mock private MemoryConsolidationService consolidationService;
    @Mock private MemoryPersonalizationService personalizationService;

    @Test
    void extractsAndConsolidatesEnabledUsersThenAcknowledgesTheJob() {
        MemoryProcessingJob job = job();
        org.mockito.Mockito.doReturn(job).doReturn(null).when(jobService).claimNext();
        when(personalizationService.isEnabled(7L)).thenReturn(true);
        when(jobService.complete(job)).thenReturn(true);

        worker().processPending();

        verify(jobService).recoverUnqueuedEpisodes(20);
        verify(candidateService).extractAndPersistAfterCommit(7L, 31L);
        verify(consolidationService).consolidateAfterCommit(7L);
        verify(jobService).complete(job);
    }

    @Test
    void acknowledgesWithoutLearningWhenPersonalizationWasTurnedOff() {
        MemoryProcessingJob job = job();
        org.mockito.Mockito.doReturn(job).doReturn(null).when(jobService).claimNext();
        when(personalizationService.isEnabled(7L)).thenReturn(false);
        when(jobService.complete(job)).thenReturn(true);

        worker().processPending();

        verify(candidateService, never()).extractAndPersistAfterCommit(7L, 31L);
        verify(consolidationService, never()).consolidateAfterCommit(7L);
        verify(jobService).complete(job);
    }

    private MemoryProcessingJobWorker worker() {
        return new MemoryProcessingJobWorker(jobService, candidateService, consolidationService,
                personalizationService, 2);
    }

    private MemoryProcessingJob job() {
        MemoryProcessingJob job = new MemoryProcessingJob();
        job.setId(9L);
        job.setUserId(7L);
        job.setEpisodeId(31L);
        job.setAttempts(1);
        job.setLeaseToken("lease-9");
        return job;
    }
}
