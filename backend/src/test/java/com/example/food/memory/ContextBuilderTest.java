package com.example.food.memory;

import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ContextBuilderTest {

    @Test
    void keepsSessionMemoryProfileKnowledgeAndToolResultsInSeparateSections() {
        MemoryConsolidationService consolidation = mock(MemoryConsolidationService.class);
        MemorySessionService sessions = mock(MemorySessionService.class);
        MemoryRankingProperties properties = new MemoryRankingProperties();
        ContextBudgetManager budgetManager = new ContextBudgetManager();
        MemoryProfile profile = new MemoryProfile();
        profile.setUserId(9L);
        profile.setProfileJson("{\"ingredientPreferences\":{"
                + "\"liked\":[{\"id\":1,\"entity\":\"鸡胸肉\",\"canonicalGroupId\":\"INGREDIENT_CHICKEN_BREAST\",\"scope\":\"USER\","
                + "\"temporalType\":\"LONG_TERM\",\"lastSeenAt\":\"2025-01-01T10:00:00\",\"confidence\":0.91,\"evidenceCount\":3}],"
                + "\"disliked\":[{\"id\":2,\"entity\":\"香菜\",\"scope\":\"USER\","
                + "\"temporalType\":\"LONG_TERM\",\"confidence\":0.96,\"evidenceCount\":2},"
                + "{\"id\":3,\"entity\":\"鸡胸肉\",\"canonicalGroupId\":\"INGREDIENT_CHICKEN_BREAST\",\"scope\":\"USER\","
                + "\"temporalType\":\"RECENT\",\"lastSeenAt\":\"2026-09-20T10:00:00\",\"confidence\":0.70,\"evidenceCount\":1}]}}");
        when(consolidation.getOwnedProfile(9L)).thenReturn(profile);
        MemoryItem recentDislike = new MemoryItem();
        recentDislike.setId(3L);
        recentDislike.setSourceEpisodeIdsJson("[44]");
        when(consolidation.listOwnedItems(9L, 500)).thenReturn(List.of(recentDislike));
        MemorySession session = new MemorySession();
        session.setId(20L);
        session.setCurrentTask("训练后晚餐");
        session.setContextJson("20分钟内");
        MemorySearchHit hit = new MemorySearchHit("EPISODE", 31L, "RECIPE_SAVED", "鸡胸肉饭",
                "用户曾收藏鸡胸肉饭", "{\"rating\":5}", null, "TEMPORARY_CONTEXT",
                BigDecimal.valueOf(0.5), BigDecimal.valueOf(0.8), LocalDateTime.now(), null);
        when(sessions.findOwned(9L, 20L)).thenReturn(session);
        MemoryEpisode sourceEpisode = new MemoryEpisode();
        sourceEpisode.setId(44L);
        sourceEpisode.setEpisodeType("RECIPE_FEEDBACK");
        sourceEpisode.setSummary("训练后鸡胸肉晚餐");
        sourceEpisode.setPayloadJson("{\"scene\":\"POST_WORKOUT_DINNER\",\"mealType\":\"DINNER\"}");
        sourceEpisode.setOccurredAt(LocalDateTime.now());
        MemoryEpisodeService episodeService = mock(MemoryEpisodeService.class);
        when(episodeService.findOwnedByIds(9L, List.of(44L))).thenReturn(List.of(sourceEpisode));
        ObjectMapper objectMapper = new ObjectMapper();
        ContextBuilder builder = new ContextBuilder(consolidation, sessions, properties, budgetManager,
                objectMapper, new PersonalizedSkillService(objectMapper),
                new MemoryConflictResolver(objectMapper), episodeService);
        MemoryQueryPlan plan = new MemoryQueryPlan("今晚吃什么", "DINNER_MEMORY_RECALL",
                "今晚吃什么 晚餐", 20L, List.of(), List.of(), List.of("POST_WORKOUT"), List.of("DINNER"),
                null, null, List.of(), List.of(), null, null, 5);
        MemoryRetrievalResult retrieval = new MemoryRetrievalResult(plan, List.of(hit),
                new MemoryRetrievalResult.RetrievalTrace(9L, 20L, LocalDateTime.now(),
                        0, 1, List.of(), List.of(31L)));

        ContextBuilder.ContextBuildResult result = builder.build(9L, 20L, retrieval,
                List.of(new ContextBuilder.ExternalContext("KNOWLEDGE_DOC", 91L, "训练后补充蛋白质")),
                List.of(new ContextBuilder.ExternalContext("RECIPE_TOOL", 5L, "可选鸡胸肉饭")), null);

        assertThat(result.sections()).containsKeys("SESSION", "PERSONAL_MEMORY", "STRUCTURED_PROFILE",
                "PERSONALIZED_SKILL", "KNOWLEDGE_RAG", "TOOL_RESULTS", "MEMORY_CONFLICTS");
        assertThat(result.usedEpisodeIds()).containsExactly(31L);
        assertThat(result.knowledgeIds()).containsExactly(91L);
        assertThat(result.promptContext()).contains("[PERSONAL_MEMORY]", "[PERSONALIZED_SKILL: RECOMMEND_RECIPE]",
                "香菜", "鸡胸肉", "[KNOWLEDGE_RAG]", "[MEMORY_CONFLICTS]",
                "本轮暂按“不喜欢”处理", "训练后晚餐");
        assertThat(result.estimatedTokens()).isLessThanOrEqualTo(result.tokenBudget());
    }

    @Test
    void omitsProfileWhenTheTaskOnlyLooksUpHistoricalRecipes() {
        MemoryConsolidationService consolidation = mock(MemoryConsolidationService.class);
        MemorySessionService sessions = mock(MemorySessionService.class);
        MemoryProfile profile = new MemoryProfile();
        profile.setUserId(9L);
        profile.setProfileJson("{\"summary\":{\"memoryItemCount\":20},"
                + "\"ingredientPreferences\":{\"liked\":[\"鸡胸肉\"]}}");
        when(consolidation.getOwnedProfile(9L)).thenReturn(profile);
        ContextBuilder builder = new ContextBuilder(consolidation, sessions,
                new MemoryRankingProperties(), new ContextBudgetManager(), new ObjectMapper());
        MemoryQueryPlan plan = new MemoryQueryPlan("找昨天收藏的菜谱", "GENERAL_MEMORY_RECALL",
                "找昨天收藏的菜谱", null, List.of(), List.of(), List.of(), List.of(),
                null, null, List.of(), List.of(), null, null, 5);
        MemoryRetrievalResult retrieval = new MemoryRetrievalResult(plan, List.of(),
                new MemoryRetrievalResult.RetrievalTrace(9L, null, LocalDateTime.now(),
                        0, 0, List.of(), List.of()));

        ContextBuilder.ContextBuildResult result = builder.build(9L, null, retrieval,
                List.of(), List.of(), 200);

        assertThat(result.sections()).doesNotContainKey("STRUCTURED_PROFILE");
        assertThat(result.sections()).doesNotContainKey("PERSONALIZED_SKILL");
    }

    @Test
    void suppliesEpisodeTimesAndOrdersRetrievedEventsChronologically() {
        MemoryConsolidationService consolidation = mock(MemoryConsolidationService.class);
        MemorySessionService sessions = mock(MemorySessionService.class);
        ContextBuilder builder = new ContextBuilder(consolidation, sessions,
                new MemoryRankingProperties(), new ContextBudgetManager(), new ObjectMapper());
        MemorySearchHit dislike = new MemorySearchHit("EPISODE", 42L, "RECIPE_FEEDBACK", "反馈",
                "REACTION", "{\"action\":\"REACTION\",\"reaction\":\"DISLIKE\"}", null,
                "TEMPORARY_CONTEXT", BigDecimal.valueOf(0.8), BigDecimal.valueOf(0.7),
                LocalDateTime.parse("2026-09-24T16:38:42"), null);
        MemorySearchHit cleared = new MemorySearchHit("EPISODE", 41L, "RECIPE_FEEDBACK", "反馈",
                "REACTION_CLEARED", "{\"action\":\"REACTION_CLEARED\"}", null,
                "TEMPORARY_CONTEXT", BigDecimal.valueOf(0.8), BigDecimal.valueOf(0.7),
                LocalDateTime.parse("2026-09-24T16:38:36"), null);
        MemorySearchHit review = new MemorySearchHit("EPISODE", 46L, "FINISHED_DISH_REVIEW", "成品评价",
                "REVIEW", "{\"overallScore\":85}", null, "TEMPORARY_CONTEXT",
                BigDecimal.valueOf(0.8), BigDecimal.valueOf(0.7),
                LocalDateTime.parse("2026-09-24T17:18:20"), null);
        MemoryQueryPlan plan = new MemoryQueryPlan("按时间回忆这道菜的反馈", "GENERAL_MEMORY_RECALL",
                "按时间回忆", null, List.of(), List.of(), List.of(), List.of(), null, null,
                List.of(), List.of(), null, null, 5);
        MemoryRetrievalResult retrieval = new MemoryRetrievalResult(plan, List.of(dislike, cleared, review),
                new MemoryRetrievalResult.RetrievalTrace(9L, null, LocalDateTime.now(),
                        0, 3, List.of(), List.of(42L, 41L, 46L)));

        ContextBuilder.ContextBuildResult result = builder.build(9L, null, retrieval,
                List.of(), List.of(), 3000);

        String personalMemory = String.join("\n", result.sections().get("PERSONAL_MEMORY"));
        int clearedIndex = personalMemory.indexOf("事件发生时间：2026-09-24T16:38:36");
        int dislikeIndex = personalMemory.indexOf("事件发生时间：2026-09-24T16:38:42");
        int reviewIndex = personalMemory.indexOf("事件发生时间：2026-09-24T17:18:20");
        assertThat(clearedIndex).isGreaterThanOrEqualTo(0).isLessThan(dislikeIndex);
        assertThat(dislikeIndex).isLessThan(reviewIndex);
    }
}
