package com.example.food.recipe;

import com.example.food.ai.recipe.dto.RecipeGenerateResponse;
import com.example.food.recipe.dto.RecipeHistoryDetailResponse;
import com.example.food.recipe.dto.SaveRecipeRequest;
import com.example.food.security.AppRole;
import com.example.food.security.AuthPrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SavedRecipeServiceTest {

    @Mock
    private SearchLogMapper searchLogMapper;

    @Mock
    private RecipeRecordMapper recipeRecordMapper;

    @Mock
    private RecipeIngredientMapper recipeIngredientMapper;

    @Mock
    private RecipeStepMapper recipeStepMapper;

    private SavedRecipeService savedRecipeService;

    @BeforeEach
    void setUp() {
        savedRecipeService = new SavedRecipeService(
                searchLogMapper,
                recipeRecordMapper,
                recipeIngredientMapper,
                recipeStepMapper,
                new ObjectMapper()
        );
    }

    private void stubRecipeInsert() {
        doAnswer(invocation -> {
            RecipeRecord record = invocation.getArgument(0);
            record.setId(99L);
            return 1;
        }).when(recipeRecordMapper).insert(any(RecipeRecord.class));
    }

    @Test
    void savesRecipeWithAuthenticatedUserIdAndChildren() {
        stubRecipeInsert();
        SearchLog searchLog = searchLog(10L, 7L, null);
        when(searchLogMapper.selectById(10L)).thenReturn(searchLog);

        RecipeHistoryDetailResponse result = savedRecipeService.save(
                request(),
                new AuthPrincipal(7L, "13800138000", AppRole.USER),
                null
        );

        ArgumentCaptor<RecipeRecord> recordCaptor = ArgumentCaptor.forClass(RecipeRecord.class);
        ArgumentCaptor<RecipeIngredient> ingredientCaptor = ArgumentCaptor.forClass(RecipeIngredient.class);
        ArgumentCaptor<RecipeStep> stepCaptor = ArgumentCaptor.forClass(RecipeStep.class);
        org.mockito.Mockito.verify(recipeRecordMapper).insert(recordCaptor.capture());
        org.mockito.Mockito.verify(recipeIngredientMapper).insert(ingredientCaptor.capture());
        org.mockito.Mockito.verify(recipeStepMapper).insert(stepCaptor.capture());

        assertThat(recordCaptor.getValue().getUserId()).isEqualTo(7L);
        assertThat(recordCaptor.getValue().getSearchLogId()).isEqualTo(10L);
        assertThat(recordCaptor.getValue().getTitle()).isEqualTo("番茄炒蛋");
        assertThat(ingredientCaptor.getValue().getRecipeId()).isEqualTo(99L);
        assertThat(stepCaptor.getValue().getRecipeId()).isEqualTo(99L);
        assertThat(result.id()).isEqualTo(99L);
    }

    @Test
    void replaysExistingAgentSaveByIdempotencyKeyWithoutCreatingAnotherRecipe() {
        RecipeRecord existing = new RecipeRecord();
        existing.setId(101L);
        existing.setUserId(7L);
        existing.setTitle("番茄炒蛋");
        existing.setSearchLogId(10L);
        when(recipeRecordMapper.findByUserIdAndAgentIdempotencyKey(7L, "key-1"))
                .thenReturn(existing);
        when(recipeRecordMapper.selectById(101L)).thenReturn(existing);
        when(searchLogMapper.selectById(10L)).thenReturn(searchLog(10L, 7L, null));
        when(recipeIngredientMapper.selectList(any())).thenReturn(List.of());
        when(recipeStepMapper.selectList(any())).thenReturn(List.of());

        RecipeHistoryDetailResponse result = savedRecipeService.save(
                request(), new AuthPrincipal(7L, "13800138000", AppRole.USER), null, "key-1");

        assertThat(result.id()).isEqualTo(101L);
        verify(recipeRecordMapper, never()).insert(any(RecipeRecord.class));
    }

    @Test
    void persistsStructuredNutritionAlongsideRecipeRecord() {
        stubRecipeInsert();
        when(searchLogMapper.selectById(10L)).thenReturn(searchLog(10L, 7L, null));
        RecipeGenerateResponse.NutritionEstimate nutrition = new RecipeGenerateResponse.NutritionEstimate(
                2,
                new java.math.BigDecimal("420"),
                new java.math.BigDecimal("24"),
                new java.math.BigDecimal("16"),
                new java.math.BigDecimal("42"),
                RecipeGenerateResponse.NutritionEstimate.AI_ESTIMATE
        );
        SaveRecipeRequest request = new SaveRecipeRequest(
                10L,
                "番茄炒蛋",
                "家常快手菜",
                List.of(),
                List.of(new RecipeGenerateResponse.Ingredient("番茄", "2个")),
                List.of(),
                List.of(new RecipeGenerateResponse.Step(1, "备菜", "番茄切块", 5)),
                List.of(),
                List.of(),
                RecipeGenerateResponse.Explanation.empty(),
                nutrition,
                "qwen",
                "qwen-plus"
        );

        savedRecipeService.save(request, new AuthPrincipal(7L, "13800138000", AppRole.USER), null);

        ArgumentCaptor<RecipeRecord> recordCaptor = ArgumentCaptor.forClass(RecipeRecord.class);
        verify(recipeRecordMapper).insert(recordCaptor.capture());
        assertThat(recordCaptor.getValue().getServings()).isEqualTo(2);
        assertThat(recordCaptor.getValue().getCaloriesKcal()).isEqualByComparingTo("420");
        assertThat(recordCaptor.getValue().getNutritionSource()).isEqualTo("AI_ESTIMATE");
    }

    @Test
    void rejectsSearchLogOwnedByAnotherUser() {
        when(searchLogMapper.selectById(10L)).thenReturn(searchLog(10L, 8L, null));

        assertThatThrownBy(() -> savedRecipeService.save(
                request(),
                new AuthPrincipal(7L, "13800138000", AppRole.USER),
                null
        )).hasMessageContaining("无权使用该搜索记录");
    }

    @Test
    void acceptsAnonymousSearchLogWhenAnonymousIdMatches() {
        stubRecipeInsert();
        when(searchLogMapper.selectById(10L)).thenReturn(searchLog(10L, null, "anonymous-12345678"));

        RecipeHistoryDetailResponse result = savedRecipeService.save(
                request(),
                new AuthPrincipal(7L, "13800138000", AppRole.USER),
                "anonymous-12345678"
        );

        assertThat(result.id()).isEqualTo(99L);
    }

    @Test
    void savesAndReloadsRecipeEnhancementsFromRawResponse() {
        stubRecipeInsert();
        when(searchLogMapper.selectById(10L)).thenReturn(searchLog(10L, 7L, null));

        savedRecipeService.save(
                enhancedRequest(),
                new AuthPrincipal(7L, "13800138000", AppRole.USER),
                null
        );

        ArgumentCaptor<RecipeRecord> recordCaptor = ArgumentCaptor.forClass(RecipeRecord.class);
        verify(recipeRecordMapper).insert(recordCaptor.capture());
        RecipeRecord savedRecord = recordCaptor.getValue();
        savedRecord.setUserId(7L);
        when(recipeRecordMapper.selectById(99L)).thenReturn(savedRecord);
        when(recipeIngredientMapper.selectList(any())).thenReturn(List.of());
        when(recipeStepMapper.selectList(any())).thenReturn(List.of());

        RecipeHistoryDetailResponse detail = savedRecipeService.detail(7L, 99L);

        assertThat(detail.recipe().missingIngredients()).singleElement().satisfies(item -> {
            assertThat(item.name()).isEqualTo("鸡蛋");
            assertThat(item.substitutes()).containsExactly("嫩豆腐");
        });
        assertThat(detail.recipe().explanation().pairingLogic()).isEqualTo("番茄的酸甜与鸡蛋的鲜香互补");
        assertThat(detail.recipe().explanation().nutrition()).isEqualTo("包含蛋白质与维生素");
        assertThat(detail.recipe().explanation().cookingPrinciple()).isEqualTo("分开炒制能保持嫩度");
    }

    @Test
    void fallsBackToNormalizedFieldsWhenStoredRawResponseIsInvalid() {
        RecipeRecord record = new RecipeRecord();
        record.setId(99L);
        record.setUserId(7L);
        record.setTitle("番茄炒蛋");
        record.setSummary("家常快手菜");
        record.setEffects("[\"补充蛋白质\"]");
        record.setTips("[\"先炒鸡蛋\"]");
        record.setVideoKeywords("[\"番茄炒蛋教程\"]");
        record.setAiModel("qwen:qwen-plus");
        record.setRawResponse("{invalid-json");
        when(recipeRecordMapper.selectById(99L)).thenReturn(record);
        when(recipeIngredientMapper.selectList(any())).thenReturn(List.of(
                recipeIngredient("番茄", "2个")
        ));
        when(recipeStepMapper.selectList(any())).thenReturn(List.of(
                recipeStep(1, "备菜", "番茄切块", 5)
        ));

        RecipeHistoryDetailResponse detail = savedRecipeService.detail(7L, 99L);

        assertThat(detail.recipe().title()).isEqualTo("番茄炒蛋");
        assertThat(detail.recipe().ingredients()).extracting(RecipeGenerateResponse.Ingredient::name)
                .containsExactly("番茄");
        assertThat(detail.recipe().steps()).extracting(RecipeGenerateResponse.Step::title)
                .containsExactly("备菜");
        assertThat(detail.recipe().missingIngredients()).isEmpty();
        assertThat(detail.recipe().explanation()).isNotNull();
    }

    @Test
    void listsSavedRecipesUsingNormalizedSearchFilters() {
        RecipeRecord record = recipeRecord(99L, 7L, 10L, "番茄炒蛋");
        when(recipeRecordMapper.findSavedRecipes(7L, "番茄", "dinner", "balanced", 20, 5))
                .thenReturn(List.of(record));
        when(searchLogMapper.selectById(10L)).thenReturn(searchLog(10L, 7L, null));

        List<?> result = savedRecipeService.list(7L, "  番茄  ", " dinner ", " balanced ", 20, 5);

        assertThat(result).hasSize(1);
        verify(recipeRecordMapper).findSavedRecipes(7L, "番茄", "dinner", "balanced", 20, 5);
    }

    @Test
    void ignoresBlankAndUnlimitedSearchFilters() {
        when(recipeRecordMapper.findSavedRecipes(7L, null, null, null, 50, 0)).thenReturn(List.of());

        savedRecipeService.list(7L, " ", "不限", " 不限 ", 50, 0);
        savedRecipeService.list(7L, " 不限 ", null, null, 50, 0);

        verify(recipeRecordMapper, org.mockito.Mockito.times(2))
                .findSavedRecipes(7L, null, null, null, 50, 0);
    }

    @Test
    void rejectsInvalidSavedRecipePagination() {
        assertThatThrownBy(() -> savedRecipeService.list(7L, null, null, null, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> savedRecipeService.list(7L, null, null, null, 51, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> savedRecipeService.list(7L, null, null, null, 50, -1))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(recipeRecordMapper);
    }

    @Test
    void deletesRecipeOnlyWhenOwnedByCurrentUser() {
        when(recipeRecordMapper.deleteOwnedRecipe(99L, 7L)).thenReturn(1);

        savedRecipeService.delete(7L, 99L);

        verify(recipeRecordMapper).deleteOwnedRecipe(99L, 7L);
        verifyNoInteractions(searchLogMapper, recipeIngredientMapper, recipeStepMapper);
    }

    @Test
    void treatsMissingAndOtherUsersRecipeAsNotFoundWhenDeleting() {
        when(recipeRecordMapper.deleteOwnedRecipe(99L, 7L)).thenReturn(0);

        assertThatThrownBy(() -> savedRecipeService.delete(7L, 99L))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .satisfies(exception -> assertThat(
                        ((org.springframework.web.server.ResponseStatusException) exception).getStatusCode().value()
                ).isEqualTo(404));
    }

    private SearchLog searchLog(Long id, Long userId, String anonymousId) {
        SearchLog searchLog = new SearchLog();
        searchLog.setId(id);
        searchLog.setUserId(userId);
        searchLog.setAnonymousId(anonymousId);
        searchLog.setQueryText("番茄、鸡蛋");
        searchLog.setMealType("dinner");
        searchLog.setGoal("balanced");
        return searchLog;
    }

    private RecipeRecord recipeRecord(Long id, Long userId, Long searchLogId, String title) {
        RecipeRecord record = new RecipeRecord();
        record.setId(id);
        record.setUserId(userId);
        record.setSearchLogId(searchLogId);
        record.setTitle(title);
        record.setCreatedAt(LocalDateTime.now());
        return record;
    }

    private SaveRecipeRequest request() {
        return new SaveRecipeRequest(
                10L,
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

    private SaveRecipeRequest enhancedRequest() {
        return new SaveRecipeRequest(
                10L,
                "番茄炒蛋",
                "家常快手菜",
                List.of("补充蛋白质"),
                List.of(new RecipeGenerateResponse.Ingredient("番茄", "2个")),
                List.of(new RecipeGenerateResponse.MissingIngredient(
                        "鸡蛋",
                        "2个",
                        List.of("嫩豆腐"),
                        "用户已有食材中没有鸡蛋"
                )),
                List.of(new RecipeGenerateResponse.Step(1, "备菜", "番茄切块", 5)),
                List.of("先炒鸡蛋"),
                List.of("番茄炒蛋教程"),
                new RecipeGenerateResponse.Explanation(
                        "番茄的酸甜与鸡蛋的鲜香互补",
                        "包含蛋白质与维生素",
                        "分开炒制能保持嫩度"
                ),
                "qwen",
                "qwen-plus"
        );
    }

    private RecipeIngredient recipeIngredient(String name, String amount) {
        RecipeIngredient ingredient = new RecipeIngredient();
        ingredient.setIngredientName(name);
        ingredient.setAmount(amount);
        return ingredient;
    }

    private RecipeStep recipeStep(int order, String title, String instruction, int minutes) {
        RecipeStep step = new RecipeStep();
        step.setStepNo(order);
        step.setTitle(title);
        step.setInstruction(instruction);
        step.setEstimatedMinutes(minutes);
        return step;
    }
}
