package com.example.food.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MemoryConfirmationIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private MemoryEpisodeService episodeService;
    @Autowired
    private MemoryCandidateService candidateService;
    @Autowired
    private MemoryCandidateMapper candidateMapper;
    @Autowired
    private MemoryConfirmationService confirmationService;
    @Autowired
    private MemoryConsolidationService consolidationService;
    @Autowired
    private PersonalizedSkillService personalizedSkillService;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void asksBeforePromotingRepeatedDietGoalAndOnlyUsesItAfterConfirmation() throws Exception {
        assertThat(columnExists("user_decision")).isTrue();
        assertThat(columnExists("decided_at")).isTrue();
        Long userId = insertUser();
        recordSavedGoal(userId, "muscle_gain", "高蛋白鸡胸肉饭 1");
        recordSavedGoal(userId, "muscle_gain", "香煎鸡胸肉 2");

        MemoryProfile recentProfile = consolidationService.getOwnedProfile(userId);
        assertThat(objectMapper.readTree(recentProfile.getProfileJson()).path("dietGoals")).isEmpty();
        assertThat(confirmationService.listPending(userId, 20)).isEmpty();

        recordSavedGoal(userId, "muscle_gain", "鸡胸肉意面 3");

        List<MemoryConfirmationResponse> pending = confirmationService.listPending(userId, 20);
        assertThat(pending).hasSize(1);
        MemoryConfirmationResponse prompt = pending.get(0);
        assertThat(prompt.entity()).isEqualTo("增肌");
        assertThat(prompt.evidenceCount()).isEqualTo(3);
        assertThat(prompt.evidenceSummaries()).hasSize(3);
        assertThat(objectMapper.readTree(consolidationService.getOwnedProfile(userId).getProfileJson())
                .path("dietGoals")).isEmpty();

        MemoryConfirmationDecisionResult result = confirmationService.decide(userId, prompt.candidateId(),
                new MemoryConfirmationDecisionRequest(MemoryConfirmationDecision.CONFIRM, prompt.version()));

        assertThat(result.profileUpdated()).isTrue();
        MemoryCandidate confirmed = candidateMapper.findOwned(userId, prompt.candidateId());
        assertThat(confirmed.getStatus()).isEqualTo(MemoryCandidateStatus.CONSOLIDATED.name());
        assertThat(confirmed.getUserDecision()).isEqualTo(MemoryConfirmationDecision.CONFIRM.name());
        assertThat(confirmed.getTemporalType()).isEqualTo("LONG_TERM");
        assertThat(confirmed.getSourceType()).isEqualTo("USER_CONFIRMED");
        assertThat(confirmed.getConfidence()).isEqualByComparingTo("0.9800");
        assertThat(confirmed.getEvidenceCount()).isEqualTo(3);

        var profile = objectMapper.readTree(consolidationService.getOwnedProfile(userId).getProfileJson());
        assertThat(profile.path("dietGoals").get(0).path("entity").asText()).isEqualTo("增肌");
        PersonalizedSkillService.SkillContext skill = personalizedSkillService.resolve("推荐一道晚餐",
                consolidationService.getOwnedProfile(userId).getProfileJson());
        assertThat(skill.strategy()).containsEntry("prioritizeDietGoals", List.of("增肌"));

        Integer decisionEvidenceCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM memory_evidence WHERE candidate_id = ? AND source_type = 'USER_CONFIRMATION'",
                Integer.class,
                prompt.candidateId()
        );
        assertThat(decisionEvidenceCount).isEqualTo(1);
    }

    @Test
    void rejectAndOnlyThisTimeDoNotPromoteAndDecisionIsIdempotent() throws Exception {
        Long userId = insertUser();
        Long otherUserId = insertUser();
        Long rejectCandidateId = createPendingGoal(userId, "fat_loss");
        MemoryCandidate rejectCandidate = candidateMapper.findOwned(userId, rejectCandidateId);

        assertThat(confirmationService.listPending(otherUserId, 20)).isEmpty();
        assertThatThrownBy(() -> confirmationService.decide(otherUserId, rejectCandidateId,
                new MemoryConfirmationDecisionRequest(MemoryConfirmationDecision.CONFIRM,
                        rejectCandidate.getVersion())))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("待确认记忆不存在");

        MemoryConfirmationDecisionRequest reject = new MemoryConfirmationDecisionRequest(
                MemoryConfirmationDecision.REJECT, rejectCandidate.getVersion());
        MemoryConfirmationDecisionResult rejected = confirmationService.decide(userId, rejectCandidateId, reject);
        MemoryConfirmationDecisionResult duplicate = confirmationService.decide(userId, rejectCandidateId, reject);

        assertThat(rejected.status()).isEqualTo(MemoryCandidateStatus.REJECTED.name());
        assertThat(duplicate.alreadyProcessed()).isTrue();
        assertThat(objectMapper.readTree(consolidationService.getOwnedProfile(userId).getProfileJson())
                .path("dietGoals")).isEmpty();
    }

    @Test
    void onlyThisTimeStoresTheChoiceWithoutCreatingStableProfileData() throws Exception {
        Long userId = insertUser();
        Long candidateId = createPendingGoal(userId, "low_sugar");
        MemoryCandidate candidate = candidateMapper.findOwned(userId, candidateId);

        MemoryConfirmationDecisionResult result = confirmationService.decide(userId, candidateId,
                new MemoryConfirmationDecisionRequest(MemoryConfirmationDecision.ONLY_THIS_TIME,
                        candidate.getVersion()));

        MemoryCandidate dismissed = candidateMapper.findOwned(userId, candidateId);
        assertThat(result.profileUpdated()).isFalse();
        assertThat(dismissed.getStatus()).isEqualTo(MemoryCandidateStatus.REJECTED.name());
        assertThat(dismissed.getUserDecision()).isEqualTo(MemoryConfirmationDecision.ONLY_THIS_TIME.name());
        assertThat(objectMapper.readTree(consolidationService.getOwnedProfile(userId).getProfileJson())
                .path("dietGoals")).isEmpty();
    }

    @Test
    void rejectsStaleVersionWithoutWritingDecisionEvidence() {
        Long userId = insertUser();
        Long candidateId = createPendingGoal(userId, "protein");
        MemoryCandidate candidate = candidateMapper.findOwned(userId, candidateId);

        assertThatThrownBy(() -> confirmationService.decide(userId, candidateId,
                new MemoryConfirmationDecisionRequest(MemoryConfirmationDecision.CONFIRM,
                        candidate.getVersion() + 1)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("记忆已更新");
        assertThat(candidateMapper.findOwned(userId, candidateId).getStatus())
                .isEqualTo(MemoryCandidateStatus.AWAITING_CONFIRMATION.name());
        Integer evidenceCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM memory_evidence WHERE candidate_id = ? AND source_type = 'USER_CONFIRMATION'",
                Integer.class,
                candidateId
        );
        assertThat(evidenceCount).isZero();
    }

    private Long createPendingGoal(Long userId, String goal) {
        String title = "验收菜谱";
        recordSavedGoal(userId, goal, title + " 1");
        recordSavedGoal(userId, goal, title + " 2");
        MemoryEpisode third = recordSavedGoal(userId, goal, title + " 3");
        return candidateService.listOwned(userId, third.getId(), "DIET_GOAL", 20).stream()
                .filter(candidate -> MemoryCandidateStatus.AWAITING_CONFIRMATION.name().equals(candidate.getStatus()))
                .map(MemoryCandidate::getId)
                .findFirst()
                .orElseThrow();
    }

    private MemoryEpisode recordSavedGoal(Long userId, String goal, String title) {
        String key = UUID.randomUUID().toString();
        MemoryEpisode episode = episodeService.record(userId, new MemoryEpisodeCommand(
                null, null, "RECIPE_SAVED", "RECIPE_RECORD", key, key, key,
                "收藏菜谱：" + title,
                "{\"title\":\"" + title + "\",\"goal\":\"" + goal + "\",\"ingredients\":[]}",
                LocalDateTime.now(), new BigDecimal("0.65")
        )).episode();
        candidateService.extractAndPersist(userId, episode.getId());
        consolidationService.consolidate(userId);
        return episode;
    }

    private Long insertUser() {
        String phone = "139" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000));
        jdbcTemplate.update("INSERT INTO users (phone, nickname) VALUES (?, ?)", phone, "记忆确认测试用户");
        return jdbcTemplate.queryForObject("SELECT id FROM users WHERE phone = ?", Long.class, phone);
    }

    private boolean columnExists(String columnName) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS "
                        + "WHERE LOWER(TABLE_NAME) = 'memory_candidates' AND LOWER(COLUMN_NAME) = ?",
                Integer.class,
                columnName
        );
        return count != null && count == 1;
    }
}
