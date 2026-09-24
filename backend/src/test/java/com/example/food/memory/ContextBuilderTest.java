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
        profile.setProfileJson("{\"ingredientPreferences\":{\"liked\":[\"鸡胸肉\"]}}");
        when(consolidation.getOwnedProfile(9L)).thenReturn(profile);
        MemorySession session = new MemorySession();
        session.setId(20L);
        session.setCurrentTask("训练后晚餐");
        session.setContextJson("20分钟内");
        when(sessions.findOwned(9L, 20L)).thenReturn(session);
        ContextBuilder builder = new ContextBuilder(consolidation, sessions, properties, budgetManager,
                new ObjectMapper());

        MemorySearchHit hit = new MemorySearchHit("EPISODE", 31L, "RECIPE_SAVED", "鸡胸肉饭",
                "用户曾收藏鸡胸肉饭", "{\"rating\":5}", null, "TEMPORARY_CONTEXT",
                BigDecimal.valueOf(0.5), BigDecimal.valueOf(0.8), LocalDateTime.now(), null);
        MemoryQueryPlan plan = new MemoryQueryPlan("今晚吃什么", "DINNER_MEMORY_RECALL",
                "今晚吃什么 晚餐", 20L, List.of(), List.of(), List.of(), List.of(),
                null, null, List.of(), List.of(), null, null, 5);
        MemoryRetrievalResult retrieval = new MemoryRetrievalResult(plan, List.of(hit),
                new MemoryRetrievalResult.RetrievalTrace(9L, 20L, LocalDateTime.now(),
                        0, 1, List.of(), List.of(31L)));

        ContextBuilder.ContextBuildResult result = builder.build(9L, 20L, retrieval,
                List.of(new ContextBuilder.ExternalContext("KNOWLEDGE_DOC", 91L, "训练后补充蛋白质")),
                List.of(new ContextBuilder.ExternalContext("RECIPE_TOOL", 5L, "可选鸡胸肉饭")), null);

        assertThat(result.sections()).containsKeys("SESSION", "PERSONAL_MEMORY", "STRUCTURED_PROFILE",
                "KNOWLEDGE_RAG", "TOOL_RESULTS");
        assertThat(result.usedEpisodeIds()).containsExactly(31L);
        assertThat(result.knowledgeIds()).containsExactly(91L);
        assertThat(result.promptContext()).contains("[PERSONAL_MEMORY]", "[KNOWLEDGE_RAG]");
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
    }
}
