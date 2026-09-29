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
class MemoryFeedbackReactionSupersessionIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MemoryEpisodeService episodeService;

    @Autowired
    private MemoryCandidateService candidateService;

    @Autowired
    private MemoryConsolidationService consolidationService;

    @Test
    void clearingReactionSupersedesPreferenceAndRebuildsProfileButKeepsEvidence() {
        Long userId = insertUser("13900000901", "反馈撤销测试");
        LocalDateTime firstAt = LocalDateTime.now().minusMinutes(3);
        MemoryEpisode liked = reaction(userId, "feedback-clear-1", "REACTION", "LIKE", firstAt);

        candidateService.extractAndPersist(userId, liked.getId());
        consolidationService.consolidate(userId);
        assertThat(consolidationService.listOwnedItems(userId, 20))
                .extracting(MemoryItem::getPreference)
                .containsExactly("LIKE");

        MemoryEpisode cleared = reaction(userId, "feedback-clear-1", "REACTION_CLEARED", null,
                firstAt.plusMinutes(1));
        candidateService.extractAndPersist(userId, cleared.getId());
        consolidationService.consolidate(userId);

        assertThat(consolidationService.listOwnedItems(userId, 20)).isEmpty();
        MemoryCandidate candidate = candidateService.listOwned(userId, liked.getId(), null, 10).get(0);
        assertThat(candidate.getStatus()).isEqualTo(MemoryCandidateStatus.SUPERSEDED.name());
        assertThat(episodeService.findOwnedByIds(userId, List.of(liked.getId(), cleared.getId()))).hasSize(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM memory_evidence WHERE user_id = ? AND candidate_id = ?",
                Integer.class, userId, candidate.getId())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM memory_item_candidates WHERE user_id = ? AND candidate_id = ?",
                Integer.class, userId, candidate.getId())).isZero();
        assertThat(consolidationService.getOwnedProfile(userId).getProfileJson()).doesNotContain("红烧牛肉");
    }

    @Test
    void changedReactionSupersedesOnlyEarlierActionFromSameFeedbackSource() {
        Long userId = insertUser("13900000902", "反馈切换测试");
        LocalDateTime firstAt = LocalDateTime.now().minusMinutes(3);
        MemoryEpisode oldLike = reaction(userId, "feedback-change-1", "REACTION", "LIKE", firstAt);
        candidateService.extractAndPersist(userId, oldLike.getId());
        consolidationService.consolidate(userId);

        MemoryEpisode dislike = reaction(userId, "feedback-change-1", "REACTION", "DISLIKE",
                firstAt.plusMinutes(1));
        candidateService.extractAndPersist(userId, dislike.getId());
        consolidationService.consolidate(userId);

        MemoryEpisode separateLike = reaction(userId, "feedback-change-2", "REACTION", "LIKE",
                firstAt.plusMinutes(2));
        candidateService.extractAndPersist(userId, separateLike.getId());
        consolidationService.consolidate(userId);

        List<MemoryItem> active = consolidationService.listOwnedItems(userId, 20);
        assertThat(active).extracting(MemoryItem::getPreference).containsExactlyInAnyOrder("LIKE", "DISLIKE");
        MemoryCandidate oldCandidate = candidateService.listOwned(userId, oldLike.getId(), null, 10).get(0);
        assertThat(oldCandidate.getStatus()).isEqualTo(MemoryCandidateStatus.SUPERSEDED.name());
        assertThat(active.stream().filter(item -> "LIKE".equals(item.getPreference())).findFirst().orElseThrow()
                .getOccurrenceCount()).isEqualTo(1);
    }

    @Test
    void delayedExtractionOfOlderReactionCannotResurrectItAfterClear() {
        Long userId = insertUser("13900000903", "乱序事件测试");
        LocalDateTime firstAt = LocalDateTime.now().minusMinutes(3);
        MemoryEpisode oldLike = reaction(userId, "feedback-order-1", "REACTION", "LIKE", firstAt);
        MemoryEpisode cleared = reaction(userId, "feedback-order-1", "REACTION_CLEARED", null,
                firstAt.plusMinutes(1));

        candidateService.extractAndPersist(userId, cleared.getId());
        candidateService.extractAndPersist(userId, oldLike.getId());
        consolidationService.consolidate(userId);

        assertThat(candidateService.listOwned(userId, null, null, 20)).isEmpty();
        assertThat(consolidationService.listOwnedItems(userId, 20)).isEmpty();
    }

    @Test
    void consolidationBackfillsLegacyClearedReactionAndKeepsItsProvenance() {
        Long userId = insertUser("13900000905", "历史撤销回溯测试");
        LocalDateTime firstAt = LocalDateTime.now().minusMinutes(3);
        MemoryEpisode liked = reaction(userId, "feedback-legacy-clear", "REACTION", "LIKE", firstAt);
        candidateService.extractAndPersist(userId, liked.getId());
        consolidationService.consolidate(userId);

        MemoryCandidate oldCandidate = candidateService.listOwned(userId, liked.getId(), null, 10).get(0);
        assertThat(consolidationService.listOwnedItems(userId, 20)).hasSize(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM memory_evidence WHERE user_id = ? AND candidate_id = ?",
                Integer.class, userId, oldCandidate.getId())).isEqualTo(1);

        MemoryEpisode cleared = reaction(userId, "feedback-legacy-clear", "REACTION_CLEARED", null,
                firstAt.plusMinutes(1));
        // Simulate legacy state: the clear Episode was recorded, but old deployments
        // did not extract it into a candidate or reconcile the existing profile.
        consolidationService.consolidate(userId);

        assertThat(consolidationService.listOwnedItems(userId, 20)).isEmpty();
        assertThat(candidateService.findOwned(userId, oldCandidate.getId()).getStatus())
                .isEqualTo(MemoryCandidateStatus.SUPERSEDED.name());
        assertThat(episodeService.findOwnedByIds(userId, List.of(liked.getId(), cleared.getId()))).hasSize(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM memory_evidence WHERE user_id = ? AND candidate_id = ?",
                Integer.class, userId, oldCandidate.getId())).isEqualTo(1);
    }

    @Test
    void consolidationBackfillsLegacyPolarityChangeBeforeNewExtraction() {
        Long userId = insertUser("13900000906", "历史反向反馈回溯测试");
        LocalDateTime firstAt = LocalDateTime.now().minusMinutes(3);
        MemoryEpisode liked = reaction(userId, "feedback-legacy-change", "REACTION", "LIKE", firstAt);
        candidateService.extractAndPersist(userId, liked.getId());
        consolidationService.consolidate(userId);

        MemoryEpisode disliked = reaction(userId, "feedback-legacy-change", "REACTION", "DISLIKE",
                firstAt.plusMinutes(1));
        // The backfill must not keep the old positive candidate active while the new
        // negative Episode is waiting for extraction.
        consolidationService.consolidate(userId);
        assertThat(consolidationService.listOwnedItems(userId, 20)).isEmpty();
        assertThat(candidateService.listOwned(userId, liked.getId(), null, 10).get(0).getStatus())
                .isEqualTo(MemoryCandidateStatus.SUPERSEDED.name());

        candidateService.extractAndPersist(userId, disliked.getId());
        consolidationService.consolidate(userId);
        assertThat(consolidationService.listOwnedItems(userId, 20))
                .extracting(MemoryItem::getPreference)
                .containsExactly("DISLIKE");
    }

    @Test
    void explicitlyConfirmedCandidateSurvivesReactionClear() {
        Long userId = insertUser("13900000904", "确认偏好测试");
        LocalDateTime firstAt = LocalDateTime.now().minusMinutes(3);
        MemoryEpisode liked = reaction(userId, "feedback-confirm-1", "REACTION", "LIKE", firstAt);
        candidateService.extractAndPersist(userId, liked.getId());
        consolidationService.consolidate(userId);
        Long candidateId = candidateService.listOwned(userId, liked.getId(), null, 10).get(0).getId();
        jdbcTemplate.update("UPDATE memory_candidates SET user_decision = 'CONFIRM', source_type = 'USER_CONFIRMED' "
                + "WHERE user_id = ? AND id = ?", userId, candidateId);

        MemoryEpisode cleared = reaction(userId, "feedback-confirm-1", "REACTION_CLEARED", null,
                firstAt.plusMinutes(1));
        candidateService.extractAndPersist(userId, cleared.getId());
        consolidationService.consolidate(userId);

        assertThat(consolidationService.listOwnedItems(userId, 20))
                .extracting(MemoryItem::getPreference)
                .containsExactly("LIKE");
        assertThat(candidateService.findOwned(userId, candidateId).getStatus())
                .isEqualTo(MemoryCandidateStatus.CONSOLIDATED.name());
    }

    private MemoryEpisode reaction(Long userId, String sourceId, String action, String reaction,
                                  LocalDateTime occurredAt) {
        String payload = "REACTION_CLEARED".equals(action)
                ? "{\"action\":\"REACTION_CLEARED\"}"
                : "{\"action\":\"REACTION\",\"reaction\":\"" + reaction
                + "\",\"recipeTitle\":\"红烧牛肉\"}";
        return episodeService.record(userId, new MemoryEpisodeCommand(
                null, null, "RECIPE_FEEDBACK", "RECOMMENDATION_FEEDBACK", sourceId,
                sourceId + "-" + action + "-" + occurredAt.getMinute(),
                sourceId + "-" + action + "-" + occurredAt.getMinute(),
                "反馈：" + action, payload, occurredAt, null
        )).episode();
    }

    private Long insertUser(String phone, String nickname) {
        jdbcTemplate.update("INSERT INTO users (phone, nickname) VALUES (?, ?)", phone, nickname);
        return jdbcTemplate.queryForObject("SELECT id FROM users WHERE phone = ?", Long.class, phone);
    }
}
