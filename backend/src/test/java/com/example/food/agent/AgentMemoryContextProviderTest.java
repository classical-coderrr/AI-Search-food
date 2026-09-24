package com.example.food.agent;

import com.example.food.memory.ContextBuilder;
import com.example.food.memory.MemoryQueryPlan;
import com.example.food.memory.MemoryRetrievalResult;
import com.example.food.memory.MemoryRetriever;
import com.example.food.memory.MemorySearchHit;
import com.example.food.memory.MemorySearchCommand;
import com.example.food.memory.MemorySession;
import com.example.food.memory.MemorySessionOpenCommand;
import com.example.food.memory.MemorySessionService;
import com.example.food.memory.MemorySessionUpdate;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentMemoryContextProviderTest {

    @Test
    void retrievesWithinAnOwnedAgentSessionAndBuildsBudgetedContext() {
        MemoryRetriever retriever = mock(MemoryRetriever.class);
        ContextBuilder contextBuilder = mock(ContextBuilder.class);
        MemorySessionService sessionService = mock(MemorySessionService.class);
        MemorySession session = new MemorySession();
        session.setId(55L);
        when(sessionService.open(eq(7L), any(MemorySessionOpenCommand.class))).thenReturn(session);

        MemoryRetrievalResult retrieval = retrieval(55L);
        when(retriever.search(eq(7L), any(MemorySearchCommand.class))).thenReturn(retrieval);
        ContextBuilder.ContextBuildResult built = new ContextBuilder.ContextBuildResult(
                "[PERSONAL_MEMORY]\n用户明确不吃香菜", Map.of(
                        "PERSONAL_MEMORY", List.of("用户明确不吃香菜"),
                        "STRUCTURED_PROFILE", List.of("{\"ingredientPreferences\":{"
                                + "\"liked\":[{\"entity\":\"鸡胸肉\",\"preference\":\"LIKE\","
                                + "\"temporalType\":\"RECENT\"}],"
                                + "\"disliked\":[{\"entity\":\"香菜\",\"preference\":\"DISLIKE\","
                                + "\"temporalType\":\"LONG_TERM\"}]}}")),
                List.of(31L, 32L), List.of(44L), List.of(), 38, 1800, false);
        when(contextBuilder.build(7L, 55L, retrieval, List.of(), List.of(), null)).thenReturn(built);

        AgentMemoryContextProvider provider = new AgentMemoryContextProvider(
                retriever, contextBuilder, sessionService, new ObjectMapper());
        AgentMemoryContextProvider.PreparedContext result = provider.prepare(
                7L, 42L, "run-1", "今晚推荐一道清淡鸡肉晚餐");

        assertThat(result.status()).isEqualTo("SUCCESS");
        assertThat(result.sessionId()).isEqualTo(55L);
        assertThat(result.promptContext()).contains("用户明确不吃香菜");
        assertThat(result.usedMemoryItemIds()).containsExactly(31L, 32L);
        assertThat(result.usedEpisodeIds()).containsExactly(44L);
        assertThat(result.contextSections()).contains("PERSONAL_MEMORY", "STRUCTURED_PROFILE");
        assertThat(result.traceSummaries()).containsExactly(
                "长期偏好：不喜欢香菜",
                "近期行为推断：喜欢鸡胸肉",
                "历史行为：收藏过清淡鸡肉晚餐",
                "食材偏好（近期行为推断）：喜欢鸡胸肉",
                "食材偏好（长期）：不喜欢香菜");
        assertThat(result.traceSummaries()).noneMatch(summary -> summary.contains("internal-payload"));

        verify(sessionService).open(eq(7L), any(MemorySessionOpenCommand.class));
        verify(retriever).search(eq(7L), eq(MemorySearchCommand.query("今晚推荐一道清淡鸡肉晚餐", 55L)));
        verify(sessionService, times(2)).touch(eq(7L), eq(55L), any(MemorySessionUpdate.class));
        verify(sessionService).close(7L, 55L);
    }

    @Test
    void degradesWithoutBlockingAgentWhenMemoryStoreIsUnavailable() {
        MemoryRetriever retriever = mock(MemoryRetriever.class);
        ContextBuilder contextBuilder = mock(ContextBuilder.class);
        MemorySessionService sessionService = mock(MemorySessionService.class);
        MemorySession session = new MemorySession();
        session.setId(55L);
        when(sessionService.open(eq(7L), any(MemorySessionOpenCommand.class))).thenReturn(session);
        when(retriever.search(eq(7L), any(MemorySearchCommand.class)))
                .thenThrow(new IllegalStateException("memory database unavailable"));

        AgentMemoryContextProvider provider = new AgentMemoryContextProvider(
                retriever, contextBuilder, sessionService, new ObjectMapper());
        AgentMemoryContextProvider.PreparedContext result = provider.prepare(
                7L, 42L, "run-2", "给我推荐晚餐");

        assertThat(result.status()).isEqualTo("DEGRADED");
        assertThat(result.promptContext()).isEmpty();
        assertThat(result.errorType()).isEqualTo("IllegalStateException");
    }

    private MemoryRetrievalResult retrieval(Long sessionId) {
        MemoryQueryPlan plan = new MemoryQueryPlan(
                "今晚推荐一道清淡鸡肉晚餐", "DINNER_MEMORY_RECALL", "今晚推荐一道清淡鸡肉晚餐 晚餐 晚饭",
                sessionId, List.of(), List.of(), List.of(), List.of(), null, null,
                List.of(), List.of(), BigDecimal.ZERO, BigDecimal.ZERO, 8);
        MemoryRetrievalResult.RetrievalTrace trace = new MemoryRetrievalResult.RetrievalTrace(
                7L, sessionId, java.time.LocalDateTime.now(), 2, 2,
                List.of(31L, 32L), List.of(44L, 45L));
        return new MemoryRetrievalResult(plan, List.of(
                new MemorySearchHit("MEMORY_ITEM", 31L, "INGREDIENT_PREFERENCE", "香菜",
                        "用户明确不吃香菜", "internal-payload", "DISLIKE", "LONG_TERM",
                        BigDecimal.valueOf(0.95), BigDecimal.valueOf(0.9),
                        java.time.LocalDateTime.now(), null),
                new MemorySearchHit("MEMORY_ITEM", 32L, "INGREDIENT_PREFERENCE", "鸡胸肉",
                        "收藏菜谱中的食材：鸡胸肉", "internal-payload", "LIKE", "RECENT",
                        BigDecimal.valueOf(0.45), BigDecimal.valueOf(0.4),
                        java.time.LocalDateTime.now(), null),
                new MemorySearchHit("EPISODE", 44L, "RECIPE_SAVED", "收藏过清淡鸡肉晚餐",
                        "用户收藏菜谱", "{\"eventId\":\"internal-payload\"}", null,
                        "TEMPORARY_CONTEXT", BigDecimal.valueOf(0.5), BigDecimal.valueOf(0.8),
                        java.time.LocalDateTime.now(), null),
                new MemorySearchHit("EPISODE", 45L, "RECIPE_SAVED", "未纳入上下文的记忆",
                        "未纳入上下文", "{}", null, "TEMPORARY_CONTEXT",
                        BigDecimal.valueOf(0.5), BigDecimal.valueOf(0.8),
                        java.time.LocalDateTime.now(), null)), trace);
    }
}
