package com.example.food.ai.recipe.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Size;

import java.util.List;

public record RecipeGenerateRequest(
        @Size(max = 240)
        String ingredients,
        String mealType,
        String goal,
        String searchMode,
        String regenerationPreference,
        String previousTitle,
        DietPreference dietPreference,
        Boolean usePantry,
        Boolean useHealthNutrition,
        Boolean useAiIngredientRecommendation,
        @Size(max = 1600)
        String userRequirement
) {
    public RecipeGenerateRequest(
            String ingredients,
            String mealType,
            String goal,
            String searchMode
    ) {
        this(ingredients, mealType, goal, searchMode, null, null, null, false, false, false, null);
    }

    public RecipeGenerateRequest(
            String ingredients,
            String mealType,
            String goal,
            String searchMode,
            String regenerationPreference,
            String previousTitle
    ) {
        this(ingredients, mealType, goal, searchMode, regenerationPreference, previousTitle,
                null, false, false, false, null);
    }

    public RecipeGenerateRequest(
            String ingredients,
            String mealType,
            String goal,
            String searchMode,
            String regenerationPreference,
            String previousTitle,
            DietPreference dietPreference
    ) {
        this(ingredients, mealType, goal, searchMode, regenerationPreference, previousTitle,
                dietPreference, false, false, false, null);
    }

    public RecipeGenerateRequest(
            String ingredients,
            String mealType,
            String goal,
            String searchMode,
            String regenerationPreference,
            String previousTitle,
            DietPreference dietPreference,
            Boolean usePantry,
            Boolean useHealthNutrition
    ) {
        this(ingredients, mealType, goal, searchMode, regenerationPreference, previousTitle, dietPreference,
                usePantry, useHealthNutrition, false, null);
    }

    public RecipeGenerateRequest(
            String ingredients,
            String mealType,
            String goal,
            String searchMode,
            String regenerationPreference,
            String previousTitle,
            DietPreference dietPreference,
            Boolean usePantry,
            Boolean useHealthNutrition,
            Boolean useAiIngredientRecommendation
    ) {
        this(ingredients, mealType, goal, searchMode, regenerationPreference, previousTitle, dietPreference,
                usePantry, useHealthNutrition, useAiIngredientRecommendation, null);
    }

    public boolean includePantry() {
        return Boolean.TRUE.equals(usePantry);
    }

    public boolean includeHealthNutrition() {
        return Boolean.TRUE.equals(useHealthNutrition);
    }

    public boolean includeAiIngredientRecommendation() {
        return Boolean.TRUE.equals(useAiIngredientRecommendation);
    }

    @AssertTrue(message = "请填写食材，或选择由 AI 自主推荐食材")
    public boolean hasIngredientSource() {
        return includeAiIngredientRecommendation() || (ingredients != null && !ingredients.isBlank());
    }

    public record DietPreference(
            String taste,
            String defaultGoal,
            List<String> avoidIngredients,
            List<String> allergenIngredients
    ) {
    }
}
