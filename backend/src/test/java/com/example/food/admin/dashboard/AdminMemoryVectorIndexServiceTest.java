package com.example.food.admin.dashboard;

import com.example.food.admin.dashboard.dto.AdminMemoryVectorIndexRebuildResponse;
import com.example.food.admin.dashboard.dto.AdminMemoryVectorIndexStatusResponse;
import com.example.food.memory.MemoryEmbeddingMapper;
import com.example.food.memory.MemoryEmbeddingProperties;
import com.example.food.memory.MemoryVectorIndexJobMapper;
import com.example.food.memory.MemoryVectorIndexJobService;
import com.example.food.memory.QdrantMemoryVectorIndex;
import com.example.food.memory.QdrantMemoryVectorProperties;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminMemoryVectorIndexServiceTest {
    @Test
    void statusAggregatesModelScopedCountsWithoutExposingUsers() {
        MemoryEmbeddingMapper embeddingMapper = mock(MemoryEmbeddingMapper.class);
        MemoryVectorIndexJobMapper jobMapper = mock(MemoryVectorIndexJobMapper.class);
        MemoryVectorIndexJobService jobService = mock(MemoryVectorIndexJobService.class);
        QdrantMemoryVectorIndex vectorIndex = mock(QdrantMemoryVectorIndex.class);
        QdrantMemoryVectorProperties qdrantProperties = new QdrantMemoryVectorProperties();
        qdrantProperties.setEnabled(true);
        MemoryEmbeddingProperties embeddingProperties = new MemoryEmbeddingProperties();
        embeddingProperties.setModel("test-model");
        when(embeddingMapper.countVectorsByModel("test-model")).thenReturn(10);
        when(embeddingMapper.countIndexedVectorsByModel("test-model")).thenReturn(7);
        when(jobMapper.countUpsertJobsByStatus("test-model")).thenReturn(List.of(
                Map.of("STATUS", "PENDING", "JOB_COUNT", 2L),
                Map.of("status", "RETRY", "job_count", 1L),
                Map.of("status", "COMPLETE", "job_count", 9L)));
        when(vectorIndex.inspectCollection())
                .thenReturn(new QdrantMemoryVectorIndex.CollectionStatus("GREEN", true, 12, 0));
        AdminMemoryVectorIndexService service = new AdminMemoryVectorIndexService(embeddingMapper, jobMapper,
                jobService, vectorIndex, qdrantProperties, embeddingProperties,
                Clock.fixed(Instant.parse("2026-09-29T04:00:00Z"), ZoneOffset.UTC));

        AdminMemoryVectorIndexStatusResponse status = service.status();

        assertThat(status.databaseVectorCount()).isEqualTo(10);
        assertThat(status.indexedVectorCount()).isEqualTo(7);
        assertThat(status.unindexedVectorCount()).isEqualTo(3);
        assertThat(status.pendingJobCount()).isEqualTo(2);
        assertThat(status.retryingJobCount()).isEqualTo(1);
        assertThat(status.completedJobCount()).isEqualTo(9);
        assertThat(status.qdrantPointCount()).isEqualTo(12);
        assertThat(status.qdrantAvailable()).isTrue();
    }

    @Test
    void rebuildDelegatesToTheDurableIndexJobService() {
        MemoryEmbeddingMapper embeddingMapper = mock(MemoryEmbeddingMapper.class);
        MemoryVectorIndexJobMapper jobMapper = mock(MemoryVectorIndexJobMapper.class);
        MemoryVectorIndexJobService jobService = mock(MemoryVectorIndexJobService.class);
        QdrantMemoryVectorIndex vectorIndex = mock(QdrantMemoryVectorIndex.class);
        QdrantMemoryVectorProperties qdrantProperties = new QdrantMemoryVectorProperties();
        qdrantProperties.setEnabled(true);
        MemoryEmbeddingProperties embeddingProperties = new MemoryEmbeddingProperties();
        embeddingProperties.setModel("test-model");
        when(jobService.requestRebuild("test-model"))
                .thenReturn(new MemoryVectorIndexJobService.RebuildRequestResult(4, 3));
        AdminMemoryVectorIndexService service = new AdminMemoryVectorIndexService(embeddingMapper, jobMapper,
                jobService, vectorIndex, qdrantProperties, embeddingProperties,
                Clock.fixed(Instant.parse("2026-09-29T04:00:00Z"), ZoneOffset.UTC));

        AdminMemoryVectorIndexRebuildResponse result = service.requestRebuild();

        assertThat(result.embeddingModel()).isEqualTo("test-model");
        assertThat(result.vectorRowsReset()).isEqualTo(4);
        assertThat(result.upsertJobsQueued()).isEqualTo(3);
        verify(jobService).requestRebuild("test-model");
    }
}
