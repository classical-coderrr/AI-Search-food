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

    @Autowired
    private PersonalizedSkillMapper personalizedSkillMapper;

    @Autowired
    private PersonalizedSkillProjectionService skillProjectionService;

    @Autowired
    private MemoryPersonalizationService personalizationService;

    @Autowired
    private MemoryProfileMapper profileMapper;

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

        assertThat(firstRun.processedCandidateCount()).isEqualTo(2);
        assertThat(firstRun.changedItemCount()).isEqualTo(1);
        assertThat(retry.processedCandidateCount()).isZero();
        assertThat(retry.changedItemCount()).isZero();

        List<MemoryItem> items = consolidationService.listOwnedItems(userId, 20);
        assertThat(items).hasSize(1);
        assertThat(items).allSatisfy(item -> {
            assertThat(item.getUserId()).isEqualTo(userId);
            assertThat(item.getEvidenceCount()).isEqualTo(2);
            assertThat(item.getOccurrenceCount()).isEqualTo(2);
            assertThat(item.getSourceCount()).isEqualTo(2);
            assertThat(item.getConfidence()).isBetween(BigDecimal.ZERO, BigDecimal.ONE);
        });
        List<MemoryCandidate> sourceCandidates = consolidationService.listOwnedSourceCandidates(userId, items);
        assertThat(sourceCandidates).hasSize(2)
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

        MemoryItem recipeMemory = items.get(0);
        jdbcTemplate.update("UPDATE memory_items SET temporal_type = 'LONG_TERM', confidence = 0.9000 "
                        + "WHERE user_id = ? AND id = ?", userId, recipeMemory.getId());
        consolidationService.refreshProfileForManagement(userId);
        MemoryProfile refreshedProfile = consolidationService.getOwnedProfile(userId);
        PersonalizedSkillService.SkillContext derived = new PersonalizedSkillService(new com.fasterxml.jackson.databind.ObjectMapper())
                .resolve("推荐一道晚餐", refreshedProfile.getProfileJson());
        assertThat(derived.strategy()).as(refreshedProfile.getProfileJson())
                .containsEntry("likedRecipeReferences", List.of("鸡胸肉意面"));
        PersonalizedSkill persistedSkill = personalizedSkillMapper.findActiveOwned(userId, "RECOMMEND_RECIPE");
        assertThat(persistedSkill).isNotNull();
        assertThat(persistedSkill.getStrategyJson()).contains("likedRecipeReferences", "鸡胸肉意面");
        assertThat(persistedSkill.getSourceMemoryIdsJson()).contains(recipeMemory.getId().toString());
        assertThat(persistedSkill.getEvidenceCount()).isEqualTo(2);
        assertThat(persistedSkill.getConfidence()).isEqualByComparingTo("0.9000");
        assertThat(persistedSkill.getSourceProfileVersion()).isEqualTo(refreshedProfile.getVersion());

        PersonalizedSkillService.SkillContext runtimeSkill = skillProjectionService.resolve(
                userId, "推荐一道晚餐", refreshedProfile);
        assertThat(runtimeSkill.personalized()).isTrue();
        assertThat(runtimeSkill.strategy()).containsEntry("likedRecipeReferences", List.of("鸡胸肉意面"));
        assertThat(runtimeSkill.promptContext()).contains("个性化策略", "鸡胸肉意面");
        assertThat(personalizedSkillMapper.findActiveOwned(otherUserId, "RECOMMEND_RECIPE")).isNull();

        MemoryPersonalizationState disabled = personalizationService.update(userId,
                new MemoryPersonalizationRequest(false, 0));
        assertThat(disabled.enabled()).isFalse();
        PersonalizedSkillService.SkillContext withoutPersonalization = skillProjectionService.resolve(
                userId, "推荐一道晚餐", refreshedProfile);
        assertThat(withoutPersonalization.personalized()).isFalse();
        assertThat(withoutPersonalization.strategy()).isEmpty();
        assertThat(personalizationService.update(userId, new MemoryPersonalizationRequest(true, disabled.version()))
                .enabled()).isTrue();

        jdbcTemplate.update("UPDATE memory_items SET status = 'DELETED', deleted_at = CURRENT_TIMESTAMP, "
                + "version = version + 1 WHERE user_id = ? AND id = ?", userId, recipeMemory.getId());
        consolidationService.refreshProfileForManagement(userId);
        assertThat(personalizedSkillMapper.findActiveOwned(userId, "RECOMMEND_RECIPE")).isNull();
        PersonalizedSkill inactiveSkill = personalizedSkillMapper.findOwnedForRebuild(userId, "RECOMMEND_RECIPE");
        assertThat(inactiveSkill).isNotNull();
        assertThat(inactiveSkill.getDeletedAt()).isNotNull();
        profileMapper.deleteOwned(userId);
        assertThat(personalizedSkillMapper.findOwnedForRebuild(userId, "RECOMMEND_RECIPE")).isNull();

        Integer consolidated = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM memory_candidates WHERE user_id = ? AND status = 'CONSOLIDATED'",
                Integer.class,
                userId
        );
        assertThat(consolidated).isEqualTo(2);
        Integer relationCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM memory_item_candidates WHERE user_id = ?",
                Integer.class,
                userId
        );
        assertThat(relationCount).isEqualTo(2);
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
