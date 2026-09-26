package com.example.food.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryConflictResolverTest {

    private final MemoryConflictResolver resolver = new MemoryConflictResolver(new ObjectMapper());

    @Test
    void prioritizesTheOppositePreferenceWhoseEpisodeMatchesCurrentSceneAndKeepsBoth() {
        String profile = profile("鸡胸肉", "鸡胸", "2026-01-10T10:00:00", "2026-09-20T10:00:00",
                "LONG_TERM", "RECENT", "INGREDIENT_CHICKEN_BREAST");
        List<MemoryItem> items = List.of(item(1L, "[101]"), item(2L, "[102]"));
        List<MemorySearchHit> episodes = List.of(
                episode(101L, "{\"scene\":\"WEEKDAY_LUNCH\",\"mealType\":\"LUNCH\"}",
                        LocalDateTime.parse("2026-01-10T10:00:00")),
                episode(102L, "{\"scene\":\"POST_WORKOUT_DINNER\",\"mealType\":\"DINNER\"}",
                        LocalDateTime.parse("2026-09-19T18:00:00")));

        List<String> explanations = resolver.explain(profile, plan(List.of("POST_WORKOUT"), List.of("DINNER")),
                episodes, items);

        assertThat(explanations).hasSize(1);
        assertThat(explanations.get(0))
                .contains("鸡胸肉", "长期记录倾向喜欢", "近期/临时记录倾向不喜欢")
                .contains("训练后晚餐", "本轮暂按“不喜欢”");
        assertThat(explanations.get(0)).contains("不同时间或情境下的记忆保留");
    }

    @Test
    void letsMatchingSceneOutweighAnOlderTimestampWithoutReplacingLongTermPreference() {
        String profile = profile("鸡胸肉", "鸡胸肉", "2025-01-01T10:00:00", "2026-09-20T10:00:00",
                "LONG_TERM", "RECENT", "INGREDIENT_CHICKEN_BREAST");
        List<MemorySearchHit> episodes = List.of(
                episode(101L, "{\"scene\":\"POST_WORKOUT_DINNER\"}",
                        LocalDateTime.parse("2025-01-01T10:00:00")),
                episode(102L, "{\"scene\":\"WEEKDAY_LUNCH\"}",
                        LocalDateTime.parse("2026-09-19T10:00:00")));

        String explanation = resolver.explain(profile, plan(List.of("POST_WORKOUT"), List.of()), episodes,
                List.of(item(1L, "[101]"), item(2L, "[102]"))).get(0);

        assertThat(explanation).contains("本轮暂按“喜欢”处理", "长期记录倾向喜欢", "近期/临时记录倾向不喜欢");
    }

    @Test
    void explicitPreferenceOutweighsANewerBehaviorInference() {
        String profile = profile("鸡胸肉", "鸡胸肉", "2026-09-20T10:00:00", "2026-01-01T10:00:00",
                "RECENT", "LONG_TERM", "INGREDIENT_CHICKEN_BREAST");
        MemoryItem inferredLike = item(1L, "[101]");
        inferredLike.setSourceCandidateIdsJson("[201]");
        MemoryItem explicitDislike = item(2L, "[102]");
        explicitDislike.setSourceCandidateIdsJson("[202]");
        MemoryCandidate inferred = candidate(201L, "IMPLICIT_BEHAVIOR");
        MemoryCandidate explicit = candidate(202L, "EXPLICIT");
        List<MemorySearchHit> episodes = List.of(
                episode(101L, "{\"scene\":\"POST_WORKOUT_DINNER\"}",
                        LocalDateTime.parse("2026-09-20T10:00:00")),
                episode(102L, "{\"scene\":\"WEEKDAY_LUNCH\"}",
                        LocalDateTime.parse("2026-01-01T10:00:00")));

        String explanation = resolver.explain(profile, plan(List.of("POST_WORKOUT"), List.of()), episodes,
                List.of(inferredLike, explicitDislike), List.of(inferred, explicit)).get(0);

        assertThat(explanation).contains("本轮暂按“不喜欢”处理", "用户明确表达/确认",
                "行为推断", "不视为已被永久覆盖");
    }

    @Test
    void usesMoreRecentMemoryWhenNoMatchingSceneEvidenceIsAvailable() {
        String profile = profile("鸡胸肉", "鸡胸肉", "2026-01-01T10:00:00", "2026-09-20T10:00:00",
                "LONG_TERM", "RECENT", "INGREDIENT_CHICKEN_BREAST");

        String explanation = resolver.explain(profile, plan(List.of("POST_WORKOUT"), List.of()),
                List.of(), List.of(item(1L, "[101]"), item(2L, "[102]"))).get(0);

        assertThat(explanation).contains("未检索到可核实的历史场景/餐次证据", "记忆最近更新于 2026-09-20",
                "本轮暂按“不喜欢”");
    }

    @Test
    void usesAndExplainsEventTimeWithinTheSameDay() {
        String profile = profile("番茄黄瓜炒鸡丁", "番茄黄瓜炒鸡丁",
                "2026-09-24T17:18:20", "2026-09-24T16:38:42",
                "LONG_TERM", "LONG_TERM", "RECIPE_TEST_DISH");
        List<MemorySearchHit> episodes = List.of(
                episode(101L, "{}", LocalDateTime.parse("2026-09-24T16:38:42")),
                episode(102L, "{}", LocalDateTime.parse("2026-09-24T17:18:20")));

        String explanation = resolver.explain(profile, plan(List.of(), List.of()), episodes,
                List.of(item(1L, "[102]"), item(2L, "[101]"))).get(0);

        assertThat(explanation).contains("关联事件先后顺序：不喜欢证据发生于 2026-09-24 16:38:42；"
                        + "喜欢证据发生于 2026-09-24 17:18:20",
                "本轮暂按“喜欢”处理", "另一方向仍作为不同时间或情境下的记忆保留");
    }

    @Test
    void doesNotChooseASideWhenTimeAndSceneEvidenceCannotDistinguishTheMemories() {
        String profile = profile("鸡胸肉", "鸡胸肉", "2026-09-20T10:00:00", "2026-09-20T10:00:00",
                "LONG_TERM", "LONG_TERM", "INGREDIENT_CHICKEN_BREAST");

        String explanation = resolver.explain(profile, plan(List.of("POST_WORKOUT"), List.of()),
                List.of(), List.of(item(1L, "[101]"), item(2L, "[102]"))).get(0);

        assertThat(explanation).contains("现有证据无法可靠判断", "必要时向用户确认");
    }

    @Test
    void returnsNoConflictForDifferentCanonicalIngredients() {
        String profile = """
                {"ingredientPreferences":{
                  "liked":[{"id":1,"entity":"鸡胸肉","canonicalGroupId":"INGREDIENT_CHICKEN_BREAST","preference":"LIKE"}],
                  "disliked":[{"id":2,"entity":"香菜","canonicalGroupId":"INGREDIENT_CILANTRO","preference":"DISLIKE"}]}}
                """;

        assertThat(resolver.hasIngredientConflicts(profile)).isFalse();
        assertThat(resolver.explain(profile, plan(List.of(), List.of()), List.of(), List.of())).isEmpty();
    }

    @Test
    void fallsBackToAnExactIngredientNameWhenOneLegacyPreferenceHasNoCanonicalId() {
        String profile = """
                {"ingredientPreferences":{
                  "liked":[{"id":1,"entity":"鸡胸肉","canonicalGroupId":"INGREDIENT_CHICKEN_BREAST","preference":"LIKE"}],
                  "disliked":[{"id":2,"entity":"鸡胸肉","preference":"DISLIKE"}]}}
                """;

        assertThat(resolver.hasIngredientConflicts(profile)).isTrue();
    }

    @Test
    void matchesShortChineseIngredientAliasesInAnExplicitQuery() {
        String profile = profile("鸡胸肉", "鸡胸", "2026-01-10T10:00:00", "2026-09-20T10:00:00",
                "LONG_TERM", "RECENT", "INGREDIENT_CHICKEN_BREAST");
        MemoryQueryPlan query = new MemoryQueryPlan("鸡胸怎么做", "GENERAL_MEMORY_RECALL", "鸡胸", null,
                List.of(), List.of(), List.of(), List.of(), null, null, List.of("鸡胸"), List.of(),
                null, null, 5);

        List<String> explanations = resolver.explain(profile, query, List.of(), List.of());

        assertThat(explanations).hasSize(1);
        assertThat(explanations.get(0)).contains("鸡胸肉");
    }

    @Test
    void onlyExplainsConflictsRelevantToAnExplicitIngredientQuery() {
        String profile = """
                {"ingredientPreferences":{
                  "liked":[
                    {"id":1,"entity":"鸡胸肉","canonicalGroupId":"INGREDIENT_CHICKEN_BREAST","preference":"LIKE"},
                    {"id":3,"entity":"香菜","canonicalGroupId":"INGREDIENT_CILANTRO","preference":"LIKE"}],
                  "disliked":[
                    {"id":2,"entity":"鸡胸肉","canonicalGroupId":"INGREDIENT_CHICKEN_BREAST","preference":"DISLIKE"},
                    {"id":4,"entity":"香菜","canonicalGroupId":"INGREDIENT_CILANTRO","preference":"DISLIKE"}]}}
                """;
        MemoryQueryPlan cilantroQuery = new MemoryQueryPlan("香菜要不要放", "GENERAL_MEMORY_RECALL",
                "香菜", null, List.of(), List.of(), List.of(), List.of(), null, null,
                List.of("香菜"), List.of(), null, null, 5);

        List<String> explanations = resolver.explain(profile, cilantroQuery, List.of(), List.of());

        assertThat(explanations).hasSize(1);
        assertThat(explanations.get(0)).contains("香菜").doesNotContain("鸡胸肉");
    }

    private String profile(String likedEntity, String dislikedEntity, String likedAt, String dislikedAt,
                           String likedTemporal, String dislikedTemporal, String canonicalGroupId) {
        return """
                {"ingredientPreferences":{
                  "liked":[{"id":1,"entity":"%s","canonicalGroupId":"%s","preference":"LIKE","scope":"USER","temporalType":"%s","lastSeenAt":"%s"}],
                  "disliked":[{"id":2,"entity":"%s","canonicalGroupId":"%s","preference":"DISLIKE","scope":"USER","temporalType":"%s","lastSeenAt":"%s"}]}}
                """.formatted(likedEntity, canonicalGroupId, likedTemporal, likedAt,
                dislikedEntity, canonicalGroupId, dislikedTemporal, dislikedAt);
    }

    private MemoryItem item(Long id, String episodeIds) {
        MemoryItem item = new MemoryItem();
        item.setId(id);
        item.setSourceEpisodeIdsJson(episodeIds);
        return item;
    }

    private MemoryCandidate candidate(Long id, String sourceType) {
        MemoryCandidate candidate = new MemoryCandidate();
        candidate.setId(id);
        candidate.setSourceType(sourceType);
        return candidate;
    }

    private MemorySearchHit episode(Long id, String payload, LocalDateTime occurredAt) {
        return new MemorySearchHit("EPISODE", id, "RECIPE_FEEDBACK", "雞胸肉反馈", "feedback", payload,
                null, "TEMPORARY_CONTEXT", BigDecimal.valueOf(0.8), BigDecimal.valueOf(0.7), occurredAt, null);
    }

    private MemoryQueryPlan plan(List<String> scenes, List<String> mealTypes) {
        return new MemoryQueryPlan("健身后吃什么", "POST_WORKOUT_RECIPE_RECALL", "健身后", null,
                List.of(), List.of(), scenes, mealTypes, null, null, List.of(), List.of(),
                null, null, 5);
    }
}
