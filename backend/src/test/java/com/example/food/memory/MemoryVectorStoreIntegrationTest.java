package com.example.food.memory;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MemoryVectorStoreIntegrationTest {
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private MemoryVectorStoreAdapter vectorStore;
    @Autowired private MemoryEmbeddingMapper embeddingMapper;
    @Autowired private MemoryEpisodeService episodeService;

    @Test
    void persistsExactVectorsSupportsReindexAndKeepsUsersIsolated() {
        Long userA = insertUser("13900000991");
        Long userB = insertUser("13900000992");
        vectorStore.upsert(new MemoryVectorDocument(userA, "MEMORY_ITEM", 77L,
                "INGREDIENT_PREFERENCE", 1, "test-model", new float[]{1.0f, 0.0f}));
        vectorStore.upsert(new MemoryVectorDocument(userB, "MEMORY_ITEM", 77L,
                "INGREDIENT_PREFERENCE", 1, "test-model", new float[]{0.0f, 1.0f}));

        assertThat(vectorStore.search(userA, "test-model", new float[]{1.0f, 0.0f}, 10))
                .extracting(MemoryVectorMatch::sourceId).containsExactly(77L);
        assertThat(vectorStore.search(userB, "test-model", new float[]{1.0f, 0.0f}, 10).get(0).similarity())
                .isZero();

        vectorStore.upsert(new MemoryVectorDocument(userA, "MEMORY_ITEM", 77L,
                "INGREDIENT_PREFERENCE", 2, "test-model", new float[]{0.0f, 1.0f}));
        assertThat(vectorStore.search(userA, "test-model", new float[]{1.0f, 0.0f}, 10).get(0).similarity())
                .isZero();

        vectorStore.deleteSource(userA, "MEMORY_ITEM", 77L);
        assertThat(vectorStore.search(userA, "test-model", new float[]{1.0f, 0.0f}, 10)).isEmpty();
        assertThat(vectorStore.search(userB, "test-model", new float[]{0.0f, 1.0f}, 10))
                .extracting(MemoryVectorMatch::sourceId).containsExactly(77L);
    }

    @Test
    void findsUnindexedSourcesAndSkipsUsersWhoDisabledPersonalization() {
        Long enabledUser = insertUser("13900000993");
        Long disabledUser = insertUser("13900000994");
        MemoryEpisode enabledEpisode = saveEpisode(enabledUser, "enabled-episode");
        saveEpisode(disabledUser, "disabled-episode");
        jdbcTemplate.update("INSERT INTO user_memory_settings (user_id, personalization_enabled, version) VALUES (?, FALSE, 1)",
                disabledUser);

        List<MemoryEmbeddingCandidate> pending = embeddingMapper.findUnindexedCandidates("test-model", 2, 20, null,
                LocalDateTime.now());

        assertThat(pending).anySatisfy(candidate -> {
            assertThat(candidate.getUserId()).isEqualTo(enabledUser);
            assertThat(candidate.getSourceKind()).isEqualTo("EPISODE");
            assertThat(candidate.getSourceId()).isEqualTo(enabledEpisode.getId());
            assertThat(candidate.getContent()).contains("训练后偏好");
        });
        assertThat(pending).noneMatch(candidate -> disabledUser.equals(candidate.getUserId()));

        List<MemoryEmbeddingCandidate> scoped = embeddingMapper.findUnindexedCandidates("test-model", 2, 20,
                enabledUser, LocalDateTime.now());

        assertThat(scoped).allMatch(candidate -> enabledUser.equals(candidate.getUserId()));
        assertThat(scoped).anyMatch(candidate -> enabledEpisode.getId().equals(candidate.getSourceId()));
    }

    @Test
    void reindexesExistingSourcesWhenConfiguredDimensionsChange() {
        Long userId = insertUser("13900000995");
        MemoryEpisode episode = saveEpisode(userId, "dimension-change-episode");
        Long memoryItemId = saveMemoryItem(userId, "dimension-change-item");
        vectorStore.upsert(new MemoryVectorDocument(userId, "EPISODE", episode.getId(), episode.getEpisodeType(),
                episode.getVersion(), "test-model", new float[]{1.0f, 0.0f}));
        vectorStore.upsert(new MemoryVectorDocument(userId, "MEMORY_ITEM", memoryItemId,
                "INGREDIENT_PREFERENCE", 1, "test-model", new float[]{1.0f, 0.0f}));

        List<MemoryEmbeddingCandidate> sameDimensions = embeddingMapper.findUnindexedCandidates(
                "test-model", 2, 20, userId, LocalDateTime.now());
        List<MemoryEmbeddingCandidate> changedDimensions = embeddingMapper.findUnindexedCandidates(
                "test-model", 3, 20, userId, LocalDateTime.now());

        assertThat(sameDimensions).noneMatch(candidate -> episode.getId().equals(candidate.getSourceId())
                || memoryItemId.equals(candidate.getSourceId()));
        assertThat(changedDimensions).anySatisfy(candidate -> {
            assertThat(candidate.getSourceKind()).isEqualTo("EPISODE");
            assertThat(candidate.getSourceId()).isEqualTo(episode.getId());
        }).anySatisfy(candidate -> {
            assertThat(candidate.getSourceKind()).isEqualTo("MEMORY_ITEM");
            assertThat(candidate.getSourceId()).isEqualTo(memoryItemId);
        });
    }

    private MemoryEpisode saveEpisode(Long userId, String key) {
        return episodeService.record(userId, new MemoryEpisodeCommand(null, null, "RECIPE_SAVED",
                "RECIPE_RECORD", key, key, key, "训练后偏好", "{\"scene\":\"POST_WORKOUT\"}",
                null, null)).episode();
    }

    private Long saveMemoryItem(Long userId, String key) {
        jdbcTemplate.update("""
                INSERT INTO memory_items
                    (user_id, memory_type, canonical_entity, preference, strength, confidence, importance,
                     source_candidate_ids_json, source_episode_ids_json, first_seen_at, last_seen_at,
                     consolidation_key, status, version)
                VALUES (?, 'INGREDIENT_PREFERENCE', '鸡胸肉', 'LIKE', 0.75, 0.60, 0.50,
                        '[]', '[]', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?, 'ACTIVE', 1)
                """, userId, key);
        return jdbcTemplate.queryForObject("SELECT id FROM memory_items WHERE user_id = ? AND consolidation_key = ?",
                Long.class, userId, key);
    }

    private Long insertUser(String phone) {
        jdbcTemplate.update("INSERT INTO users (phone, nickname) VALUES (?, ?)", phone, "memory-vector-test");
        return jdbcTemplate.queryForObject("SELECT id FROM users WHERE phone = ?", Long.class, phone);
    }
}
