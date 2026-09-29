package com.example.food.memory;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = "app.memory.vector.qdrant.enabled=true")
@ActiveProfiles("test")
@Transactional
class MemoryVectorIndexJobIntegrationTest {
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private MemoryVectorStoreAdapter vectorStore;
    @Autowired private MemoryVectorIndexJobService jobService;
    @Autowired private MemoryVectorIndexJobMapper jobMapper;
    @Autowired private MemoryEmbeddingMapper embeddingMapper;

    @MockBean private QdrantMemoryVectorIndex qdrant;

    @Test
    void storesVectorAndDurableUpsertAndDeleteJobsWithoutBreakingMySqlFallback() {
        when(qdrant.enabled()).thenReturn(false);
        Long userId = insertUser("13900000996");
        MemoryVectorDocument document = new MemoryVectorDocument(userId, "MEMORY_ITEM", 77L,
                "INGREDIENT_PREFERENCE", 1, "test-model", new float[]{1.0f, 0.0f});

        vectorStore.upsert(document);

        assertThat(vectorStore.search(userId, "test-model", new float[]{1.0f, 0.0f}, 10))
                .extracting(MemoryVectorMatch::sourceId).containsExactly(77L);
        Map<String, Object> vector = jdbcTemplate.queryForMap("""
                SELECT ann_indexed_at, source_version, dimensions
                FROM memory_embeddings
                WHERE user_id = ? AND source_kind = 'MEMORY_ITEM' AND source_id = 77 AND embedding_model = 'test-model'
                """, userId);
        assertThat(vector.get("ann_indexed_at")).isNull();
        assertThat(((Number) vector.get("source_version")).intValue()).isEqualTo(1);
        assertThat(((Number) vector.get("dimensions")).intValue()).isEqualTo(2);
        assertThat(jobOperations(userId)).containsExactly("UPSERT");

        vectorStore.deleteAll(userId);

        assertThat(jobOperations(userId)).containsExactly("UPSERT", "DELETE_USER");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM memory_embeddings WHERE user_id = ?",
                Integer.class, userId)).isZero();
    }

    @Test
    void missingVectorScannerSkipsOpenJobsSoLaterBatchesDoNotStall() {
        Long userId = insertUser("13900000995");
        insertVector(userId, 81L, false);
        insertVector(userId, 82L, false);

        assertThat(jobService.enqueueMissingVectors("test-model", 1)).isEqualTo(1);
        assertThat(jobService.hasOpenUpsertJobs("test-model")).isTrue();
        assertThat(jobService.enqueueMissingVectors("test-model", 1)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForList("""
                SELECT source_id FROM memory_vector_index_jobs
                WHERE user_id = ? AND operation = 'UPSERT' ORDER BY id
                """, Long.class, userId)).containsExactly(81L, 82L);
        assertThat(jobService.hasOpenUpsertJobs("test-model")).isTrue();
        jobMapper.update(null, new UpdateWrapper<MemoryVectorIndexJob>()
                .eq("embedding_model", "test-model")
                .set("status", "COMPLETE"));
        assertThat(jdbcTemplate.queryForList("""
                SELECT status, operation FROM memory_vector_index_jobs
                WHERE embedding_model = 'test-model' AND operation = 'UPSERT' AND status != 'COMPLETE'
                """)).isEmpty();
        assertThat(jobService.hasOpenUpsertJobs("test-model")).isFalse();
    }

    @Test
    void identityAuditPagesOnlyIndexedVectorsAndQueuesTargetedRepair() {
        Long userId = insertUser("13900000993");
        insertVector(userId, 81L, true);
        insertVector(userId, 82L, false);
        insertVector(userId, 83L, true);

        List<MemoryEmbedding> firstPage = embeddingMapper.listIndexedVectorsAfterId("test-model", 0, 1);

        assertThat(firstPage).hasSize(1);
        assertThat(firstPage.get(0).getSourceId()).isEqualTo(81L);
        assertThat(embeddingMapper.findIndexedVectorsByIdentities("test-model", firstPage))
                .extracting(MemoryEmbedding::getSourceId).containsExactly(81L);
        MemoryEmbedding wrongVersion = firstPage.get(0);
        wrongVersion.setSourceVersion(2);
        assertThat(embeddingMapper.findIndexedVectorsByIdentities("test-model", List.of(wrongVersion))).isEmpty();
        wrongVersion.setSourceVersion(1);
        List<MemoryEmbedding> nextPage = embeddingMapper.listIndexedVectorsAfterId("test-model",
                firstPage.get(0).getId(), 1);
        assertThat(nextPage).hasSize(1);
        assertThat(nextPage.get(0).getSourceId()).isEqualTo(83L);

        assertThat(jobService.enqueueRepairUpsert(firstPage.get(0))).isEqualTo(1);
        assertThat(jobOperations(userId)).containsExactly("UPSERT");
        assertThat(jobService.hasOpenUpsertJobs("test-model")).isTrue();
    }

    @Test
    void manualRebuildClearsIndexedMarkersAndQueuesCurrentVectors() {
        Long userId = insertUser("13900000994");
        insertVector(userId, 83L, true);
        insertVector(userId, 84L, true);

        MemoryVectorIndexJobService.RebuildRequestResult result = jobService.requestRebuild("test-model");

        assertThat(result.vectorRowsReset()).isEqualTo(2);
        assertThat(result.upsertJobsQueued()).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM memory_embeddings
                WHERE user_id = ? AND embedding_model = 'test-model' AND ann_indexed_at IS NULL
                """, Integer.class, userId)).isEqualTo(2);
        assertThat(jobOperations(userId)).containsExactly("UPSERT", "UPSERT");
    }

    private List<String> jobOperations(Long userId) {
        return jdbcTemplate.queryForList("""
                SELECT operation FROM memory_vector_index_jobs WHERE user_id = ? ORDER BY id
                """, String.class, userId);
    }

    private void insertVector(Long userId, Long sourceId, boolean indexed) {
        jdbcTemplate.update("""
                INSERT INTO memory_embeddings (
                    user_id, source_kind, source_id, memory_type, source_version, embedding_model,
                    dimensions, embedding_json, ann_indexed_at, created_at, updated_at
                ) VALUES (?, 'MEMORY_ITEM', ?, 'INGREDIENT_PREFERENCE', 1, 'test-model', 2,
                          '[1.0,0.0]', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, userId, sourceId, indexed ? LocalDateTime.now() : null);
    }

    private Long insertUser(String phone) {
        jdbcTemplate.update("INSERT INTO users (phone, nickname) VALUES (?, ?)", phone, "vector-index-test");
        return jdbcTemplate.queryForObject("SELECT id FROM users WHERE phone = ?", Long.class, phone);
    }
}
