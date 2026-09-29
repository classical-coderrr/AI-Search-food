package com.example.food.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MemoryBehaviorPatternExtractorTest {

    private static final Long USER_ID = 7L;
    private final MemoryEpisodeService episodeService = mock(MemoryEpisodeService.class);
    private final TagNormalizationService tagNormalizationService = mock(TagNormalizationService.class);
    private final MemoryBehaviorPatternExtractor extractor = new MemoryBehaviorPatternExtractor(
            episodeService,
            tagNormalizationService,
            new ObjectMapper(),
            Clock.fixed(Instant.parse("2026-09-29T04:00:00Z"), ZoneId.of("Asia/Shanghai"))
    );

    @Test
    void doesNotInferIngredientPreferenceFromOneSavedRecipe() {
        MemoryEpisode saved = episode(1L, USER_ID, 0, "RECIPE_SAVED", "鸡胸肉沙拉", "recipe-1",
                "{\"recipeId\":\"recipe-1\",\"title\":\"鸡胸肉沙拉\",\"ingredients\":[\"鸡胸肉\"]}");
        normalizeChicken();

        assertThat(extractor.extractFromHistory(saved, List.of(saved))).isEmpty();
    }

    @Test
    void learnsRecentPreferenceOnlyAfterRepeatedPositiveBehaviorAcrossRecipes() {
        MemoryEpisode saved = episode(1L, USER_ID, -3, "RECIPE_SAVED", "鸡胸肉沙拉", "recipe-1",
                "{\"recipeId\":\"recipe-1\",\"title\":\"鸡胸肉沙拉\",\"ingredients\":[\"鸡胸\"]}");
        MemoryEpisode cooked = episode(2L, USER_ID, -2, "RECIPE_FEEDBACK", "鸡胸肉意面", "feedback-2",
                "{\"action\":\"COOKED\",\"recipeTitle\":\"鸡胸肉意面\",\"ingredients\":[\"chicken breast\"]}");
        MemoryEpisode liked = episode(3L, USER_ID, -1, "RECIPE_FEEDBACK", "鸡胸肉饭", "feedback-3",
                "{\"action\":\"REACTION\",\"reaction\":\"LIKE\",\"recipeTitle\":\"鸡胸肉饭\","
                        + "\"ingredients\":[{\"name\":\"鸡胸肉\"}]}");
        normalizeChicken();

        List<MemoryBehaviorPatternExtractor.CandidateSupport> result =
                extractor.extractFromHistory(liked, List.of(saved, cooked, liked));

        verify(tagNormalizationService).normalize("INGREDIENT_PREFERENCE", "鸡胸肉");
        verify(tagNormalizationService).normalize("INGREDIENT_PREFERENCE", "鸡胸");
        verify(tagNormalizationService).normalize("INGREDIENT_PREFERENCE", "chicken breast");
        assertThat(result).hasSize(3);
        assertThat(result).allSatisfy(support -> {
            assertThat(support.draft().candidateType()).isEqualTo("INGREDIENT_PREFERENCE");
            assertThat(support.draft().entity()).isEqualTo("鸡胸肉");
            assertThat(support.draft().preference()).isEqualTo("LIKE");
            assertThat(support.draft().sourceType()).isEqualTo("IMPLICIT_BEHAVIOR");
            assertThat(support.draft().temporalType()).isEqualTo("RECENT");
            assertThat(support.draft().explicitConfirmed()).isFalse();
            assertThat(support.draft().confidence()).isLessThanOrEqualTo(new BigDecimal("0.7000"));
            assertThat(support.draft().evidence()).containsEntry("supportEpisodeCount", 3)
                    .containsEntry("distinctRecipeCount", 3);
        });
        assertThat(result).extracting(support -> support.episode().getId())
                .containsExactlyInAnyOrder(1L, 2L, 3L);
    }

    @Test
    void repeatedInteractionsWithOneRecipeDoNotBecomeAGeneralIngredientPreference() {
        MemoryEpisode saved = episode(1L, USER_ID, -3, "RECIPE_SAVED", "鸡胸肉沙拉", "recipe-1",
                "{\"recipeId\":\"recipe-1\",\"title\":\"鸡胸肉沙拉\",\"ingredients\":[\"鸡胸肉\"]}");
        MemoryEpisode cooked = episode(2L, USER_ID, -2, "RECIPE_FEEDBACK", "鸡胸肉沙拉", "feedback-2",
                "{\"action\":\"COOKED\",\"recipeTitle\":\"鸡胸肉沙拉\",\"ingredients\":[\"鸡胸肉\"]}");
        MemoryEpisode liked = episode(3L, USER_ID, -1, "RECIPE_FEEDBACK", "鸡胸肉沙拉", "feedback-3",
                "{\"action\":\"REACTION\",\"reaction\":\"LIKE\",\"recipeTitle\":\"鸡胸肉沙拉\","
                        + "\"ingredients\":[\"鸡胸肉\"]}");
        normalizeChicken();

        assertThat(extractor.extractFromHistory(liked, List.of(saved, cooked, liked))).isEmpty();
    }

    @Test
    void foreignUserEpisodesCannotCompleteAnotherUsersEvidenceThreshold() {
        MemoryEpisode saved = episode(1L, USER_ID, -2, "RECIPE_SAVED", "鸡胸肉沙拉", "recipe-1",
                "{\"recipeId\":\"recipe-1\",\"title\":\"鸡胸肉沙拉\",\"ingredients\":[\"鸡胸肉\"]}");
        MemoryEpisode foreignCooked = episode(2L, 8L, -1, "RECIPE_FEEDBACK", "鸡胸肉意面", "feedback-2",
                "{\"action\":\"COOKED\",\"recipeTitle\":\"鸡胸肉意面\",\"ingredients\":[\"鸡胸肉\"]}");
        MemoryEpisode current = episode(3L, USER_ID, 0, "RECIPE_FEEDBACK", "鸡胸肉饭", "feedback-3",
                "{\"action\":\"COOKED\",\"recipeTitle\":\"鸡胸肉饭\",\"ingredients\":[\"鸡胸肉\"]}");
        normalizeChicken();

        assertThat(extractor.extractFromHistory(current, List.of(saved, foreignCooked, current))).isEmpty();
    }

    @Test
    void ignoresExpiredSignalsAndRecipesThatWereLaterUnsaved() {
        MemoryEpisode expired = episode(1L, USER_ID, -120, "RECIPE_SAVED", "鸡胸肉沙拉", "recipe-1",
                "{\"recipeId\":\"recipe-1\",\"title\":\"鸡胸肉沙拉\",\"ingredients\":[\"鸡胸肉\"]}");
        MemoryEpisode saved = episode(2L, USER_ID, -2, "RECIPE_SAVED", "鸡胸肉意面", "recipe-2",
                "{\"recipeId\":\"recipe-2\",\"title\":\"鸡胸肉意面\",\"ingredients\":[\"鸡胸肉\"]}");
        MemoryEpisode unsaved = episode(3L, USER_ID, -1, "RECIPE_UNSAVED", "取消收藏", "recipe-2",
                "{\"recipeId\":\"recipe-2\"}");
        MemoryEpisode current = episode(4L, USER_ID, 0, "RECIPE_FEEDBACK", "鸡胸肉饭", "feedback-4",
                "{\"action\":\"COOKED\",\"recipeTitle\":\"鸡胸肉饭\",\"ingredients\":[\"鸡胸肉\"]}");
        normalizeChicken();

        assertThat(extractor.extractFromHistory(current, List.of(expired, saved, unsaved, current))).isEmpty();
    }

    @Test
    void savingARecipeAgainAfterUnsavingMakesOnlyTheNewSaveCount() {
        MemoryEpisode oldSave = episode(1L, USER_ID, -6, "RECIPE_SAVED", "鸡胸肉沙拉", "recipe-1",
                "{\"recipeId\":\"recipe-1\",\"title\":\"鸡胸肉沙拉\",\"ingredients\":[\"鸡胸肉\"]}");
        MemoryEpisode unsaved = episode(2L, USER_ID, -5, "RECIPE_UNSAVED", "取消收藏", "recipe-1",
                "{\"recipeId\":\"recipe-1\"}");
        MemoryEpisode savedAgain = episode(3L, USER_ID, -4, "RECIPE_SAVED", "鸡胸肉沙拉", "recipe-1",
                "{\"recipeId\":\"recipe-1\",\"title\":\"鸡胸肉沙拉\",\"ingredients\":[\"鸡胸肉\"]}");
        MemoryEpisode anotherSave = episode(4L, USER_ID, -3, "RECIPE_SAVED", "鸡胸肉意面", "recipe-2",
                "{\"recipeId\":\"recipe-2\",\"title\":\"鸡胸肉意面\",\"ingredients\":[\"鸡胸肉\"]}");
        MemoryEpisode current = episode(5L, USER_ID, -1, "RECIPE_FEEDBACK", "鸡胸肉饭", "feedback-5",
                "{\"action\":\"COOKED\",\"recipeTitle\":\"鸡胸肉饭\",\"ingredients\":[\"鸡胸肉\"]}");
        normalizeChicken();

        List<MemoryBehaviorPatternExtractor.CandidateSupport> result = extractor.extractFromHistory(current,
                List.of(oldSave, unsaved, savedAgain, anotherSave, current));

        assertThat(result).hasSize(3);
        assertThat(result).extracting(support -> support.episode().getId())
                .containsExactlyInAnyOrder(savedAgain.getId(), anotherSave.getId(), current.getId())
                .doesNotContain(oldSave.getId());
    }

    @Test
    void sameTimestampUnsaveAndResaveUseEpisodeIdAsTheTieBreaker() {
        MemoryEpisode oldSave = episode(1L, USER_ID, -1, "RECIPE_SAVED", "鸡胸肉沙拉", "recipe-1",
                "{\"recipeId\":\"recipe-1\",\"title\":\"鸡胸肉沙拉\",\"ingredients\":[\"鸡胸肉\"]}");
        MemoryEpisode unsaved = episode(2L, USER_ID, 0, "RECIPE_UNSAVED", "取消收藏", "recipe-1",
                "{\"recipeId\":\"recipe-1\"}");
        MemoryEpisode savedAgain = episode(3L, USER_ID, 0, "RECIPE_SAVED", "鸡胸肉沙拉", "recipe-1",
                "{\"recipeId\":\"recipe-1\",\"title\":\"鸡胸肉沙拉\",\"ingredients\":[\"鸡胸肉\"]}");
        MemoryEpisode anotherSave = episode(4L, USER_ID, 0, "RECIPE_SAVED", "鸡胸肉意面", "recipe-2",
                "{\"recipeId\":\"recipe-2\",\"title\":\"鸡胸肉意面\",\"ingredients\":[\"鸡胸肉\"]}");
        MemoryEpisode current = episode(5L, USER_ID, 0, "RECIPE_FEEDBACK", "鸡胸肉饭", "feedback-5",
                "{\"action\":\"COOKED\",\"recipeTitle\":\"鸡胸肉饭\",\"ingredients\":[\"鸡胸肉\"]}");
        normalizeChicken();

        List<MemoryBehaviorPatternExtractor.CandidateSupport> result = extractor.extractFromHistory(current,
                List.of(oldSave, unsaved, savedAgain, anotherSave, current));

        assertThat(result).hasSize(3);
        assertThat(result).extracting(support -> support.episode().getId())
                .containsExactlyInAnyOrder(savedAgain.getId(), anotherSave.getId(), current.getId())
                .doesNotContain(oldSave.getId());
    }

    @Test
    void aNewerDislikeSupersedesTheOlderLikeWhenCountingPositiveEvidence() {
        MemoryEpisode saved = episode(1L, USER_ID, -4, "RECIPE_SAVED", "鸡胸肉沙拉", "recipe-1",
                "{\"recipeId\":\"recipe-1\",\"title\":\"鸡胸肉沙拉\",\"ingredients\":[\"鸡胸肉\"]}");
        MemoryEpisode cooked = episode(2L, USER_ID, -3, "RECIPE_FEEDBACK", "鸡胸肉意面", "feedback-2",
                "{\"action\":\"COOKED\",\"recipeTitle\":\"鸡胸肉意面\",\"ingredients\":[\"鸡胸肉\"]}");
        MemoryEpisode olderLike = episode(3L, USER_ID, -2, "RECIPE_FEEDBACK", "鸡胸肉饭", "feedback-3",
                "{\"action\":\"REACTION\",\"reaction\":\"LIKE\",\"recipeTitle\":\"鸡胸肉饭\","
                        + "\"ingredients\":[\"鸡胸肉\"]}");
        MemoryEpisode newerDislike = episode(4L, USER_ID, -1, "RECIPE_FEEDBACK", "鸡胸肉饭", "feedback-3",
                "{\"action\":\"REACTION\",\"reaction\":\"DISLIKE\",\"recipeTitle\":\"鸡胸肉饭\","
                        + "\"ingredients\":[\"鸡胸肉\"]}");
        normalizeChicken();

        assertThat(extractor.extractFromHistory(cooked, List.of(saved, cooked, olderLike, newerDislike))).isEmpty();
    }

    private void normalizeChicken() {
        when(tagNormalizationService.normalizeText("鸡胸肉")).thenReturn("鸡胸肉");
        when(tagNormalizationService.normalizeText("鸡胸")).thenReturn("鸡胸");
        when(tagNormalizationService.normalizeText("chicken breast")).thenReturn("chicken breast");
        when(tagNormalizationService.normalize("INGREDIENT_PREFERENCE", "鸡胸肉"))
                .thenReturn(chickenTag("鸡胸肉"));
        when(tagNormalizationService.normalize("INGREDIENT_PREFERENCE", "鸡胸"))
                .thenReturn(chickenTag("鸡胸"));
        when(tagNormalizationService.normalize("INGREDIENT_PREFERENCE", "chicken breast"))
                .thenReturn(chickenTag("chicken breast"));
    }

    private TagNormalizationResult chickenTag(String raw) {
        return new TagNormalizationResult(raw, "INGREDIENT_CHICKEN_BREAST", 42L, "鸡胸肉",
                "INGREDIENT", "INGREDIENT_CHICKEN", "INGREDIENT_CHICKEN",
                BigDecimal.ONE.setScale(4), "ALIAS");
    }

    private MemoryEpisode episode(Long id, Long userId, int daysAgo, String type, String summary,
                                  String sourceId, String payload) {
        MemoryEpisode episode = new MemoryEpisode();
        episode.setId(id);
        episode.setUserId(userId);
        episode.setEpisodeType(type);
        episode.setSourceType("TEST");
        episode.setSourceId(sourceId);
        episode.setEventId("event-" + id);
        episode.setOccurredAt(LocalDateTime.of(2026, 9, 29, 12, 0).plusDays(daysAgo));
        episode.setSummary(summary);
        episode.setPayloadJson(payload);
        episode.setStatus("RAW");
        return episode;
    }
}
