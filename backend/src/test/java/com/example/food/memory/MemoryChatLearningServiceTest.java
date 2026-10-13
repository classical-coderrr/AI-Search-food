package com.example.food.memory;

import com.example.food.agent.AgentMessageMapper;
import com.example.food.ai.qwen.QwenRecipeClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Map;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class MemoryChatLearningServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final MemoryChatLearningService service = new MemoryChatLearningService(
            mock(MemoryBehaviorEpisodeRecorder.class), mock(MemoryEpisodeMapper.class), mock(AgentMessageMapper.class),
            mock(MemoryPersonalizationService.class), mock(MemoryLearningGuard.class), mock(QwenRecipeClient.class),
            mapper, mock(PlatformTransactionManager.class));

    @Test
    void retainsOnlyGroundedPersonalFactsAndExplicitlyNamedRequestIngredients() throws Exception {
        String message = "我一直不喜欢香菜，今晚想吃牛肉饺子";
        var preference = fact("PREFERENCE", "INGREDIENT_PREFERENCE", "香菜", "DISLIKE", "LONG_TERM", "我一直不喜欢香菜");
        var request = fact("FOOD_REQUEST", "RECIPE_PREFERENCE", "牛肉饺子", "WANT", "SESSION", "今晚想吃牛肉饺子");
        request.put("ingredients", List.of("牛肉", "猪肉", "面粉"));
        var accepted = service.validate(message, mapper.writeValueAsString(Map.of("facts", List.of(preference, request))));
        assertThat(accepted).hasSize(2);
        assertThat(accepted.get(0)).containsEntry("preference", "DISLIKE");
        assertThat(accepted.get(1)).containsEntry("ingredients", List.of("牛肉"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"scope", "evidenceText", "temporalType", "candidateType", "preference", "correction"})
    void rejectsUngroundedUnsupportedOrNonPersonalCandidates(String field) throws Exception {
        var fact = fact("PREFERENCE", "INGREDIENT_PREFERENCE", "牛肉", "LIKE", "LONG_TERM", "我一直喜欢牛肉");
        fact.put(field, switch (field) {
            case "scope" -> "FAMILY";
            case "evidenceText" -> "助手认为我喜欢牛肉";
            case "temporalType" -> "SESSION";
            case "candidateType" -> "DIET_GOAL";
            case "preference" -> "UNKNOWN";
            default -> "true";
        });
        assertThat(service.validate("我一直喜欢牛肉", mapper.writeValueAsString(Map.of("facts", List.of(fact))))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-json", "{}", "{\"facts\":true}"})
    void malformedResultsFailSoTheQueueCanRetry(String result) {
        assertThatThrownBy(() -> service.validate("我喜欢牛肉", result)).isInstanceOf(IllegalArgumentException.class);
    }

    static java.util.LinkedHashMap<String, Object> fact(String kind, String type, String entity,
                                                     String preference, String temporal, String quote) {
        var fact = new java.util.LinkedHashMap<String, Object>();
        fact.put("kind", kind);
        fact.put("candidateType", type);
        fact.put("entity", entity);
        fact.put("preference", preference);
        fact.put("scope", "USER");
        fact.put("temporalType", temporal);
        fact.put("evidenceText", quote);
        fact.put("correction", false);
        return fact;
    }
}
