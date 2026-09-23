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
