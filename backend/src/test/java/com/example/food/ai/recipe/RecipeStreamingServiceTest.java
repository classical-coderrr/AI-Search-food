package com.example.food.ai.recipe;

import com.example.food.ai.qwen.QwenRecipeClient;
import com.example.food.ai.recipe.dto.RecipeGenerateRequest;
import com.example.food.ai.recipe.dto.RecipeGenerateResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RecipeStreamingServiceTest {

    private final RecipeRecommendationService recommendationService = mock(RecipeRecommendationService.class);
    private final QwenRecipeClient qwenClient = mock(QwenRecipeClient.class);
    private final RecipeStreamingService service = new RecipeStreamingService(recommendationService, qwenClient);

    @BeforeEach
    void keepsGeneratedResponseWhenPantryContextIsNotUnderTest() {
        when(recommendationService.applyGenerationContextFlags(any(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(1));
    }

    @Test
    void persistsExactlyOnceAfterACompleteRecipe() {
        RecipeGenerateResponse response = recipe().withSearchLogId(19L);
        when(recommendationService.preparePrompt(any(), any())).thenReturn(
                new RecipeRecommendationService.PreparedPrompt("prompt", false, false, false)
        );
        when(recommendationService.recommendationBatchMode(any())).thenReturn("MEAL_COMBO");
        when(recommendationService.batchRecipePrompt(anyString(), any(), anyInt(), anyInt(), anyList()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(recommendationService.persist(any(), any(), any(), anyString())).thenReturn(response);
        when(qwenClient.streamRecipe(anyString(), any(), any())).thenAnswer(invocation -> {
            Consumer<String> onDelta = invocation.getArgument(1);
            onDelta.accept("{\"title\":\"番茄炒蛋\",\"summary\":\"家常菜\",\"ingredients\":[{\"name\":\"番茄\"}],\"steps\":[{\"title\":\"炒制\",\"description\":\"炒熟\"}]}");
            return new QwenRecipeClient.RecipeStreamResult("", response, "qwen", "qwen-plus", false);
        });

        SseEmitter emitter = service.generate(
                new RecipeGenerateRequest("番茄、鸡蛋", "dinner", "light", "text"),
                null,
                "anon-001"
        );

        verify(recommendationService, timeout(2000).times(3)).persist(any(), any(), any(), anyString());
        verify(recommendationService, timeout(2000).times(1)).validateBatchIngredientAlignment(any(), any());
        verify(qwenClient, timeout(2000).times(3)).streamRecipe(anyString(), any(), any());
        emitter.complete();
    }

    @Test
    void retriesARecipeWhenItsTitleRepeatsAnEarlierRecipe() {
        RecipeGenerateResponse first = recipe("番茄炒蛋", "炒制");
        RecipeGenerateResponse replacement = recipe("番茄蛋花汤", "煮制");
        RecipeGenerateResponse third = recipe("番茄蒸蛋", "蒸制");
        when(recommendationService.preparePrompt(any(), any())).thenReturn(
                new RecipeRecommendationService.PreparedPrompt("prompt", false, false, false)
        );
        when(recommendationService.recommendationBatchMode(any())).thenReturn("STYLE_VARIANTS");
        when(recommendationService.batchRecipePrompt(anyString(), any(), anyInt(), anyInt(), anyList()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(recommendationService.isDuplicateRecipe(any(), anyList())).thenAnswer(invocation -> {
            RecipeGenerateResponse candidate = invocation.getArgument(0);
            List<RecipeGenerateResponse> previous = invocation.getArgument(1);
            return previous.stream().anyMatch(item -> item != null && item.title().equals(candidate.title()));
        });
        when(recommendationService.persist(any(), any(), any(), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        when(qwenClient.streamRecipe(anyString(), any(), any()))
                .thenReturn(streamResult(first), streamResult(first), streamResult(replacement), streamResult(third));

        SseEmitter emitter = service.generate(
                new RecipeGenerateRequest("番茄", "dinner", "light", "text"),
                null,
                "anon-001"
        );

        verify(qwenClient, timeout(2000).times(4)).streamRecipe(anyString(), any(), any());
        verify(recommendationService, timeout(2000).times(3)).persist(any(), any(), any(), anyString());
        emitter.complete();
    }

    @Test
    void usesInputIngredientCountWhenItExceedsThree() {
        RecipeGenerateResponse response = recipe();
        when(recommendationService.recommendationCount(any())).thenReturn(9);
        when(recommendationService.preparePrompt(any(), any())).thenReturn(
                new RecipeRecommendationService.PreparedPrompt("prompt", false, false, false)
        );
        when(recommendationService.recommendationBatchMode(any())).thenReturn("MEAL_COMBO");
        when(recommendationService.batchRecipePrompt(anyString(), any(), anyInt(), anyInt(), anyList()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(recommendationService.persist(any(), any(), any(), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        when(qwenClient.streamRecipe(anyString(), any(), any()))
                .thenReturn(streamResult(response));

        SseEmitter emitter = service.generate(
                new RecipeGenerateRequest(
                        "食材1、食材2、食材3、食材4、食材5、食材6、食材7、食材8、食材9",
                        "dinner",
                        "light",
                        "text"
                ),
                null,
                "anon-001"
        );

        verify(qwenClient, timeout(2000).times(9)).streamRecipe(anyString(), any(), any());
        verify(recommendationService, timeout(2000).times(9)).persist(any(), any(), any(), anyString());
        emitter.complete();
    }

    @Test
    void passesThinkingPlanIntoTheFinalStreamingPrompts() {
        RecipeGenerateResponse response = recipe();
        QwenRecipeClient.RecipePlan plan = new QwenRecipeClient.RecipePlan(
                "蒜泥白肉",
                List.of("猪肉"),
                List.of("蒜泥白肉 家常做法")
        );
        when(recommendationService.preparePrompt(any(), any())).thenReturn(
                new RecipeRecommendationService.PreparedPrompt("prompt", false, false, false)
        );
        when(recommendationService.recommendationBatchMode(any())).thenReturn("STYLE_VARIANTS");
        when(recommendationService.planRecipeSelections(any(), anyInt(), any()))
                .thenReturn(List.of(plan, plan, plan));
        when(recommendationService.batchRecipePrompt(anyString(), any(), anyInt(), anyInt(), anyList(), any()))
                .thenReturn("planned-prompt");
        when(recommendationService.persist(any(), any(), any(), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        when(qwenClient.streamRecipe(anyString(), any(), any())).thenReturn(
                streamResult(response), streamResult(response), streamResult(response)
        );

        SseEmitter emitter = service.generate(
                new RecipeGenerateRequest("猪肉", "dinner", "balanced", "text"),
                null,
                "anon-001"
        );

        verify(recommendationService, timeout(2000).times(3))
                .batchRecipePrompt(anyString(), any(), anyInt(), anyInt(), anyList(), any());
        emitter.complete();
    }

    @Test
    void keepsMinimumCountWhenPlannerReturnsFewerPlans() {
        RecipeGenerateResponse response = recipe();
        QwenRecipeClient.RecipePlan shrimpPlan = new QwenRecipeClient.RecipePlan(
                "清蒸大虾",
                List.of("虾"),
                List.of("清蒸大虾 家常做法")
        );
        QwenRecipeClient.RecipePlan tilapiaPlan = new QwenRecipeClient.RecipePlan(
                "清蒸罗非鱼",
                List.of("罗非鱼"),
                List.of("清蒸罗非鱼 家常做法")
        );
        when(recommendationService.recommendationCount(any())).thenReturn(3);
        when(recommendationService.preparePrompt(any(), any())).thenReturn(
                new RecipeRecommendationService.PreparedPrompt("prompt", false, false, false)
        );
        when(recommendationService.recommendationBatchMode(any())).thenReturn("MEAL_COMBO");
        when(recommendationService.planRecipeSelections(any(), anyInt(), any()))
                .thenReturn(List.of(shrimpPlan, tilapiaPlan));
        when(recommendationService.batchRecipePrompt(anyString(), any(), anyInt(), anyInt(), anyList(), any()))
                .thenReturn("planned-prompt");
        when(recommendationService.batchRecipePrompt(anyString(), any(), anyInt(), anyInt(), anyList()))
                .thenReturn("fallback-prompt");
        when(recommendationService.persist(any(), any(), any(), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        when(qwenClient.streamRecipe(anyString(), any(), any()))
                .thenReturn(streamResult(response));

        SseEmitter emitter = service.generate(
                new RecipeGenerateRequest("虾、罗非鱼", "dinner", "balanced", "text"),
                null,
                "anon-001"
        );

        verify(qwenClient, timeout(2000).times(3)).streamRecipe(anyString(), any(), any());
        verify(recommendationService, timeout(2000).times(3)).persist(any(), any(), any(), anyString());
        emitter.complete();
    }

    @Test
    void retriesWhenARecipeUsesAThirdRequestedIngredient() {
        RecipeGenerateResponse invalid = recipeWithIngredients("番茄鸡蛋洋葱炒", "番茄", "鸡蛋", "洋葱");
        RecipeGenerateResponse valid = recipeWithIngredients("番茄炒鸡蛋", "番茄", "鸡蛋");
        when(recommendationService.preparePrompt(any(), any())).thenReturn(
                new RecipeRecommendationService.PreparedPrompt("prompt", false, false, false)
        );
        when(recommendationService.recommendationBatchMode(any())).thenReturn("MEAL_COMBO");
        when(recommendationService.batchRecipePrompt(anyString(), any(), anyInt(), anyInt(), anyList()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        doThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY, "第三种食材"))
                .doNothing()
                .when(recommendationService)
                .validateRecipeIngredientPair(any(), any(), anyInt(), anyInt());
        when(recommendationService.persist(any(), any(), any(), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        when(qwenClient.streamRecipe(anyString(), any(), any()))
                .thenReturn(streamResult(invalid), streamResult(valid), streamResult(valid), streamResult(valid));

        SseEmitter emitter = service.generate(
                new RecipeGenerateRequest("番茄、鸡蛋、洋葱", "dinner", "light", "text"),
                null,
                "anon-001"
        );

        ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);
        verify(qwenClient, timeout(2000).times(4)).streamRecipe(prompts.capture(), any(), any());
        assertTrue(prompts.getAllValues().get(1).contains("食材约束校正"));
        verify(recommendationService, timeout(2000).times(3)).persist(any(), any(), any(), anyString());
        emitter.complete();
    }

    @Test
    void retriesARecipeWhenItsAssignedIngredientIsMissing() {
        RecipeGenerateResponse tomato = recipeWithIngredients("番茄炒蛋", "番茄");
        RecipeGenerateResponse egg = recipeWithIngredients("鸡蛋羹", "鸡蛋");
        AtomicBoolean retried = new AtomicBoolean();
        when(recommendationService.preparePrompt(any(), any())).thenReturn(
                new RecipeRecommendationService.PreparedPrompt("prompt", false, false, false)
        );
        when(recommendationService.recommendationBatchMode(any())).thenReturn("MEAL_COMBO");
        when(recommendationService.batchRecipePrompt(anyString(), any(), anyInt(), anyInt(), anyList()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(recommendationService.ingredientCoverageRetryInstruction(any(), anyInt(), anyInt(), any()))
                .thenReturn("coverage-retry");
        org.mockito.Mockito.doAnswer(invocation -> {
            int recipeIndex = invocation.getArgument(2);
            RecipeGenerateResponse candidate = invocation.getArgument(1);
            String ingredient = candidate.ingredients().get(0).name();
            if (recipeIndex == 1 && "番茄".equals(ingredient) && retried.compareAndSet(false, true)) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "缺少鸡蛋");
            }
            return null;
        }).when(recommendationService).validateRequiredIngredientCoverage(
                any(), any(), anyInt(), anyInt(), any()
        );
        when(recommendationService.persist(any(), any(), any(), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        when(qwenClient.streamRecipe(anyString(), any(), any()))
                .thenReturn(streamResult(tomato), streamResult(tomato), streamResult(egg), streamResult(tomato));

        SseEmitter emitter = service.generate(
                new RecipeGenerateRequest("番茄、鸡蛋", "dinner", "light", "text"),
                null,
                "anon-001"
        );

        ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);
        verify(qwenClient, timeout(2000).times(4)).streamRecipe(prompts.capture(), any(), any());
        assertTrue(prompts.getAllValues().get(2).contains("coverage-retry"));
        verify(recommendationService, timeout(2000).times(3)).persist(any(), any(), any(), anyString());
        emitter.complete();
    }

    @Test
    void doesNotPersistAnIncompleteRecipe() {
        RecipeGenerateResponse response = new RecipeGenerateResponse(
                "",
                "",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                "qwen",
                "qwen-plus"
        );
        when(recommendationService.preparePrompt(any(), any())).thenReturn(
                new RecipeRecommendationService.PreparedPrompt("prompt", false, false, false)
        );
        when(recommendationService.recommendationBatchMode(any())).thenReturn("STYLE_VARIANTS");
        when(recommendationService.batchRecipePrompt(anyString(), any(), anyInt(), anyInt(), anyList()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(qwenClient.streamRecipe(anyString(), any(), any())).thenReturn(
                new QwenRecipeClient.RecipeStreamResult("", response, "qwen", "qwen-plus", false)
        );

        service.generate(new RecipeGenerateRequest("番茄", "dinner", "light", "text"), null, "anon-001");

        verify(qwenClient, timeout(2000)).streamRecipe(anyString(), any(), any());
        verify(recommendationService, never()).persist(any(), any(), any(), anyString());
    }

    private RecipeGenerateResponse recipe() {
        return recipe("番茄炒蛋", "炒制");
    }

    private RecipeGenerateResponse recipe(String title, String stepTitle) {
        return new RecipeGenerateResponse(
                title,
                "家常菜",
                List.of(),
                List.of(new RecipeGenerateResponse.Ingredient("番茄", "2个")),
                List.of(new RecipeGenerateResponse.Step(1, stepTitle, "完成烹饪", 5)),
                List.of(),
                List.of(),
                "qwen",
                "qwen-plus"
        );
    }

    private RecipeGenerateResponse recipeWithIngredients(String title, String... names) {
        return new RecipeGenerateResponse(
                title,
                "家常菜",
                List.of(),
                java.util.Arrays.stream(names)
                        .map(name -> new RecipeGenerateResponse.Ingredient(name, "适量"))
                        .toList(),
                List.of(new RecipeGenerateResponse.Step(1, "炒制", "完成烹饪", 5)),
                List.of(),
                List.of(),
                "qwen",
                "qwen-plus"
        );
    }

    private QwenRecipeClient.RecipeStreamResult streamResult(RecipeGenerateResponse response) {
        return new QwenRecipeClient.RecipeStreamResult("", response, "qwen", "qwen-plus", false);
    }
}
