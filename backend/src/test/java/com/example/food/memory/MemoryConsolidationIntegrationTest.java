package com.example.food.memory;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MemoryConsolidationIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MemoryEpisodeService episodeService;

    @Autowired
    private MemoryCandidateService candidateService;

    @Autowired
    private MemoryConsolidationService consolidationService;

    @Test
    void consolidatesRepeatedCandidatesAndRebuildsIsolatedProfileIdempotently() {
        Long userId = insertUser("13900000701", "合并记忆用户");
        Long otherUserId = insertUser("13900000702", "其他画像用户");

        MemoryEpisode first = saveEpisode(userId, "save-1", "鸡胸肉意面");
        MemoryEpisode second = saveEpisode(userId, "save-2", "鸡胸肉意面");
        assertThat(episodeService.findOwnedByIds(userId, List.of(first.getId(), second.getId())))
                .hasSize(2);
        assertThat(episodeService.findOwnedByIds(otherUserId, List.of(first.getId(), second.getId())))
                .isEmpty();
        candidateService.extractAndPersist(userId, first.getId());
        candidateService.extractAndPersist(userId, second.getId());

        MemoryConsolidationService.ConsolidationResult firstRun = consolidationService.consolidate(userId);
        MemoryConsolidationService.ConsolidationResult retry = consolidationService.consolidate(userId);

        assertThat(firstRun.processedCandidateCount()).isEqualTo(4);
        assertThat(firstRun.changedItemCount()).isEqualTo(2);
        assertThat(retry.processedCandidateCount()).isZero();
        assertThat(retry.changedItemCount()).isZero();

        List<MemoryItem> items = consolidationService.listOwnedItems(userId, 20);
        assertThat(items).hasSize(2);
        assertThat(items).allSatisfy(item -> {
            assertThat(item.getUserId()).isEqualTo(userId);
            assertThat(item.getEvidenceCount()).isEqualTo(2);
            assertThat(item.getOccurrenceCount()).isEqualTo(2);
            assertThat(item.getSourceCount()).isEqualTo(2);
            assertThat(item.getConfidence()).isBetween(BigDecimal.ZERO, BigDecimal.ONE);
        });
        List<MemoryCandidate> sourceCandidates = consolidationService.listOwnedSourceCandidates(userId, items);
        assertThat(sourceCandidates).hasSize(4)
                .allSatisfy(candidate -> assertThat(candidate.getSourceType()).isEqualTo("IMPLICIT_BEHAVIOR"));

        MemoryProfile profile = consolidationService.getOwnedProfile(userId);
        assertThat(profile).isNotNull();
        assertThat(profile.getSourceRevision()).isEqualTo(items.stream()
                .map(MemoryItem::getId).max(Long::compareTo).orElseThrow());
        assertThat(profile.getProfileJson())
                .contains("ingredientPreferences")
                .contains("recipePreferences")
                .contains("鸡胸肉")
                .contains("liked");
        assertThat(consolidationService.listOwnedItems(otherUserId, 20)).isEmpty();

        Integer consolidated = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM memory_candidates WHERE user_id = ? AND status = 'CONSOLIDATED'",
                Integer.class,
                userId
        );
        assertThat(consolidated).isEqualTo(4);
        Integer relationCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM memory_item_candidates WHERE user_id = ?",
                Integer.class,
                userId
        );
        assertThat(relationCount).isEqualTo(4);
    }

    private MemoryEpisode saveEpisode(Long userId, String key, String title) {
        return episodeService.record(userId, new MemoryEpisodeCommand(
                null,
                null,
                "RECIPE_SAVED",
                "RECIPE_RECORD",
                key,
                key,
                key,
                "收藏菜谱：" + title,
                "{\"recipeTitle\":\"" + title + "\",\"ingredients\":[\"鸡胸肉\"]}",
                null,
                null
        )).episode();
    }

    private Long insertUser(String phone, String nickname) {
        jdbcTemplate.update("INSERT INTO users (phone, nickname) VALUES (?, ?)", phone, nickname);
        return jdbcTemplate.queryForObject("SELECT id FROM users WHERE phone = ?", Long.class, phone);
    }
}
