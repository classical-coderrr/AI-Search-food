package com.example.food.memory;

import com.example.food.ai.qwen.QwenRecipeClient;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MemoryChatLearningIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired MemoryEpisodeService episodes;
    @Autowired MemoryChatLearningService chat;
    @Autowired MemoryCandidateService candidates;
    @Autowired MemoryConsolidationService consolidation;
    @Autowired MemoryManagementService management;
    @Autowired MemoryPersonalizationService personalization;
    @Autowired MemoryProcessingJobService jobs;
    @Autowired RecipeMemoryPersonalization recipeMemory;
    @Autowired MemoryEvidenceReconciliationWorker evidenceWorker;
    @Autowired ObjectMapper mapper;
    @MockBean QwenRecipeClient model;

    @Test
    void expiredChatInterestIsRecalculatedAfterPersonalizationWasToggled() throws Exception {
        Long user = user("13900009107");
        for (int day = 1; day <= 6; day++) {
            String dish = day % 2 == 0 ? "鸡胸肉汤" : "鸡胸肉饭";
            String raw = "今天想吃" + dish;
            var fact = new java.util.LinkedHashMap<>(MemoryChatLearningServiceTest.fact(
                    "FOOD_REQUEST", "RECIPE_PREFERENCE", dish, "WANT", "SESSION", raw));
            fact.put("ingredients", List.of("鸡胸肉"));
            when(model.extractChatMemory(anyString())).thenReturn(mapper.writeValueAsString(Map.of("facts", List.of(fact))));
            var episode = message(user, raw + "，本次编号" + day, LocalDateTime.now().minusDays(day));
            chat.prepare(user, episode.getId());
            candidates.extractAndPersist(user, episode.getId());
        }
        consolidation.consolidate(user);
        assertThat(consolidation.listOwnedItems(user, 20)).anyMatch(item -> "INGREDIENT_PREFERENCE".equals(item.getMemoryType()));
        var disabled = personalization.update(user, new MemoryPersonalizationRequest(false, 0));
        personalization.update(user, new MemoryPersonalizationRequest(true, disabled.version()));
        jdbc.update("UPDATE memory_episodes SET occurred_at = ? WHERE user_id = ?", LocalDateTime.now().minusDays(91), user);
        jdbc.update("UPDATE memory_profiles SET rebuilt_at = ? WHERE user_id = ?", LocalDateTime.now().minusDays(1), user);
        evidenceWorker.rebuildLegacyEvidence();
        assertThat(consolidation.listOwnedItems(user, 20)).noneMatch(item -> "INGREDIENT_PREFERENCE".equals(item.getMemoryType()));
    }

    @Test
    void laterBehaviorCannotReactivatePreferenceRejectedByExplicitChat() throws Exception {
        Long user = user("13900009106");
        for (int day = 1; day <= 3; day++) {
            var episode = episodes.record(user, new MemoryEpisodeCommand(null, null, "RECIPE_SAVED", "TEST",
                    "beef-" + day, "beef-" + day, "beef-" + day, "保存牛肉菜谱",
                    mapper.writeValueAsString(Map.of("title", "鸡胸肉菜式" + day, "ingredients", List.of("鸡胸肉"))),
                    LocalDateTime.now().minusDays(day), null)).episode();
            candidates.extractAndPersist(user, episode.getId());
        }
        consolidation.consolidate(user);
        assertThat(consolidation.listOwnedItems(user, 20)).anyMatch(item -> "INGREDIENT_PREFERENCE".equals(item.getMemoryType()));
        var correction = message(user, "我现在不喜欢鸡胸肉了", LocalDateTime.now());
        when(model.extractChatMemory(anyString())).thenReturn(result("鸡胸肉", "DISLIKE", "我现在不喜欢鸡胸肉了"));
        chat.prepare(user, correction.getId());
        candidates.extractAndPersist(user, correction.getId());
        consolidation.consolidate(user);
        var later = episodes.record(user, new MemoryEpisodeCommand(null, null, "RECIPE_SAVED", "TEST",
                "beef-later", "beef-later", "beef-later", "替家人保存菜谱",
                mapper.writeValueAsString(Map.of("title", "鸡胸肉菜式4", "ingredients", List.of("鸡胸肉"))),
                LocalDateTime.now().plusSeconds(1), null)).episode();
        candidates.extractAndPersist(user, later.getId());
        consolidation.consolidate(user);
        assertThat(consolidation.listOwnedItems(user, 20).stream()
                .filter(item -> "INGREDIENT_PREFERENCE".equals(item.getMemoryType())).toList()).singleElement()
                .satisfies(item -> assertThat(item.getPreference()).isEqualTo("DISLIKE"));
        assertThat(recipeMemory.context(user, "今晚推荐吃什么")).contains("鸡胸肉");
    }

    @Test
    void learnsWithoutSavingCachesModelResultsAndFeedsActualRecipeDefaults() throws Exception {
        Long user = user("13900009101");
        var episode = message(user, "我一直喜欢牛肉", LocalDateTime.now().minusMinutes(1));
        when(model.extractChatMemory(anyString())).thenReturn(result("牛肉", "LIKE", "我一直喜欢牛肉"));
        chat.prepare(user, episode.getId());
        chat.prepare(user, episode.getId());
        candidates.extractAndPersist(user, episode.getId());
        candidates.extractAndPersist(user, episode.getId());
        consolidation.consolidate(user);
        var items = consolidation.listOwnedItems(user, 20);
        assertThat(items).singleElement().satisfies(item -> {
            assertThat(item.getCanonicalEntity()).isEqualTo("牛肉");
            assertThat(item.getTemporalType()).isEqualTo("LONG_TERM");
            assertThat(item.getPreference()).isEqualTo("LIKE");
            assertThat(item.getEvidenceCount()).isEqualTo(1);
        });
        assertThat(recipeMemory.context(user, "今晚推荐吃什么")).contains("牛肉", "本轮明确要求");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recipe_records WHERE user_id = ?", Integer.class, user)).isZero();
        verify(model, times(1)).extractChatMemory(anyString());
        jobs.enqueue(user, episode.getId());
        jobs.enqueue(user, episode.getId());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM memory_processing_jobs WHERE user_id = ?", Integer.class, user)).isEqualTo(1);
    }

    @Test
    void latestExplicitCorrectionWinsEvenWhenOlderTaskFinishesLater() throws Exception {
        Long user = user("13900009102");
        var older = message(user, "我一直喜欢牛肉", LocalDateTime.now().minusDays(1));
        var newer = message(user, "我现在不喜欢牛肉了", LocalDateTime.now());
        when(model.extractChatMemory(anyString())).thenAnswer(call -> call.getArgument(0, String.class).contains("现在")
                ? result("牛肉", "DISLIKE", "我现在不喜欢牛肉了") : result("牛肉", "LIKE", "我一直喜欢牛肉"));
        chat.prepare(user, newer.getId());
        candidates.extractAndPersist(user, newer.getId());
        consolidation.consolidate(user);
        chat.prepare(user, older.getId());
        candidates.extractAndPersist(user, older.getId());
        consolidation.consolidate(user);
        assertThat(consolidation.listOwnedItems(user, 20)).singleElement()
                .satisfies(item -> assertThat(item.getPreference()).isEqualTo("DISLIKE"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM memory_evidence WHERE user_id = ?", Integer.class, user)).isEqualTo(2);
    }

    @Test
    void clearingMemoryWhileModelRunsCannotRestoreItsResult() throws Exception {
        Long user = user("13900009103");
        var episode = message(user, "我一直喜欢牛肉", LocalDateTime.now());
        when(model.extractChatMemory(anyString())).thenAnswer(call -> {
            management.clearAll(user);
            return result("牛肉", "LIKE", "我一直喜欢牛肉");
        });
        chat.prepare(user, episode.getId());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM memory_episodes WHERE user_id = ?", Integer.class, user)).isZero();
        assertThat(consolidation.listOwnedItems(user, 20)).isEmpty();
        assertThat(personalization.getState(user).version()).isEqualTo(1);
    }

    @Test
    void disablingThenReenablingInvalidatesAlreadyRunningLearning() throws Exception {
        Long user = user("13900009104");
        var episode = message(user, "我一直喜欢牛肉", LocalDateTime.now());
        when(model.extractChatMemory(anyString())).thenAnswer(call -> {
            var disabled = personalization.update(user, new MemoryPersonalizationRequest(false, 0));
            personalization.update(user, new MemoryPersonalizationRequest(true, disabled.version()));
            return result("牛肉", "LIKE", "我一直喜欢牛肉");
        });
        chat.prepare(user, episode.getId());
        candidates.extractAndPersist(user, episode.getId());
        assertThat(candidates.listOwned(user, null, null, 20)).isEmpty();
        assertThat(episodes.findOwned(user, episode.getId()).getPayloadJson()).doesNotContain("chatFacts");
    }

    @Test
    void failedExtractionDoesNotCreatePreferenceAndCanBeRetried() throws Exception {
        Long user = user("13900009105");
        var episode = message(user, "我一直喜欢牛肉", LocalDateTime.now());
        when(model.extractChatMemory(anyString())).thenThrow(new IllegalStateException("暂时不可用"))
                .thenReturn(result("牛肉", "LIKE", "我一直喜欢牛肉"));
        assertThatThrownBy(() -> chat.prepare(user, episode.getId())).hasMessage("暂时不可用");
        assertThat(candidates.listOwned(user, null, null, 20)).isEmpty();
        chat.prepare(user, episode.getId());
        candidates.extractAndPersist(user, episode.getId());
        assertThat(candidates.listOwned(user, null, null, 20)).hasSize(1);
    }

    private MemoryEpisode message(Long user, String raw, LocalDateTime at) throws Exception {
        return episodes.record(user, new MemoryEpisodeCommand(null, null, "USER_CHAT", "AGENT_USER_MESSAGE",
                raw, raw, raw, "用户聊天", mapper.writeValueAsString(Map.of("message", raw, "context", List.of(),
                "personalizationVersion", personalization.getState(user).version())), at, null)).episode();
    }

    private String result(String entity, String preference, String quote) throws Exception {
        return mapper.writeValueAsString(Map.of("facts", List.of(MemoryChatLearningServiceTest.fact(
                "PREFERENCE", "INGREDIENT_PREFERENCE", entity, preference, "LONG_TERM", quote))));
    }

    private Long user(String phone) {
        jdbc.update("INSERT INTO users(phone, nickname, role, enabled) VALUES (?, '聊天记忆测试', 'USER', 1)", phone);
        return jdbc.queryForObject("SELECT id FROM users WHERE phone = ?", Long.class, phone);
    }
}
