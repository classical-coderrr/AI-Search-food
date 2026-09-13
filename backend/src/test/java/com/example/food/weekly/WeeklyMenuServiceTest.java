package com.example.food.weekly;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.food.ai.config.AiModelRuntimeConfig;
import com.example.food.ai.qwen.QwenRecipeClient;
import com.example.food.ai.recipe.dto.RecipeGenerateResponse;
import com.example.food.pantry.UserPantryService;
import com.example.food.recipe.RecipeIngredient;
import com.example.food.recipe.RecipeIngredientMapper;
import com.example.food.recipe.RecipeRecord;
import com.example.food.recipe.RecipeRecordMapper;
import com.example.food.shopping.ShoppingItemStatus;
import com.example.food.stats.IngredientNormalizer;
import com.example.food.user.health.UserHealthProfileService;
import com.example.food.user.nutrition.UserNutritionTargetService;
import com.example.food.user.preference.UserDietPreferenceService;
import com.example.food.video.VideoSearchService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.food.weekly.dto.WeeklyMenuItemRequest;
import com.example.food.weekly.dto.WeeklyMenuAutoGenerateRequest;
import com.example.food.weekly.dto.WeeklyMenuResponse;
import com.example.food.weekly.dto.WeeklyMenuSaveRequest;
import com.example.food.weekly.dto.WeeklyMenuShoppingStatusRequest;
import com.example.food.weekly.dto.WeeklyShoppingStatusResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WeeklyMenuServiceTest {

    @Mock
    private WeeklyMenuPlanMapper planMapper;

    @Mock
    private WeeklyMenuItemMapper itemMapper;

    @Mock
    private WeeklyMenuShoppingCheckMapper shoppingCheckMapper;

    @Mock
    private RecipeRecordMapper recipeRecordMapper;

    @Mock
    private RecipeIngredientMapper recipeIngredientMapper;

    @Mock
    private UserPantryService userPantryService;

    @Mock
    private QwenRecipeClient qwenRecipeClient;

    @Mock
    private UserHealthProfileService userHealthProfileService;

    @Mock
    private UserNutritionTargetService userNutritionTargetService;

    @Mock
    private UserDietPreferenceService userDietPreferenceService;

    @Mock
    private VideoSearchService videoSearchService;

    private WeeklyMenuService service;

    @BeforeEach
    void setUp() {
        service = new WeeklyMenuService(
                planMapper,
                itemMapper,
                shoppingCheckMapper,
                recipeRecordMapper,
                recipeIngredientMapper,
                userPantryService,
                new IngredientNormalizer(),
                qwenRecipeClient,
                userHealthProfileService,
                userNutritionTargetService,
                userDietPreferenceService,
                new ObjectMapper(),
                videoSearchService
        );
    }

    @Test
    void returnsEmptyPlanForTheMondayOfRequestedWeek() {
        LocalDate monday = LocalDate.of(2026, 8, 31);
        when(planMapper.findByUserIdAndWeekStart(7L, monday)).thenReturn(null);

        WeeklyMenuResponse response = service.get(7L, monday.plusDays(2));

        assertThat(response.id()).isNull();
        assertThat(response.weekStart()).isEqualTo(monday);
        assertThat(response.weekEnd()).isEqualTo(monday.plusDays(6));
        assertThat(response.items()).isEmpty();
        assertThat(response.shoppingItems()).isEmpty();
    }

    @Test
    void summarizesEachAssignedMealAndSkipsRecipesWithoutValidNutrition() {
        LocalDate monday = LocalDate.of(2026, 8, 31);
        WeeklyMenuPlan plan = plan(99L, 7L, monday);
        RecipeRecord recipe = recipe(1L, "营养菜");
        recipe.setServings(2);
        recipe.setCaloriesKcal(new java.math.BigDecimal("420"));
        recipe.setProteinG(new java.math.BigDecimal("24"));
        recipe.setFatG(new java.math.BigDecimal("16"));
        recipe.setCarbohydrateG(new java.math.BigDecimal("42"));
        recipe.setNutritionSource("AI_ESTIMATE");
        when(planMapper.findByUserIdAndWeekStart(7L, monday)).thenReturn(plan);
        when(itemMapper.findByPlanId(99L)).thenReturn(List.of(
                item(11L, 99L, monday, "BREAKFAST", 1L),
                item(12L, 99L, monday, "DINNER", 1L)
        ));
        when(recipeRecordMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(recipe));
        when(recipeIngredientMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());
        when(userPantryService.listIngredientNames(7L)).thenReturn(List.of());
        when(shoppingCheckMapper.findByUserIdAndPlanId(7L, 99L)).thenReturn(List.of());

        WeeklyMenuResponse response = service.get(7L, monday);

        assertThat(response.nutritionSummary().assignedMealCount()).isEqualTo(2);
        assertThat(response.nutritionSummary().validEstimateMealCount()).isEqualTo(2);
        assertThat(response.nutritionSummary().weekly().caloriesKcal()).isEqualByComparingTo("840");
        assertThat(response.nutritionSummary().daily()).hasSize(7);
        assertThat(response.nutritionSummary().daily().get(0).totals().proteinG())
                .isEqualByComparingTo("48");
    }

    @Test
    void autoGeneratesAFullWeekFromSavedRecipesAndIncludesUserContext() {
        LocalDate monday = LocalDate.of(2026, 8, 31);
        WeeklyMenuPlan plan = plan(99L, 7L, monday);
        RecipeRecord tomatoEgg = recipe(1L, "番茄炒蛋");
        RecipeRecord eggSoup = recipe(2L, "鸡蛋汤");
        List<RecipeRecord> candidates = List.of(tomatoEgg, eggSoup);
        List<RecipeIngredient> candidateIngredients = List.of(
                ingredient(1L, "番茄", "2个"),
                ingredient(1L, "鸡蛋", "2个"),
                ingredient(2L, "鸡蛋", "3个")
        );
        List<WeeklyMenuItem> persistedItems = fullWeekItems(monday, 1L, 2L);
        when(planMapper.findByUserIdAndWeekStart(7L, monday)).thenReturn(null, null, plan);
        when(recipeRecordMapper.findSavedRecipes(7L, null, null, null, 50, 0)).thenReturn(candidates);
        when(recipeIngredientMapper.selectList(any(QueryWrapper.class)))
                .thenReturn(candidateIngredients, candidateIngredients);
        when(userHealthProfileService.getRecommendationContext(7L)).thenReturn(
                new com.example.food.user.health.UserHealthProfileService.RecommendationContext(
                        "AGE_30_44",
                        new java.math.BigDecimal("172.0"),
                        new java.math.BigDecimal("64.0"),
                        "MODERATE",
                        new java.math.BigDecimal("21.6")
                )
        );
        when(userDietPreferenceService.get(7L)).thenReturn(
                new com.example.food.user.preference.dto.DietPreferenceResponse(
                        "light", "fat_loss", List.of("香菜"), List.of("花生")
                )
        );
        when(userPantryService.listIngredientNames(7L)).thenReturn(List.of("番茄"), List.of("番茄"));
        when(qwenRecipeClient.generateWeeklyMenu(anyString())).thenReturn(List.of(
                new QwenRecipeClient.WeeklyMenuSelection(monday.toString(), "BREAKFAST", 1L)
        ));
        when(recipeRecordMapper.selectList(any(QueryWrapper.class))).thenReturn(candidates, candidates);
        doAnswer(invocation -> {
            WeeklyMenuPlan target = invocation.getArgument(0);
            target.setId(99L);
            return 1;
        }).when(planMapper).insert(any(WeeklyMenuPlan.class));
        when(itemMapper.findByPlanId(99L)).thenReturn(persistedItems);
        when(shoppingCheckMapper.findByUserIdAndPlanId(7L, 99L)).thenReturn(List.of());

        WeeklyMenuResponse response = service.autoGenerate(
                7L,
                new WeeklyMenuAutoGenerateRequest(monday, false)
        );

        assertThat(response.items()).hasSize(21);
        assertThat(response.items().get(0).recipeId()).isEqualTo(1L);
        assertThat(response.shoppingItems()).extracting("ingredientName")
                .containsExactly("番茄", "鸡蛋");
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(qwenRecipeClient).generateWeeklyMenu(promptCaptor.capture());
        assertThat(promptCaptor.getValue())
                .contains("2026-08-31")
                .contains("番茄炒蛋")
                .contains("候选菜谱（只能使用其中的 recipeId；mealTypeHint=BREAKFAST 表示适合早餐，MAIN 表示适合午餐或晚餐，ANY 表示未指定）：\n[{\"recipeId\"")
                .contains("年龄段=30-44岁")
                .contains("目标=fat_loss")
                .contains("每日营养目标：未设置")
                .contains("不得输出疾病诊断、治疗方案或疗效保证");
    }

    @Test
    void randomAutoGenerationIgnoresSavedRecordsAndGeneratesIndependentRecipes() {
        LocalDate monday = LocalDate.of(2026, 8, 31);
        when(planMapper.findByUserIdAndWeekStart(7L, monday)).thenReturn(null, null, plan(99L, 7L, monday));
        when(qwenRecipeClient.currentRuntimeConfig()).thenReturn(new AiModelRuntimeConfig(
                "qwen",
                "openai",
                "qwen3.8-max",
                "https://dashscope.test/compatible-mode/v1",
                "test-api-key"
        ));
        when(qwenRecipeClient.generateIndependentWeeklyMenu(anyString())).thenReturn(independentSuggestions(monday));
        when(videoSearchService.searchForRecipeGrounding(anyString(), anyInt())).thenReturn(
                new com.example.food.video.dto.VideoSearchResponse(List.of(), 1, false, false, false, "", null)
        );
        List<RecipeRecord> generatedRecords = new java.util.ArrayList<>();
        long[] generatedId = {100L};
        doAnswer(invocation -> {
            RecipeRecord record = invocation.getArgument(0);
            record.setId(generatedId[0]++);
            generatedRecords.add(record);
            return 1;
        }).when(recipeRecordMapper).insert(any(RecipeRecord.class));
        when(recipeRecordMapper.selectList(any(QueryWrapper.class))).thenReturn(generatedRecords);
        doAnswer(invocation -> {
            WeeklyMenuPlan target = invocation.getArgument(0);
            target.setId(99L);
            return 1;
        }).when(planMapper).insert(any(WeeklyMenuPlan.class));

        service.autoGenerate(7L, new WeeklyMenuAutoGenerateRequest(monday, false, "random"));

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(qwenRecipeClient).generateIndependentWeeklyMenu(promptCaptor.capture());
        assertThat(promptCaptor.getValue())
                .contains("随机安排")
                .contains("禁止读取、引用或复用任何用户数据")
                .contains("早餐推荐")
                .contains("午餐推荐")
                .contains("包子")
                .contains("不要输出 steps、tips、videoKeywords、nutritionEstimate 或 recipeId")
                .doesNotContain("随机菜谱");
        verify(qwenRecipeClient, never()).generateWeeklyMenu(anyString());
        verify(recipeRecordMapper, never()).findSavedRecipes(7L, null, null, null, 50, 0);
        verify(userPantryService, never()).listIngredientNames(7L);
        verify(userHealthProfileService, never()).getRecommendationContext(7L);
        verify(userDietPreferenceService, never()).get(7L);
        verify(userNutritionTargetService, never()).getRecommendationContext(7L);
        verify(videoSearchService, times(3)).searchForRecipeGrounding(anyString(), anyInt());
        ArgumentCaptor<RecipeRecord> recipeCaptor = ArgumentCaptor.forClass(RecipeRecord.class);
        verify(recipeRecordMapper, times(21)).insert(recipeCaptor.capture());
        assertThat(recipeCaptor.getAllValues())
                .allSatisfy(record -> {
                    assertThat(record.getWeeklyMenuPlanId()).isEqualTo(99L);
                    assertThat(record.getSearchLogId()).isNull();
                });
    }

    @Test
    void autoGenerationUsesBreakfastCandidateWhenBreakfastSelectionIsMissing() {
        LocalDate monday = LocalDate.of(2026, 8, 31);
        RecipeRecord dinnerRecipe = recipe(1L, "蒜蓉炒茄子");
        RecipeRecord breakfastRecipe = recipe(2L, "鲜肉包子");
        List<RecipeRecord> candidates = List.of(dinnerRecipe, breakfastRecipe);
        when(planMapper.findByUserIdAndWeekStart(7L, monday)).thenReturn(null, null, plan(99L, 7L, monday));
        when(recipeRecordMapper.findSavedRecipes(7L, null, null, null, 50, 0)).thenReturn(candidates);
        when(recipeIngredientMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(), List.of());
        when(userHealthProfileService.getRecommendationContext(7L)).thenReturn(null);
        when(userDietPreferenceService.get(7L)).thenReturn(
                com.example.food.user.preference.dto.DietPreferenceResponse.empty()
        );
        when(userPantryService.listIngredientNames(7L)).thenReturn(List.of(), List.of());
        when(qwenRecipeClient.generateWeeklyMenu(anyString())).thenReturn(List.of(
                new QwenRecipeClient.WeeklyMenuSelection(monday.toString(), "LUNCH", 1L)
        ));
        when(recipeRecordMapper.selectList(any(QueryWrapper.class))).thenReturn(candidates);
        doAnswer(invocation -> {
            WeeklyMenuPlan target = invocation.getArgument(0);
            target.setId(99L);
            return 1;
        }).when(planMapper).insert(any(WeeklyMenuPlan.class));
        when(itemMapper.findByPlanId(99L)).thenReturn(fullWeekItems(monday, 2L, 1L));
        when(shoppingCheckMapper.findByUserIdAndPlanId(7L, 99L)).thenReturn(List.of());

        service.autoGenerate(7L, new WeeklyMenuAutoGenerateRequest(monday, false, "PANTRY"));

        ArgumentCaptor<WeeklyMenuItem> itemCaptor = ArgumentCaptor.forClass(WeeklyMenuItem.class);
        verify(itemMapper, times(21)).insert(itemCaptor.capture());
        assertThat(itemCaptor.getAllValues()).filteredOn(item ->
                        monday.equals(item.getMenuDate()) && "BREAKFAST".equals(item.getMealType()))
                .extracting(WeeklyMenuItem::getRecipeId)
                .containsExactly(2L);
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(qwenRecipeClient).generateWeeklyMenu(promptCaptor.capture());
        assertThat(promptCaptor.getValue()).contains("鲜肉包子").contains("\"mealTypeHint\":\"BREAKFAST\"");
    }

    @Test
    void autoGenerationPromptIncludesEnabledNutritionTarget() {
        LocalDate monday = LocalDate.of(2026, 8, 31);
        when(planMapper.findByUserIdAndWeekStart(7L, monday)).thenReturn(null);
        when(recipeRecordMapper.findSavedRecipes(7L, null, null, null, 50, 0))
                .thenReturn(List.of(recipe(1L, "番茄炒蛋")));
        when(recipeIngredientMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());
        when(userHealthProfileService.getRecommendationContext(7L)).thenReturn(null);
        when(userDietPreferenceService.get(7L)).thenReturn(
                com.example.food.user.preference.dto.DietPreferenceResponse.empty()
        );
        when(userNutritionTargetService.getRecommendationContext(7L)).thenReturn(
                new UserNutritionTargetService.RecommendationContext(
                        new java.math.BigDecimal("2000"),
                        new java.math.BigDecimal("80"),
                        new java.math.BigDecimal("60"),
                        new java.math.BigDecimal("260")
                )
        );
        when(userPantryService.listIngredientNames(7L)).thenReturn(List.of());
        when(qwenRecipeClient.generateWeeklyMenu(anyString())).thenReturn(List.of(
                new QwenRecipeClient.WeeklyMenuSelection(monday.toString(), "BREAKFAST", 1L)
        ));
        when(recipeRecordMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(recipe(1L, "番茄炒蛋")));
        service.autoGenerate(7L, new WeeklyMenuAutoGenerateRequest(monday, false));

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(qwenRecipeClient).generateWeeklyMenu(promptCaptor.capture());
        assertThat(promptCaptor.getValue())
                .contains("每日营养目标：每日热量=2000千卡，蛋白质=80克，脂肪=60克，碳水=260克")
                .contains("软偏好，仅供一般饮食参考");
    }

    @Test
    void refusesToOverwriteExistingMenuWithoutExplicitConfirmation() {
        LocalDate monday = LocalDate.of(2026, 8, 31);
        when(planMapper.findByUserIdAndWeekStart(7L, monday)).thenReturn(plan(99L, 7L, monday));

        assertThatThrownBy(() -> service.autoGenerate(
                7L,
                new WeeklyMenuAutoGenerateRequest(monday, false)
        ))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(exception -> ((ResponseStatusException) exception).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        verifyNoInteractions(qwenRecipeClient);
    }

    @Test
    void refusesAutoGenerationWithoutSavedRecipes() {
        LocalDate monday = LocalDate.of(2026, 8, 31);
        when(planMapper.findByUserIdAndWeekStart(7L, monday)).thenReturn(null);
        when(recipeRecordMapper.findSavedRecipes(7L, null, null, null, 50, 0)).thenReturn(List.of());

        assertThatThrownBy(() -> service.autoGenerate(
                7L,
                new WeeklyMenuAutoGenerateRequest(monday, false)
        ))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(exception -> ((ResponseStatusException) exception).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(qwenRecipeClient);
    }

    @Test
    void refusesAutoGenerationWhenModelReturnsNoValidSelection() {
        LocalDate monday = LocalDate.of(2026, 8, 31);
        RecipeRecord recipe = recipe(1L, "测试菜");
        when(planMapper.findByUserIdAndWeekStart(7L, monday)).thenReturn(null);
        when(recipeRecordMapper.findSavedRecipes(7L, null, null, null, 50, 0)).thenReturn(List.of(recipe));
        when(recipeIngredientMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());
        when(userHealthProfileService.getRecommendationContext(7L)).thenReturn(null);
        when(userDietPreferenceService.get(7L)).thenReturn(
                com.example.food.user.preference.dto.DietPreferenceResponse.empty()
        );
        when(userPantryService.listIngredientNames(7L)).thenReturn(List.of());
        when(qwenRecipeClient.generateWeeklyMenu(anyString())).thenReturn(List.of(
                new QwenRecipeClient.WeeklyMenuSelection("invalid-date", "DINNER", 999L)
        ));

        assertThatThrownBy(() -> service.autoGenerate(
                7L,
                new WeeklyMenuAutoGenerateRequest(monday, false)
        ))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(exception -> ((ResponseStatusException) exception).getStatusCode())
                .isEqualTo(HttpStatus.BAD_GATEWAY);
        verify(planMapper, never()).insert(any(WeeklyMenuPlan.class));
    }

    @Test
    void savesMenuAndAggregatesSameUnitIngredientsAgainstPantry() {
        LocalDate monday = LocalDate.of(2026, 8, 31);
        WeeklyMenuPlan plan = plan(99L, 7L, monday);
        RecipeRecord tomatoEgg = recipe(1L, "番茄炒蛋");
        RecipeRecord eggSoup = recipe(2L, "紫菜蛋花汤");
        List<WeeklyMenuItem> menuItems = List.of(
                item(11L, 99L, monday, "BREAKFAST", 1L),
                item(12L, 99L, monday.plusDays(1), "DINNER", 2L)
        );
        when(planMapper.findByUserIdAndWeekStart(7L, monday)).thenReturn(null, plan);
        doAnswer(invocation -> {
            WeeklyMenuPlan target = invocation.getArgument(0);
            target.setId(99L);
            return 1;
        }).when(planMapper).insert(any(WeeklyMenuPlan.class));
        when(recipeRecordMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(tomatoEgg, eggSoup));
        when(itemMapper.findByPlanId(99L)).thenReturn(menuItems);
        when(recipeIngredientMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(
                ingredient(1L, "番茄", "2个"),
                ingredient(1L, "鸡蛋", "2个"),
                ingredient(2L, "鸡蛋", "3个"),
                ingredient(2L, "番茄", "100克")
        ));
        when(userPantryService.listIngredientNames(7L)).thenReturn(List.of("番茄"));
        when(shoppingCheckMapper.findByUserIdAndPlanId(7L, 99L)).thenReturn(List.of());

        WeeklyMenuResponse response = service.save(7L, new WeeklyMenuSaveRequest(
                monday.plusDays(1),
                List.of(
                        new WeeklyMenuItemRequest(monday, "BREAKFAST", 1L),
                        new WeeklyMenuItemRequest(monday.plusDays(1), "DINNER", 2L)
                )
        ));

        verify(itemMapper).deleteByPlanId(99L);
        verify(itemMapper, org.mockito.Mockito.times(2)).insert(any(WeeklyMenuItem.class));
        assertThat(response.weekStart()).isEqualTo(monday);
        assertThat(response.items()).extracting("recipeTitle")
                .containsExactly("番茄炒蛋", "紫菜蛋花汤");
        assertThat(response.shoppingItems()).extracting("ingredientName")
                .containsExactly("番茄", "鸡蛋");
        assertThat(response.shoppingItems().get(0).amount()).isEqualTo("2个、100克");
        assertThat(response.shoppingItems().get(0).alreadyOwned()).isTrue();
        assertThat(response.shoppingItems().get(0).status()).isEqualTo(ShoppingItemStatus.READY.name());
        assertThat(response.shoppingItems().get(1).amount()).isEqualTo("5个");
        assertThat(response.shoppingItems().get(1).alreadyOwned()).isFalse();
    }

    @Test
    void keepsIncompatibleUnitsInsteadOfConvertingThem() {
        LocalDate monday = LocalDate.of(2026, 8, 31);
        WeeklyMenuPlan plan = plan(99L, 7L, monday);
        RecipeRecord recipe = recipe(1L, "测试菜");
        when(planMapper.findByUserIdAndWeekStart(7L, monday)).thenReturn(plan);
        when(itemMapper.findByPlanId(99L)).thenReturn(List.of(item(11L, 99L, monday, "DINNER", 1L)));
        when(recipeRecordMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(recipe));
        when(recipeIngredientMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(
                ingredient(1L, "鸡蛋", "2个"),
                ingredient(1L, "鸡蛋", "100克")
        ));
        when(userPantryService.listIngredientNames(7L)).thenReturn(List.of());
        when(shoppingCheckMapper.findByUserIdAndPlanId(7L, 99L)).thenReturn(List.of());

        WeeklyMenuResponse response = service.get(7L, monday);

        assertThat(response.shoppingItems().get(0).amount()).isEqualTo("2个、100克");
    }

    @Test
    void rejectsRecipeOwnedByAnotherUser() {
        LocalDate monday = LocalDate.of(2026, 8, 31);
        when(recipeRecordMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());

        assertThatThrownBy(() -> service.save(7L, new WeeklyMenuSaveRequest(
                monday,
                List.of(new WeeklyMenuItemRequest(monday, "DINNER", 88L))
        )))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(exception -> ((ResponseStatusException) exception).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        verify(planMapper, never()).insert(any(WeeklyMenuPlan.class));
    }

    @Test
    void rejectsDuplicateMealSlot() {
        LocalDate monday = LocalDate.of(2026, 8, 31);

        assertThatThrownBy(() -> service.save(7L, new WeeklyMenuSaveRequest(
                monday,
                List.of(
                        new WeeklyMenuItemRequest(monday, "DINNER", 1L),
                        new WeeklyMenuItemRequest(monday, "DINNER", 2L)
                )
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("同一天的同一餐次不能重复安排");
    }

    @Test
    void savesWeeklyShoppingStatusForAnAggregatedIngredient() {
        LocalDate monday = LocalDate.of(2026, 8, 31);
        WeeklyMenuPlan plan = plan(99L, 7L, monday);
        when(planMapper.findByUserIdAndWeekStart(7L, monday)).thenReturn(plan);
        when(itemMapper.findByPlanId(99L)).thenReturn(List.of(item(11L, 99L, monday, "DINNER", 1L)));
        when(recipeIngredientMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(
                ingredient(1L, "鸡蛋", "2个")
        ));
        when(userPantryService.listIngredientNames(7L)).thenReturn(List.of());
        when(shoppingCheckMapper.findByUserIdAndPlanId(7L, 99L)).thenReturn(List.of());
        doAnswer(invocation -> {
            WeeklyMenuShoppingCheck target = invocation.getArgument(0);
            target.setId(55L);
            return 1;
        }).when(shoppingCheckMapper).insert(any(WeeklyMenuShoppingCheck.class));

        WeeklyShoppingStatusResponse response = service.saveShoppingStatus(
                7L,
                new WeeklyMenuShoppingStatusRequest(monday.plusDays(3), "鸡蛋", "PURCHASING")
        );

        ArgumentCaptor<WeeklyMenuShoppingCheck> captor = ArgumentCaptor.forClass(WeeklyMenuShoppingCheck.class);
        verify(shoppingCheckMapper).insert(captor.capture());
        assertThat(captor.getValue().getIngredientName()).isEqualTo("鸡蛋");
        assertThat(captor.getValue().getStatus()).isEqualTo("PURCHASING");
        assertThat(response.status()).isEqualTo("PURCHASING");
        assertThat(response.amount()).isEqualTo("2个");
    }

    @Test
    void deletesWeeklyPlanWithoutAffectingSavedRecipes() {
        LocalDate monday = LocalDate.of(2026, 8, 31);
        when(planMapper.findByUserIdAndWeekStart(7L, monday)).thenReturn(plan(99L, 7L, monday));

        service.delete(7L, monday.plusDays(4));

        verify(planMapper).deleteById(99L);
    }

    private WeeklyMenuPlan plan(Long id, Long userId, LocalDate weekStart) {
        WeeklyMenuPlan plan = new WeeklyMenuPlan();
        plan.setId(id);
        plan.setUserId(userId);
        plan.setWeekStartDate(weekStart);
        return plan;
    }

    private WeeklyMenuItem item(Long id, Long planId, LocalDate date, String mealType, Long recipeId) {
        WeeklyMenuItem item = new WeeklyMenuItem();
        item.setId(id);
        item.setPlanId(planId);
        item.setMenuDate(date);
        item.setMealType(mealType);
        item.setRecipeId(recipeId);
        return item;
    }

    private RecipeRecord recipe(Long id, String title) {
        RecipeRecord recipe = new RecipeRecord();
        recipe.setId(id);
        recipe.setUserId(7L);
        recipe.setTitle(title);
        return recipe;
    }

    private RecipeIngredient ingredient(Long recipeId, String name, String amount) {
        RecipeIngredient ingredient = new RecipeIngredient();
        ingredient.setRecipeId(recipeId);
        ingredient.setIngredientName(name);
        ingredient.setAmount(amount);
        return ingredient;
    }

    private List<WeeklyMenuItem> fullWeekItems(LocalDate monday, Long breakfastRecipeId, Long dinnerRecipeId) {
        java.util.ArrayList<WeeklyMenuItem> items = new java.util.ArrayList<>();
        long id = 100L;
        for (int day = 0; day < 7; day++) {
            LocalDate date = monday.plusDays(day);
            items.add(item(id++, 99L, date, "BREAKFAST", breakfastRecipeId));
            items.add(item(id++, 99L, date, "LUNCH", dinnerRecipeId));
            items.add(item(id++, 99L, date, "DINNER", dinnerRecipeId));
        }
        return items;
    }

    private List<QwenRecipeClient.IndependentWeeklyMenuRecipe> independentSuggestions(LocalDate monday) {
        java.util.ArrayList<QwenRecipeClient.IndependentWeeklyMenuRecipe> suggestions = new java.util.ArrayList<>();
        for (int day = 0; day < 7; day++) {
            LocalDate date = monday.plusDays(day);
            suggestions.add(independentSuggestion(date, "BREAKFAST", "独立早餐" + (day + 1)));
            suggestions.add(independentSuggestion(date, "LUNCH", "独立午餐" + (day + 1)));
            suggestions.add(independentSuggestion(date, "DINNER", "独立晚餐" + (day + 1)));
        }
        return suggestions;
    }

    private QwenRecipeClient.IndependentWeeklyMenuRecipe independentSuggestion(
            LocalDate date,
            String mealType,
            String title
    ) {
        return new QwenRecipeClient.IndependentWeeklyMenuRecipe(
                date.toString(),
                mealType,
                title,
                "独立生成测试菜谱",
                List.of(
                        new RecipeGenerateResponse.Ingredient("番茄", "2个"),
                        new RecipeGenerateResponse.Ingredient("鸡蛋", "2个")
                ),
                List.of(),
                List.of(),
                List.of(),
                null
        );
    }
}
