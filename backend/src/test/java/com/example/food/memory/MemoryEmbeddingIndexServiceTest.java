package com.example.food.memory;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

class MemoryEmbeddingIndexServiceTest {

    @Test
    void appliesConfiguredUserScopeToPendingCandidateQuery() {
        MemoryEmbeddingMapper mapper = mock(MemoryEmbeddingMapper.class);
        MemoryEmbeddingClient client = mock(MemoryEmbeddingClient.class);
        MemoryVectorStoreAdapter vectorStore = mock(MemoryVectorStoreAdapter.class);
        MemoryEmbeddingProperties properties = new MemoryEmbeddingProperties();
        properties.setIndexUserId(9L);
        MemoryPersonalizationService personalization = mock(MemoryPersonalizationService.class);
        MemoryEmbeddingIndexJobService jobService = mock(MemoryEmbeddingIndexJobService.class);
        when(client.isAvailable()).thenReturn(true);
        when(client.model()).thenReturn("test-model");
        when(mapper.findUnindexedCandidates(eq("test-model"), eq(1024), eq(24), eq(9L), any(LocalDateTime.class)))
                .thenReturn(List.of());
        MemoryEmbeddingIndexService service = new MemoryEmbeddingIndexService(mapper, client, vectorStore,
                properties, personalization, jobService);

        service.indexPending();

        verify(mapper).findUnindexedCandidates(eq("test-model"), eq(1024), eq(24), eq(9L),
                any(LocalDateTime.class));
    }

    @Test
    void batchesIndexingPerUserAndDoesNotSendDisabledUsersMemoryToProvider() {
        MemoryEmbeddingMapper mapper = mock(MemoryEmbeddingMapper.class);
        MemoryEmbeddingClient client = mock(MemoryEmbeddingClient.class);
        MemoryVectorStoreAdapter vectorStore = mock(MemoryVectorStoreAdapter.class);
        MemoryEmbeddingProperties properties = new MemoryEmbeddingProperties();
        properties.setDimensions(2);
        properties.setBatchSize(10);
        MemoryPersonalizationService personalization = mock(MemoryPersonalizationService.class);
        MemoryEmbeddingIndexJobService jobService = mock(MemoryEmbeddingIndexJobService.class);
        when(client.isAvailable()).thenReturn(true);
        when(client.model()).thenReturn("test-model");
        when(mapper.findUnindexedCandidates(eq("test-model"), eq(2), eq(24), eq(null), any(LocalDateTime.class)))
                .thenReturn(List.of(
                candidate(1L, 11L), candidate(2L, 22L), candidate(1L, 12L)));
        when(personalization.isEnabled(1L)).thenReturn(true);
        when(personalization.isEnabled(2L)).thenReturn(false);
        when(client.embed(anyList())).thenAnswer(invocation -> {
            List<String> texts = invocation.getArgument(0);
            return texts.stream().map(ignored -> new float[]{1.0f, 0.0f}).toList();
        });
        when(jobService.claim(any(MemoryEmbeddingCandidate.class), eq("test-model"), eq(2)))
                .thenAnswer(invocation -> claimedJob((MemoryEmbeddingCandidate) invocation.getArgument(0)));
        when(jobService.complete(any(MemoryEmbeddingIndexJob.class))).thenReturn(true);
        MemoryEmbeddingIndexService service = new MemoryEmbeddingIndexService(mapper, client, vectorStore,
                properties, personalization, jobService);

        service.indexPending();

        verify(client).embed(List.of("episode 11", "episode 12"));
        verify(client, never()).embed(List.of("episode 22"));
        verify(vectorStore).upsert(org.mockito.ArgumentMatchers.argThat(document -> document.userId().equals(1L)
                && document.sourceId().equals(11L)));
        verify(vectorStore).upsert(org.mockito.ArgumentMatchers.argThat(document -> document.userId().equals(1L)
                && document.sourceId().equals(12L)));
        verify(vectorStore, never()).upsert(org.mockito.ArgumentMatchers.argThat(document -> document.userId().equals(2L)));
    }

    @Test
    void persistsRetryWhenEmbeddingProviderReturnsNoVectors() {
        MemoryEmbeddingMapper mapper = mock(MemoryEmbeddingMapper.class);
        MemoryEmbeddingClient client = mock(MemoryEmbeddingClient.class);
        MemoryVectorStoreAdapter vectorStore = mock(MemoryVectorStoreAdapter.class);
        MemoryEmbeddingProperties properties = new MemoryEmbeddingProperties();
        properties.setDimensions(2);
        MemoryPersonalizationService personalization = mock(MemoryPersonalizationService.class);
        MemoryEmbeddingIndexJobService jobService = mock(MemoryEmbeddingIndexJobService.class);
        MemoryEmbeddingCandidate candidate = candidate(1L, 31L);
        MemoryEmbeddingIndexJob job = claimedJob(candidate);
        when(client.isAvailable()).thenReturn(true);
        when(client.model()).thenReturn("test-model");
        when(mapper.findUnindexedCandidates(eq("test-model"), eq(2), eq(24), eq(null), any(LocalDateTime.class)))
                .thenReturn(List.of(candidate));
        when(personalization.isEnabled(1L)).thenReturn(true);
        when(jobService.claim(candidate, "test-model", 2)).thenReturn(job);
        when(client.embed(List.of("episode 31"))).thenReturn(List.of());

        new MemoryEmbeddingIndexService(mapper, client, vectorStore, properties, personalization, jobService)
                .indexPending();

        verify(jobService).retry(job, "EMBEDDING_RESPONSE_EMPTY_OR_MISMATCHED");
        verifyNoInteractions(vectorStore);
    }

    private MemoryEmbeddingCandidate candidate(Long userId, Long sourceId) {
        MemoryEmbeddingCandidate candidate = new MemoryEmbeddingCandidate();
        candidate.setUserId(userId);
        candidate.setSourceKind("EPISODE");
        candidate.setSourceId(sourceId);
        candidate.setSourceVersion(0);
        candidate.setMemoryType("RECIPE_SAVED");
        candidate.setContent("episode " + sourceId);
        return candidate;
    }

    private MemoryEmbeddingIndexJob claimedJob(MemoryEmbeddingCandidate candidate) {
        MemoryEmbeddingIndexJob job = new MemoryEmbeddingIndexJob();
        job.setId(candidate.getSourceId());
        job.setUserId(candidate.getUserId());
        job.setSourceKind(candidate.getSourceKind());
        job.setSourceId(candidate.getSourceId());
        job.setSourceVersion(candidate.getSourceVersion());
        job.setEmbeddingModel("test-model");
        job.setDimensions(2);
        job.setStatus("PROCESSING");
        job.setAttempts(1);
        job.setLeaseToken("lease-" + candidate.getSourceId());
        return job;
    }
}
