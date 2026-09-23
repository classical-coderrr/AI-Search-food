package com.example.food.recipe;

import com.example.food.ai.recipe.dto.RecipeGenerateRequest;
import com.example.food.ai.recipe.dto.RecipeGenerateResponse;
import com.example.food.memory.MemoryBehaviorEpisodeEvent;
import com.example.food.memory.MemoryBehaviorEpisodeRecorder;
import com.example.food.security.AppRole;
import com.example.food.security.AuthPrincipal;
import com.example.food.stats.IngredientNormalizer;
import com.example.food.stats.SearchLogIngredient;
import com.example.food.stats.SearchLogIngredientMapper;
import com.example.food.stats.image.IngredientImageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

@ExtendWith(MockitoExtension.class)
class SearchLogServiceTest {

    @Mock
    private SearchLogMapper searchLogMapper;

    @Mock
    private SearchLogIngredientMapper searchLogIngredientMapper;

    @Mock
    private IngredientImageService ingredientImageService;

    @Mock
    private MemoryBehaviorEpisodeRecorder memoryEpisodeRecorder;

    private SearchLogService searchLogService;

    @BeforeEach
    void setUp() {
        searchLogService = new SearchLogService(
                searchLogMapper,
                searchLogIngredientMapper,
                new IngredientNormalizer(),
                new ObjectMapper(),
                ingredientImageService,
                memoryEpisodeRecorder
        );
        doAnswer(invocation -> {
            SearchLog searchLog = invocation.getArgument(0);
            searchLog.setId(42L);
            return 1;
        }).when(searchLogMapper).insert(any(SearchLog.class));
    }

    @Test
    void recordsSuccessfulTextSearchForAuthenticatedUser() {
        RecipeGenerateRequest request = new RecipeGenerateRequest("番茄, 鸡蛋", "dinner", "balanced", "text");

        Long id = searchLogService.record(
                request,
                recipeResponse(),
                new AuthPrincipal(7L, "13800138000", AppRole.USER),
                "browser-12345678"
        );

        ArgumentCaptor<SearchLog> captor = ArgumentCaptor.forClass(SearchLog.class);
        org.mockito.Mockito.verify(searchLogMapper).insert(captor.capture());
        SearchLog saved = captor.getValue();
        assertThat(id).isEqualTo(42L);
        assertThat(saved.getUserId()).isEqualTo(7L);
        assertThat(saved.getAnonymousId()).isEqualTo("browser-12345678");
        assertThat(saved.getQueryText()).isEqualTo("番茄, 鸡蛋");
        assertThat(saved.getInputType()).isEqualTo("text");
        assertThat(saved.getResultTitle()).isEqualTo("番茄炒蛋");
        assertThat(saved.getResultIngredients()).isEqualTo("[\"番茄\"]");
        assertThat(saved.getMealType()).isEqualTo("dinner");
        assertThat(saved.getGoal()).isEqualTo("balanced");
        assertThat(saved.getAiModel()).isEqualTo("qwen:qwen-plus");
        org.mockito.Mockito.verify(ingredientImageService)
                .ensureQueuedAfterSearch(List.of("番茄", "鸡蛋"));
        org.mockito.Mockito.verify(memoryEpisodeRecorder)
                .record(org.mockito.ArgumentMatchers.any(MemoryBehaviorEpisodeEvent.class));
    }

    @Test
    void recordsAnonymousImageSearchWithRecognizedIngredients() {
        RecipeGenerateRequest request = new RecipeGenerateRequest("土豆, 胡萝卜", "lunch", "light", "image");

        searchLogService.record(request, recipeResponse(), null, "anonymous-12345678");

        ArgumentCaptor<SearchLog> captor = ArgumentCaptor.forClass(SearchLog.class);
        org.mockito.Mockito.verify(searchLogMapper).insert(captor.capture());
        SearchLog saved = captor.getValue();
        assertThat(saved.getUserId()).isNull();
        assertThat(saved.getAnonymousId()).isEqualTo("anonymous-12345678");
        assertThat(saved.getRecognizedIngredients()).isEqualTo("[\"土豆\",\"胡萝卜\"]");
    }

    @Test
    void recordsDistinctCanonicalIngredientDetailsInTheSameSearch() {
        RecipeGenerateRequest request = new RecipeGenerateRequest(
                "西红柿, 番茄, 马铃薯, 土豆",
                "lunch",
                "balanced",
                "text"
        );

        searchLogService.record(request, recipeResponse(), null, "anonymous-12345678");

        ArgumentCaptor<SearchLogIngredient> captor = ArgumentCaptor.forClass(SearchLogIngredient.class);
        org.mockito.Mockito.verify(searchLogIngredientMapper, org.mockito.Mockito.times(2))
                .insert(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(
                        SearchLogIngredient::getSearchLogId,
                        SearchLogIngredient::getOriginalName,
                        SearchLogIngredient::getIngredientName
                )
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(42L, "西红柿", "番茄"),
                        org.assertj.core.groups.Tuple.tuple(42L, "马铃薯", "土豆")
                );
        assertThat(captor.getAllValues())
                .allSatisfy(item -> assertThat(item.getCreatedAt()).isNotNull());
    }

    @Test
    void recordsDietPreferenceGoalWhenRequestGoalIsBlank() {
        String longGoal = "g".repeat(90);
        RecipeGenerateRequest request = new RecipeGenerateRequest(
                "番茄",
                "dinner",
                null,
                "text",
                null,
                null,
                new RecipeGenerateRequest.DietPreference(
                        "light",
                        longGoal,
                        List.of(),
                        List.of()
                )
        );

        searchLogService.record(request, recipeResponse(), null, "anonymous-12345678");

        ArgumentCaptor<SearchLog> captor = ArgumentCaptor.forClass(SearchLog.class);
        org.mockito.Mockito.verify(searchLogMapper).insert(captor.capture());
        assertThat(captor.getValue().getGoal()).isEqualTo("g".repeat(80));
    }

    private RecipeGenerateResponse recipeResponse() {
        return new RecipeGenerateResponse(
                "番茄炒蛋",
                "家常快手菜",
                List.of("补充蛋白质"),
                List.of(new RecipeGenerateResponse.Ingredient("番茄", "2个")),
                List.of(new RecipeGenerateResponse.Step(1, "备菜", "番茄切块", 5)),
                List.of("先炒鸡蛋"),
                List.of("番茄炒蛋教程"),
                "qwen",
                "qwen-plus"
        );
    }
}
