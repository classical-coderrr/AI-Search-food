package com.example.food.memory;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MemoryRetrieverVectorRecallTest {

    @Test
    void addsSemanticOnlyCandidatesUsingUserScopedMetadataLookupAndReranksThem() {
        MemoryItemMapper items = mock(MemoryItemMapper.class);
        MemoryEpisodeMapper episodes = mock(MemoryEpisodeMapper.class);
        MemoryQueryPlanner planner = mock(MemoryQueryPlanner.class);
        MemorySessionService sessions = mock(MemorySessionService.class);
        MemoryEmbeddingIndexService vectors = mock(MemoryEmbeddingIndexService.class);
        MemoryQueryPlan plan = new MemoryQueryPlan("我该选什么口味", "PREFERENCE_QUERY", "我该选什么口味",
                null, List.of(), List.of(), List.of(), List.of(), null, null, List.of(), List.of(),
                null, null, 5);
        when(planner.plan(31L, MemorySearchCommand.query("我该选什么口味"))).thenReturn(plan);
        when(items.searchActiveForRetrieval(31L, plan.memoryTypes(), plan.ingredients(), plan.dietGoals(),
                null, null, null, null, 200)).thenReturn(List.of());
        when(episodes.searchOwnedForRetrieval(31L, plan.episodeTypes(), plan.scenes(), plan.mealTypes(),
                plan.ingredients(), plan.dietGoals(), null, null, null, 200)).thenReturn(List.of());
        when(vectors.search(31L, plan.rewrittenQuery(), 200))
                .thenReturn(List.of(new MemoryVectorMatch("MEMORY_ITEM", 91L, 0.94)));
        MemoryItem item = new MemoryItem();
        item.setId(91L);
        item.setUserId(31L);
        item.setVersion(2);
        item.setMemoryType("INGREDIENT_PREFERENCE");
        item.setCanonicalEntity("鸡胸肉");
        item.setPreference("LIKE");
        item.setTemporalType("EXPLICIT_PREFERENCE");
        item.setConfidence(new BigDecimal("0.9"));
        item.setImportance(new BigDecimal("0.8"));
        when(items.findActiveOwnedByIds(31L, List.of(91L))).thenReturn(List.of(item));
        MemoryRankingProperties ranking = new MemoryRankingProperties();
        MemoryRetriever retriever = new MemoryRetriever(items, episodes, planner,
                new MemoryReranker(ranking), ranking, new MemoryEmbeddingProperties(), sessions,
                vectors, java.time.Clock.systemUTC());

        MemoryRetrievalResult result = retriever.search(31L, MemorySearchCommand.query("我该选什么口味"));

        assertThat(result.hits()).extracting(MemorySearchHit::id).containsExactly(91L);
        assertThat(result.hits().get(0).scores().semantic()).isGreaterThan(0.6);
        assertThat(result.trace().selectedMemoryItemIds()).containsExactly(91L);
        verify(items).findActiveOwnedByIds(31L, List.of(91L));
    }

    @Test
    void appliesMetadataFiltersToSemanticOnlyEpisodeCandidates() {
        MemoryItemMapper items = mock(MemoryItemMapper.class);
        MemoryEpisodeMapper episodes = mock(MemoryEpisodeMapper.class);
        MemoryQueryPlanner planner = mock(MemoryQueryPlanner.class);
        MemorySessionService sessions = mock(MemorySessionService.class);
        MemoryEmbeddingIndexService vectors = mock(MemoryEmbeddingIndexService.class);
        MemoryQueryPlan plan = new MemoryQueryPlan("推荐训练后晚餐", "POST_WORKOUT", "训练后晚餐",
                null, List.of(), List.of("RECIPE_SAVED"), List.of("POST_WORKOUT"), List.of("DINNER"),
                null, null, List.of("鸡胸肉"), List.of(), null, null, 5);
        MemorySearchCommand command = MemorySearchCommand.query("推荐训练后晚餐");
        when(planner.plan(31L, command)).thenReturn(plan);
        when(items.searchActiveForRetrieval(31L, plan.memoryTypes(), plan.ingredients(), plan.dietGoals(),
                null, null, null, null, 200)).thenReturn(List.of());
        when(episodes.searchOwnedForRetrieval(31L, plan.episodeTypes(), plan.scenes(), plan.mealTypes(),
                plan.ingredients(), plan.dietGoals(), null, null, null, 200)).thenReturn(List.of());
        when(vectors.search(31L, plan.rewrittenQuery(), 200)).thenReturn(List.of(
                new MemoryVectorMatch("EPISODE", 15L, 0.92), new MemoryVectorMatch("EPISODE", 16L, 0.91)));
        MemoryEpisode relevant = episode(15L, "RECIPE_SAVED", "POST_WORKOUT_DINNER",
                "{\"scene\":\"POST_WORKOUT\",\"mealType\":\"DINNER\",\"ingredients\":[\"鸡胸肉\"]}");
        MemoryEpisode unrelated = episode(16L, "RECIPE_SAVED", "LUNCH",
                "{\"scene\":\"WEEKDAY_LUNCH\",\"mealType\":\"LUNCH\",\"ingredients\":[\"番茄\"]}");
        when(episodes.findOwnedByIds(31L, List.of(15L, 16L))).thenReturn(List.of(relevant, unrelated));
        MemoryRankingProperties ranking = new MemoryRankingProperties();
        MemoryRetriever retriever = new MemoryRetriever(items, episodes, planner,
                new MemoryReranker(ranking), ranking, new MemoryEmbeddingProperties(), sessions,
                vectors, java.time.Clock.systemUTC());

        MemoryRetrievalResult result = retriever.search(31L, command);

        assertThat(result.hits()).extracting(MemorySearchHit::id).containsExactly(15L);
    }

    private MemoryEpisode episode(Long id, String type, String summary, String payload) {
        MemoryEpisode episode = new MemoryEpisode();
        episode.setId(id);
        episode.setUserId(31L);
        episode.setEpisodeType(type);
        episode.setSummary(summary);
        episode.setPayloadJson(payload);
        episode.setStatus("RAW");
        episode.setImportance(new BigDecimal("0.8"));
        episode.setOccurredAt(java.time.LocalDateTime.now());
        return episode;
    }
}
