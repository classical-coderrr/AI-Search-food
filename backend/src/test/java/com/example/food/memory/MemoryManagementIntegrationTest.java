package com.example.food.memory;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MemoryManagementIntegrationTest {

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private MemoryEpisodeService episodeService;
    @Autowired private MemoryCandidateService candidateService;
    @Autowired private MemoryConsolidationService consolidationService;
    @Autowired private MemoryManagementService managementService;
    @Autowired private MemoryPersonalizationService personalizationService;
    @Autowired private MemorySessionService sessionService;
    @Autowired private MemoryBehaviorEpisodeRecorder episodeRecorder;

    @Test
    void userEditSurvivesLaterConsolidationAndDoesNotCrossAccounts() {
        Long userId = insertUser("13900000801", "记忆管理用户");
        Long otherUserId = insertUser("13900000802", "其他记忆用户");
        MemoryEpisode first = saveRecipeEpisode(userId, "management-save-1", "鸡胸肉沙拉");
        candidateService.extractAndPersist(userId, first.getId());
        consolidationService.consolidate(userId);

        MemoryItem ingredientMemory = ingredientMemory(userId, "鸡胸肉");
        MemoryManagementItemResponse edited = managementService.updateItem(userId, ingredientMemory.getId(),
                new MemoryItemUpdateRequest("DISLIKE", new BigDecimal("0.9200"), ingredientMemory.getVersion()));
        assertThat(edited.preference()).isEqualTo("DISLIKE");
        assertThat(edited.userModified()).isTrue();

        MemoryEpisode second = saveRecipeEpisode(userId, "management-save-2", "鸡胸肉汤");
        candidateService.extractAndPersist(userId, second.getId());
        consolidationService.consolidate(userId);

        MemoryItem afterConsolidation = ingredientMemory(userId, "鸡胸肉");
        assertThat(afterConsolidation.getPreference()).isEqualTo("DISLIKE");
        assertThat(afterConsolidation.getStrength()).isEqualByComparingTo("0.9200");
        assertThat(afterConsolidation.getUserModified()).isTrue();

        MemoryEpisode otherEpisode = saveRecipeEpisode(otherUserId, "management-other", "鸡胸肉饭");
        candidateService.extractAndPersist(otherUserId, otherEpisode.getId());
        consolidationService.consolidate(otherUserId);
        assertThat(managementService.getOverview(otherUserId).memories())
                .anyMatch(memory -> "鸡胸肉".equals(memory.entity()));
    }

    @Test
    void deletedMemoryStaysHiddenUntilNewEvidenceArrives() {
        Long userId = insertUser("13900000803", "删除记忆用户");
        MemoryEpisode first = saveRecipeEpisode(userId, "delete-save-1", "鸡胸肉沙拉");
        candidateService.extractAndPersist(userId, first.getId());
        consolidationService.consolidate(userId);
        MemoryItem item = ingredientMemory(userId, "鸡胸肉");

        managementService.deleteItem(userId, item.getId(), item.getVersion());
        assertThat(consolidationService.listOwnedItems(userId, 500))
                .noneMatch(value -> item.getId().equals(value.getId()));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM memory_candidates WHERE user_id = ? AND user_decision = 'REJECT'",
                Integer.class, userId)).isGreaterThan(0);

        MemoryEpisode freshEvidence = saveRecipeEpisode(userId, "delete-save-2", "鸡胸肉汤");
        candidateService.extractAndPersist(userId, freshEvidence.getId());
        consolidationService.consolidate(userId);
        assertThat(consolidationService.listOwnedItems(userId, 500))
                .anyMatch(value -> item.getId().equals(value.getId()));
    }

    @Test
    void disablingStopsEpisodeCaptureAndClearAllRemovesOnlyTheUsersMemoryGraph() {
        Long userId = insertUser("13900000804", "关闭个性化用户");
        Long otherUserId = insertUser("13900000805", "保留记忆用户");
        MemorySession session = sessionService.open(userId, new MemorySessionOpenCommand(
                null, "management-session", "RECOMMEND_RECIPE", "偏好测试", "{}", "[]", "[]", null, null));
        MemoryEpisode existing = saveRecipeEpisode(userId, "clear-save-user", "番茄鸡蛋面", session.getId());
        candidateService.extractAndPersist(userId, existing.getId());
        consolidationService.consolidate(userId);
        MemoryEpisode otherEpisode = saveRecipeEpisode(otherUserId, "clear-save-other", "清蒸鲈鱼");
        candidateService.extractAndPersist(otherUserId, otherEpisode.getId());
        consolidationService.consolidate(otherUserId);

        MemoryPersonalizationState disabled = personalizationService.update(userId,
                new MemoryPersonalizationRequest(false, 0));
        episodeRecorder.record(new MemoryBehaviorEpisodeEvent(
                userId, session.getId(), null, "RECIPE_SAVED", "RECIPE_RECORD", "suppressed-save",
                "suppressed-event", "suppressed-key", "不会记录的搜索", Map.of("recipeTitle", "牛肉面"),
                null, BigDecimal.ONE));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM memory_episodes WHERE user_id = ?", Integer.class, userId))
                .isEqualTo(1);

        MemoryClearResult cleared = managementService.clearAll(userId);
        assertThat(cleared.episodesDeleted()).isEqualTo(1);
        assertThat(cleared.candidatesDeleted()).isGreaterThan(0);
        assertThat(cleared.memoriesDeleted()).isGreaterThan(0);
        assertThat(cleared.sessionsDeleted()).isEqualTo(1);
        assertThat(personalizationService.getState(userId).enabled()).isFalse();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM memory_items WHERE user_id = ?", Integer.class, userId)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM memory_episodes WHERE user_id = ?", Integer.class, userId)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM memory_profiles WHERE user_id = ?", Integer.class, userId)).isZero();
        assertThat(consolidationService.listOwnedItems(otherUserId, 500)).isNotEmpty();
    }

    private MemoryItem ingredientMemory(Long userId, String entity) {
        return consolidationService.listOwnedItems(userId, 500).stream()
                .filter(item -> "INGREDIENT_PREFERENCE".equals(item.getMemoryType()))
                .filter(item -> entity.equals(item.getCanonicalEntity()))
                .findFirst().orElseThrow();
    }

    private MemoryEpisode saveRecipeEpisode(Long userId, String key, String title) {
        return saveRecipeEpisode(userId, key, title, null);
    }

    private MemoryEpisode saveRecipeEpisode(Long userId, String key, String title, Long sessionId) {
        return episodeService.record(userId, new MemoryEpisodeCommand(
                sessionId, null, "RECIPE_SAVED", "RECIPE_RECORD", key, key, key,
                "收藏菜谱：" + title,
                "{\"recipeTitle\":\"" + title + "\",\"ingredients\":[\"鸡胸肉\"]}", null, null
        )).episode();
    }

    private Long insertUser(String phone, String nickname) {
        jdbcTemplate.update("INSERT INTO users (phone, nickname) VALUES (?, ?)", phone, nickname);
        return jdbcTemplate.queryForObject("SELECT id FROM users WHERE phone = ?", Long.class, phone);
    }
}
