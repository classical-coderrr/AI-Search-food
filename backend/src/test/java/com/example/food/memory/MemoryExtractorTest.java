package com.example.food.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryExtractorTest {

    private final MemoryExtractor extractor = new MemoryExtractor(new ObjectMapper());

    @Test
    void doesNotTurnARecipeSearchIntoALongTermPreference() {
        MemoryEpisode episode = episode(
                "RECIPE_SEARCH",
                "{\"resultTitle\":\"番茄炒蛋\",\"ingredients\":[\"番茄\"]}"
        );

        assertThat(extractor.extract(episode)).isEmpty();
    }

    @Test
    void keepsSavedBehaviorAsAnImplicitLowConfidenceCandidate() {
        MemoryEpisode episode = episode(
                "RECIPE_SAVED",
                "{\"title\":\"黑椒鸡胸肉饭\",\"ingredients\":[\"鸡胸肉\",\"西兰花\"]}"
        );

        List<MemoryCandidateDraft> drafts = extractor.extract(episode);

        assertThat(drafts).extracting(MemoryCandidateDraft::candidateType)
                .contains("RECIPE_PREFERENCE", "INGREDIENT_PREFERENCE");
        MemoryCandidateDraft recipeDraft = drafts.stream()
                .filter(item -> "RECIPE_PREFERENCE".equals(item.candidateType()))
                .findFirst()
                .orElseThrow();
        assertThat(recipeDraft.sourceType()).isEqualTo("IMPLICIT_BEHAVIOR");
        assertThat(recipeDraft.confidence()).isEqualByComparingTo(new BigDecimal("0.4500"));
        assertThat(recipeDraft.explicitConfirmed()).isFalse();
    }

    @Test
    void extractsOnlyAllowlistedDietGoalsAsRecentBehaviorRatherThanConfirmedProfile() {
        MemoryEpisode episode = episode(
                "RECIPE_SAVED",
                "{\"title\":\"高蛋白鸡胸肉\",\"goal\":\"muscle_gain\",\"ingredients\":[\"鸡胸肉\"]}"
        );

        MemoryCandidateDraft goal = extractor.extract(episode).stream()
                .filter(item -> "DIET_GOAL".equals(item.candidateType()))
                .findFirst()
                .orElseThrow();

        assertThat(goal.entity()).isEqualTo("增肌");
        assertThat(goal.preference()).isEqualTo("PURSUE");
        assertThat(goal.temporalType()).isEqualTo("RECENT");
        assertThat(goal.sourceType()).isEqualTo("IMPLICIT_BEHAVIOR");
        assertThat(goal.explicitConfirmed()).isFalse();
        assertThat(goal.evidenceText()).contains("选择饮食目标");
    }

    @Test
    void ignoresUnknownAndDefaultRecipeGoalsForLongTermInference() {
        MemoryEpisode episode = episode(
                "RECIPE_SAVED",
                "{\"title\":\"家常菜\",\"goal\":\"balanced\",\"ingredients\":[]}"
        );

        assertThat(extractor.extract(episode)).noneMatch(item -> "DIET_GOAL".equals(item.candidateType()));
    }

    @Test
    void treatsExplicitFeedbackAsStrongerThanASave() {
        MemoryEpisode episode = episode(
                "RECIPE_FEEDBACK",
                "{\"action\":\"REACTION\",\"reaction\":\"LIKE\",\"recipeTitle\":\"鸡胸肉意面\"}"
        );

        MemoryCandidateDraft draft = extractor.extract(episode).get(0);

        assertThat(draft.sourceType()).isEqualTo("EXPLICIT_FEEDBACK");
        assertThat(draft.preference()).isEqualTo("LIKE");
        assertThat(draft.confidence()).isGreaterThan(new BigDecimal("0.4500"));
        assertThat(draft.explicitConfirmed()).isTrue();
        assertThat(draft.evidence()).containsEntry("action", "LIKE");
    }

    @Test
    void classifiesFinishedDishReviewUsingItsZeroToOneHundredPointScale() {
        MemoryCandidateDraft positive = extractor.extract(episode("FINISHED_DISH_REVIEW",
                "{\"recipeTitle\":\"番茄炒蛋\",\"overallScore\":85}"))
                .get(0);
        MemoryCandidateDraft negative = extractor.extract(episode("FINISHED_DISH_REVIEW",
                "{\"recipeTitle\":\"番茄炒蛋\",\"overallScore\":35}"))
                .get(0);

        assertThat(positive.preference()).isEqualTo("LIKE");
        assertThat(positive.evidenceText()).contains("85/100 分").doesNotContain("星");
        assertThat(negative.preference()).isEqualTo("DISLIKE");
    }

    @Test
    void doesNotInferARecipePreferenceFromMiddleOrOutOfRangeFinishedReviewScores() {
        assertThat(extractor.extract(episode("FINISHED_DISH_REVIEW",
                "{\"recipeTitle\":\"番茄炒蛋\",\"overallScore\":60}"))).isEmpty();
        assertThat(extractor.extract(episode("FINISHED_DISH_REVIEW",
                "{\"recipeTitle\":\"番茄炒蛋\",\"overallScore\":101}"))).isEmpty();
        assertThat(extractor.extract(episode("FINISHED_DISH_REVIEW",
                "{\"recipeTitle\":\"番茄炒蛋\",\"overallScore\":-1}"))).isEmpty();
    }

    @Test
    void supportsExplicitUserPreferenceEpisode() {
        MemoryEpisode episode = episode(
                "USER_PREFERENCE_DECLARED",
                "{\"candidateType\":\"INGREDIENT_PREFERENCE\",\"entity\":\"香菜\",\"preference\":\"DISLIKE\"}"
        );

        MemoryCandidateDraft draft = extractor.extract(episode).get(0);

        assertThat(draft.sourceType()).isEqualTo("EXPLICIT");
        assertThat(draft.entity()).isEqualTo("香菜");
        assertThat(draft.preference()).isEqualTo("DISLIKE");
        assertThat(draft.confidence()).isEqualByComparingTo(new BigDecimal("0.9500"));
    }

    private MemoryEpisode episode(String type, String payload) {
        MemoryEpisode episode = new MemoryEpisode();
        episode.setId(42L);
        episode.setUserId(7L);
        episode.setEpisodeType(type);
        episode.setSourceType("TEST");
        episode.setSourceId("source-1");
        episode.setEventId("event-1");
        episode.setPayloadJson(payload);
        episode.setOccurredAt(LocalDateTime.of(2026, 9, 23, 10, 0));
        episode.setSummary("测试记忆事件");
        return episode;
    }
}
