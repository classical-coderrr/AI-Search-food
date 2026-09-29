package com.example.food.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MemoryVectorIndexWorkerTest {
    private static final LocalDateTime INDEXED_AT = LocalDateTime.of(2026, 9, 29, 4, 0);

    @Test
    void indexesStoredVectorAndRecordsActualIndexTime() {
        Fixture fixture = new Fixture();
        MemoryVectorIndexJob job = fixture.upsertJob(2, 2);
        MemoryEmbedding row = fixture.vectorRow(2, 2);
        row.setUpdatedAt(INDEXED_AT.minusDays(2));
        when(fixture.embeddingMapper.findOwnedVector(17L, "MEMORY_ITEM", 9L, "test-model")).thenReturn(row);
        fixture.claimOnceThenEmpty(job);
        when(fixture.jobService.complete(job)).thenReturn(true);

        fixture.worker().processPending();

        ArgumentCaptor<MemoryVectorDocument> document = ArgumentCaptor.forClass(MemoryVectorDocument.class);
        verify(fixture.vectorIndex).upsert(document.capture());
        assertThat(document.getValue().userId()).isEqualTo(17L);
        assertThat(document.getValue().sourceKind()).isEqualTo("MEMORY_ITEM");
        assertThat(document.getValue().sourceId()).isEqualTo(9L);
        assertThat(document.getValue().embedding()).containsExactly(1.0f, 0.0f);
        verify(fixture.embeddingMapper).markAnnIndexed(17L, "MEMORY_ITEM", 9L, "test-model", 2, 2, INDEXED_AT);
        verify(fixture.jobService).complete(job);
    }

    @Test
    void missingCollectionResetsIndexMarkersBeforeRecreatingAndEnqueueing() {
        Fixture fixture = new Fixture();
        when(fixture.vectorIndex.collectionExists()).thenReturn(false);
        when(fixture.jobService.requestRebuild("test-model"))
                .thenReturn(new MemoryVectorIndexJobService.RebuildRequestResult(135, 100));
        when(fixture.jobService.claimNext()).thenReturn(null);

        fixture.worker().processPending();

        var order = inOrder(fixture.vectorIndex, fixture.jobService);
        order.verify(fixture.vectorIndex).collectionExists();
        order.verify(fixture.jobService).requestRebuild("test-model");
        order.verify(fixture.vectorIndex).ensureCollection();
        order.verify(fixture.jobService).enqueueMissingVectors("test-model", 100);
        order.verify(fixture.jobService).claimNext();
    }

    @Test
    void existingButUnderfilledCollectionRequestsRebuildWhenNoJobsAreOpen() {
        Fixture fixture = new Fixture();
        when(fixture.embeddingMapper.countIndexedVectorsByModel("test-model")).thenReturn(135);
        when(fixture.vectorIndex.countPointsByModel("test-model")).thenReturn(0L);
        when(fixture.jobService.requestRebuild("test-model"))
                .thenReturn(new MemoryVectorIndexJobService.RebuildRequestResult(135, 100));
        when(fixture.jobService.claimNext()).thenReturn(null);

        fixture.worker().processPending();

        verify(fixture.jobService).requestRebuild("test-model");
    }

    @Test
    void equalCountsStillRepairAnExpectedPointThatIsMissingByIdentity() {
        Fixture fixture = new Fixture();
        MemoryEmbedding row = fixture.vectorRow(2, 2);
        row.setId(45L);
        when(fixture.embeddingMapper.countIndexedVectorsByModel("test-model")).thenReturn(1);
        when(fixture.vectorIndex.countPointsByModel("test-model")).thenReturn(1L);
        when(fixture.vectorIndex.scrollModelPointPage("test-model", null, 500)).thenReturn(
                new QdrantMemoryVectorIndex.ModelPointPage(List.of(
                        new QdrantMemoryVectorIndex.ModelPointIdentity("wrong-id", 17L, "MEMORY_ITEM",
                                9L, 2, false)), null));
        when(fixture.embeddingMapper.listIndexedVectorsAfterId("test-model", 0, 500)).thenReturn(List.of(row));
        when(fixture.vectorIndex.findMissingPoints(List.of(row))).thenReturn(List.of(row));
        when(fixture.jobService.enqueueRepairUpsert(row)).thenReturn(1);
        when(fixture.jobService.claimNext()).thenReturn(null);

        fixture.worker().processPending();

        verify(fixture.vectorIndex).deletePointsById(List.of("wrong-id"));
        verify(fixture.vectorIndex).findMissingPoints(List.of(row));
        verify(fixture.jobService).enqueueRepairUpsert(row);
        verify(fixture.jobService, never()).requestRebuild("test-model");
    }

    @Test
    void skipsCollectionCountWhileUpsertsAreStillOpen() {
        Fixture fixture = new Fixture();
        when(fixture.jobService.hasOpenUpsertJobs("test-model")).thenReturn(true);
        when(fixture.jobService.claimNext()).thenReturn(null);

        fixture.worker().processPending();

        verify(fixture.vectorIndex, never()).countPointsByModel("test-model");
        verify(fixture.embeddingMapper, never()).countIndexedVectorsByModel("test-model");
        verify(fixture.embeddingMapper, never()).listIndexedVectorsAfterId("test-model", 0, 500);
    }

    @Test
    void transientOpenJobCheckFailureDoesNotReachVectorCount() {
        Fixture fixture = new Fixture();
        when(fixture.jobService.hasOpenUpsertJobs("test-model"))
                .thenThrow(new IllegalStateException("database unavailable"));
        when(fixture.jobService.claimNext()).thenReturn(null);

        fixture.worker().processPending();

        verify(fixture.vectorIndex, never()).countPointsByModel("test-model");
        verify(fixture.embeddingMapper, never()).countIndexedVectorsByModel("test-model");
        verify(fixture.embeddingMapper, never()).listIndexedVectorsAfterId("test-model", 0, 500);
    }

    @Test
    void obsoleteJobDoesNotOverwriteNewerVectorAndIsAcknowledged() {
        Fixture fixture = new Fixture();
        MemoryVectorIndexJob job = fixture.upsertJob(2, 2);
        MemoryEmbedding row = fixture.vectorRow(3, 2);
        when(fixture.embeddingMapper.findOwnedVector(17L, "MEMORY_ITEM", 9L, "test-model")).thenReturn(row);
        fixture.claimOnceThenEmpty(job);
        when(fixture.jobService.complete(job)).thenReturn(true);

        fixture.worker().processPending();

        verify(fixture.vectorIndex, never()).upsert(org.mockito.ArgumentMatchers.any());
        verify(fixture.embeddingMapper, never()).markAnnIndexed(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
        verify(fixture.jobService).complete(job);
    }

    @Test
    void malformedStoredVectorIsRetriedWithoutMarkingItIndexed() {
        Fixture fixture = new Fixture();
        MemoryVectorIndexJob job = fixture.upsertJob(2, 2);
        MemoryEmbedding row = fixture.vectorRow(2, 2);
        row.setEmbeddingJson("not-json");
        when(fixture.embeddingMapper.findOwnedVector(17L, "MEMORY_ITEM", 9L, "test-model")).thenReturn(row);
        fixture.claimOnceThenEmpty(job);
        when(fixture.jobService.retry(job, "IllegalStateException")).thenReturn(true);

        fixture.worker().processPending();

        verify(fixture.vectorIndex, never()).upsert(org.mockito.ArgumentMatchers.any());
        verify(fixture.embeddingMapper, never()).markAnnIndexed(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
        verify(fixture.jobService).retry(job, "IllegalStateException");
    }

    private static final class Fixture {
        private final MemoryVectorIndexJobService jobService = mock(MemoryVectorIndexJobService.class);
        private final MemoryEmbeddingMapper embeddingMapper = mock(MemoryEmbeddingMapper.class);
        private final QdrantMemoryVectorIndex vectorIndex = mock(QdrantMemoryVectorIndex.class);
        private final QdrantMemoryVectorProperties qdrantProperties = new QdrantMemoryVectorProperties();
        private final MemoryEmbeddingProperties embeddingProperties = new MemoryEmbeddingProperties();

        private Fixture() {
            qdrantProperties.setEnabled(true);
            embeddingProperties.setModel("test-model");
            embeddingProperties.setDimensions(2);
            when(vectorIndex.enabled()).thenReturn(true);
            when(vectorIndex.collectionExists()).thenReturn(true);
            when(vectorIndex.scrollModelPointPage("test-model", null, 500))
                    .thenReturn(new QdrantMemoryVectorIndex.ModelPointPage(List.of(), null));
            when(jobService.enqueueMissingVectors("test-model", 100)).thenReturn(0);
        }

        private MemoryVectorIndexWorker worker() {
            return new MemoryVectorIndexWorker(jobService, embeddingMapper, vectorIndex, qdrantProperties,
                    embeddingProperties, new ObjectMapper(), Clock.fixed(Instant.parse("2026-09-29T04:00:00Z"),
                    ZoneOffset.UTC));
        }

        private void claimOnceThenEmpty(MemoryVectorIndexJob job) {
            AtomicInteger calls = new AtomicInteger();
            when(jobService.claimNext()).thenAnswer(invocation -> calls.getAndIncrement() == 0 ? job : null);
        }

        private MemoryVectorIndexJob upsertJob(int version, int dimensions) {
            MemoryVectorIndexJob job = new MemoryVectorIndexJob();
            job.setId(31L);
            job.setUserId(17L);
            job.setSourceKind("MEMORY_ITEM");
            job.setSourceId(9L);
            job.setEmbeddingModel("test-model");
            job.setSourceVersion(version);
            job.setDimensions(dimensions);
            job.setOperation("UPSERT");
            job.setAttempts(1);
            job.setLeaseToken("lease-token");
            return job;
        }

        private MemoryEmbedding vectorRow(int version, int dimensions) {
            MemoryEmbedding row = new MemoryEmbedding();
            row.setUserId(17L);
            row.setSourceKind("MEMORY_ITEM");
            row.setSourceId(9L);
            row.setMemoryType("INGREDIENT_PREFERENCE");
            row.setSourceVersion(version);
            row.setEmbeddingModel("test-model");
            row.setDimensions(dimensions);
            row.setEmbeddingJson("[1.0,0.0]");
            return row;
        }
    }
}
