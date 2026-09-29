package com.example.food.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MemoryVectorStoreAdapterTest {

    @Test
    void returnsExactCosineOrderAndAlwaysScopesTheVectorReadToOneUser() {
        MemoryEmbeddingMapper mapper = mock(MemoryEmbeddingMapper.class);
        MemoryEmbeddingIndexJobMapper jobMapper = mock(MemoryEmbeddingIndexJobMapper.class);
        MemoryEmbeddingProperties properties = new MemoryEmbeddingProperties();
        MySqlMemoryVectorStoreAdapter store = new MySqlMemoryVectorStoreAdapter(mapper, jobMapper,
                new ObjectMapper(), properties);
        when(mapper.listOwnedVectors(17L, "test-model", properties.scanLimit()))
                .thenReturn(List.of(row("MEMORY_ITEM", 9L, "[0.8,0.6]", 2),
                        row("EPISODE", 8L, "[0.0,1.0]", 2), row("EPISODE", 7L, "malformed", 2)));

        List<MemoryVectorMatch> matches = store.search(17L, "test-model", new float[]{1.0f, 0.0f}, 5);

        assertThat(matches).extracting(MemoryVectorMatch::sourceId).containsExactly(9L, 8L);
        assertThat(matches.get(0).similarity()).isCloseTo(0.8, org.assertj.core.data.Offset.offset(0.0001));
        verify(mapper).listOwnedVectors(17L, "test-model", properties.scanLimit());
    }

    @Test
    void enqueuesAnnUpsertWhenAnExistingVectorIsUpdated() {
        MemoryEmbeddingMapper mapper = mock(MemoryEmbeddingMapper.class);
        MemoryEmbeddingIndexJobMapper jobMapper = mock(MemoryEmbeddingIndexJobMapper.class);
        MemoryVectorIndexJobService indexJobService = mock(MemoryVectorIndexJobService.class);
        QdrantMemoryVectorIndex qdrant = mock(QdrantMemoryVectorIndex.class);
        MemoryVectorDocument document = new MemoryVectorDocument(17L, "MEMORY_ITEM", 9L,
                "INGREDIENT_PREFERENCE", 2, "test-model", new float[]{1.0f, 0.0f});
        when(mapper.updateOwnedVector(any(MemoryEmbedding.class))).thenReturn(1);
        MySqlMemoryVectorStoreAdapter store = new MySqlMemoryVectorStoreAdapter(mapper, jobMapper,
                new ObjectMapper(), new MemoryEmbeddingProperties(), indexJobService, qdrant);

        store.upsert(document);

        verify(mapper).updateOwnedVector(any(MemoryEmbedding.class));
        verify(mapper, never()).insertVector(any(MemoryEmbedding.class));
        verify(indexJobService).enqueueUpsert(document);
    }

    @Test
    void usesAnnResultsOnlyWhenTheUsersIndexIsReady() {
        MemoryEmbeddingMapper mapper = mock(MemoryEmbeddingMapper.class);
        MemoryEmbeddingIndexJobMapper jobMapper = mock(MemoryEmbeddingIndexJobMapper.class);
        MemoryVectorIndexJobService indexJobService = mock(MemoryVectorIndexJobService.class);
        QdrantMemoryVectorIndex qdrant = mock(QdrantMemoryVectorIndex.class);
        MySqlMemoryVectorStoreAdapter store = new MySqlMemoryVectorStoreAdapter(mapper, jobMapper,
                new ObjectMapper(), new MemoryEmbeddingProperties(), indexJobService, qdrant);
        List<MemoryVectorMatch> annMatches = List.of(new MemoryVectorMatch("EPISODE", 42L, 0.91));
        when(qdrant.enabled()).thenReturn(true);
        when(indexJobService.needsMySqlFallback(17L, "test-model")).thenReturn(false);
        when(qdrant.search(17L, "test-model", new float[]{1.0f, 0.0f}, 3)).thenReturn(annMatches);

        assertThat(store.search(17L, "test-model", new float[]{1.0f, 0.0f}, 3)).isEqualTo(annMatches);

        verify(mapper, never()).listOwnedVectors(anyLong(), any(), anyInt());
    }

    @Test
    void fallsBackToMySqlWhenAnnIsPendingOrUnavailable() {
        MemoryEmbeddingMapper mapper = mock(MemoryEmbeddingMapper.class);
        MemoryEmbeddingIndexJobMapper jobMapper = mock(MemoryEmbeddingIndexJobMapper.class);
        MemoryVectorIndexJobService indexJobService = mock(MemoryVectorIndexJobService.class);
        QdrantMemoryVectorIndex qdrant = mock(QdrantMemoryVectorIndex.class);
        MemoryEmbeddingProperties properties = new MemoryEmbeddingProperties();
        MySqlMemoryVectorStoreAdapter store = new MySqlMemoryVectorStoreAdapter(mapper, jobMapper,
                new ObjectMapper(), properties, indexJobService, qdrant);
        when(qdrant.enabled()).thenReturn(true);
        when(indexJobService.needsMySqlFallback(17L, "test-model")).thenReturn(true, false);
        when(qdrant.search(17L, "test-model", new float[]{1.0f, 0.0f}, 3))
                .thenThrow(new IllegalStateException("qdrant offline"))
                .thenThrow(new IllegalStateException("qdrant offline"));
        when(mapper.listOwnedVectors(17L, "test-model", properties.scanLimit()))
                .thenReturn(List.of(row("MEMORY_ITEM", 9L, "[1.0,0.0]", 2)));

        assertThat(store.search(17L, "test-model", new float[]{1.0f, 0.0f}, 3))
                .extracting(MemoryVectorMatch::sourceId).containsExactly(9L);
        assertThat(store.search(17L, "test-model", new float[]{1.0f, 0.0f}, 3))
                .extracting(MemoryVectorMatch::sourceId).containsExactly(9L);

        verify(qdrant).search(17L, "test-model", new float[]{1.0f, 0.0f}, 3);
        verify(mapper, org.mockito.Mockito.times(2))
                .listOwnedVectors(17L, "test-model", properties.scanLimit());
    }

    private MemoryEmbedding row(String kind, Long id, String vector, int dimensions) {
        MemoryEmbedding row = new MemoryEmbedding();
        row.setSourceKind(kind);
        row.setSourceId(id);
        row.setEmbeddingJson(vector);
        row.setDimensions(dimensions);
        return row;
    }
}
