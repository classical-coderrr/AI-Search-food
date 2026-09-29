package com.example.food.memory;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MemoryEmbeddingIndexJobIntegrationTest {
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private MemoryEmbeddingIndexJobMapper jobMapper;
    @Autowired private MemoryEmbeddingMapper embeddingMapper;
    @Autowired private MemoryEmbeddingIndexJobService jobService;
    @Autowired private MemoryEpisodeService episodeService;
    @Autowired private MemoryVectorStoreAdapter vectorStore;

    @Test
    void retryBackoffSkipsFailedCandidateAndLetsLaterMemoryProgress() {
        Long userId = insertUser("13900000996");
        MemoryEpisode first = saveEpisode(userId, "retry-first");
        MemoryEpisode second = saveEpisode(userId, "retry-second");
        List<MemoryEmbeddingCandidate> candidates = pending(userId);
        MemoryEmbeddingCandidate firstCandidate = candidateFor(candidates, first.getId());

        MemoryEmbeddingIndexJob claimed = jobService.claim(firstCandidate, "test-model", 2);
        assertThat(claimed).isNotNull();
        assertThat(jobService.retry(claimed, "PROVIDER_UNAVAILABLE")).isTrue();

        List<MemoryEmbeddingCandidate> afterFailure = pending(userId);
        assertThat(afterFailure).noneMatch(candidate -> first.getId().equals(candidate.getSourceId()));
        assertThat(afterFailure).anyMatch(candidate -> second.getId().equals(candidate.getSourceId()));

        jdbcTemplate.update("UPDATE memory_embedding_index_jobs SET available_at = TIMESTAMP '2000-01-01 00:00:00' WHERE id = ?",
                claimed.getId());
        assertThat(pending(userId)).anyMatch(candidate -> first.getId().equals(candidate.getSourceId()));
        MemoryEmbeddingIndexJob retried = jobService.claim(firstCandidate, "test-model", 2);
        assertThat(retried).isNotNull();
        assertThat(retried.getAttempts()).isEqualTo(2);
    }

    @Test
    void activeLeasePreventsDuplicateClaimsAndExpiredLeaseCanBeReclaimed() {
        Long userId = insertUser("13900000997");
        MemoryEpisode episode = saveEpisode(userId, "lease-recovery");
        MemoryEmbeddingCandidate candidate = candidateFor(pending(userId), episode.getId());

        MemoryEmbeddingIndexJob firstClaim = jobService.claim(candidate, "test-model", 2);
        MemoryEmbeddingIndexJob duplicateClaim = jobService.claim(candidate, "test-model", 2);

        assertThat(firstClaim).isNotNull();
        assertThat(duplicateClaim).isNull();

        jdbcTemplate.update("UPDATE memory_embedding_index_jobs SET lease_until = TIMESTAMP '2000-01-01 00:00:00' WHERE id = ?",
                firstClaim.getId());
        MemoryEmbeddingIndexJob reclaimed = jobService.claim(candidate, "test-model", 2);

        assertThat(reclaimed).isNotNull();
        assertThat(reclaimed.getLeaseToken()).isNotEqualTo(firstClaim.getLeaseToken());
        assertThat(reclaimed.getAttempts()).isEqualTo(2);
    }

    @Test
    void sourceVersionChangeImmediatelyReleasesRetryBackoffAndResetsAttempts() {
        Long userId = insertUser("13900000998");
        MemoryEpisode episode = saveEpisode(userId, "version-change-retry");
        MemoryEmbeddingCandidate original = candidateFor(pending(userId), episode.getId());
        MemoryEmbeddingIndexJob claimed = jobService.claim(original, "test-model", 2);
        assertThat(jobService.retry(claimed, "PROVIDER_UNAVAILABLE")).isTrue();

        jdbcTemplate.update("UPDATE memory_episodes SET version = version + 1 WHERE user_id = ? AND id = ?",
                userId, episode.getId());
        MemoryEmbeddingCandidate updated = candidateFor(pending(userId), episode.getId());
        MemoryEmbeddingIndexJob reclaimed = jobService.claim(updated, "test-model", 2);

        assertThat(reclaimed).isNotNull();
        assertThat(reclaimed.getSourceVersion()).isEqualTo(updated.getSourceVersion());
        assertThat(reclaimed.getAttempts()).isEqualTo(1);
    }

    @Test
    void completedJobWithMissingVectorIsSelectedForIndexRepair() {
        Long userId = insertUser("13900000995");
        MemoryEpisode episode = saveEpisode(userId, "missing-vector-repair");
        MemoryEmbeddingCandidate candidate = candidateFor(pending(userId), episode.getId());
        MemoryEmbeddingIndexJob claimed = jobService.claim(candidate, "test-model", 2);

        assertThat(jobService.complete(claimed)).isTrue();
        assertThat(pending(userId)).anyMatch(item -> episode.getId().equals(item.getSourceId()));
    }

    @Test
    void vectorDeletionAlsoRemovesItsRetryMetadata() {
        Long userId = insertUser("13900000999");
        MemoryEpisode episode = saveEpisode(userId, "deleted-retry-source");
        MemoryEmbeddingCandidate candidate = candidateFor(pending(userId), episode.getId());
        MemoryEmbeddingIndexJob claimed = jobService.claim(candidate, "test-model", 2);
        assertThat(jobService.retry(claimed, "PROVIDER_UNAVAILABLE")).isTrue();
        assertThat(jobMapper.selectCount(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<MemoryEmbeddingIndexJob>()
                .eq("user_id", userId))).isEqualTo(1);

        vectorStore.deleteSource(userId, "EPISODE", episode.getId());

        assertThat(jobMapper.selectCount(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<MemoryEmbeddingIndexJob>()
                .eq("user_id", userId))).isZero();
    }

    private List<MemoryEmbeddingCandidate> pending(Long userId) {
        return embeddingMapper.findUnindexedCandidates("test-model", 2, 100, userId, LocalDateTime.now());
    }

    private MemoryEmbeddingCandidate candidateFor(List<MemoryEmbeddingCandidate> candidates, Long episodeId) {
        return candidates.stream().filter(candidate -> "EPISODE".equals(candidate.getSourceKind())
                        && episodeId.equals(candidate.getSourceId()))
                .findFirst().orElseThrow();
    }

    private MemoryEpisode saveEpisode(Long userId, String key) {
        return episodeService.record(userId, new MemoryEpisodeCommand(null, null, "RECIPE_SAVED",
                "RECIPE_RECORD", key, key, key, "训练后偏好", "{\"scene\":\"POST_WORKOUT\"}",
                null, null)).episode();
    }

    private Long insertUser(String phone) {
        jdbcTemplate.update("INSERT INTO users (phone, nickname) VALUES (?, ?)", phone, "memory-embedding-job-test");
        return jdbcTemplate.queryForObject("SELECT id FROM users WHERE phone = ?", Long.class, phone);
    }
}
