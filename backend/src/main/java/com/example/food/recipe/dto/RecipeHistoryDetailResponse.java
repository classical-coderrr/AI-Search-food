package com.example.food.recipe.dto;

import com.example.food.ai.recipe.dto.RecipeGenerateResponse;

import java.time.LocalDateTime;

public record RecipeHistoryDetailResponse(
        Long id,
        Long searchLogId,
        LocalDateTime savedAt,
        String searchIngredients,
        String mealType,
        String goal,
        RecipeGenerateResponse recipe,
        boolean alreadySaved
) {
    public RecipeHistoryDetailResponse(Long id, Long searchLogId, LocalDateTime savedAt,
                                       String searchIngredients, String mealType, String goal,
                                       RecipeGenerateResponse recipe) {
        this(id, searchLogId, savedAt, searchIngredients, mealType, goal, recipe, false);
    }

    public RecipeHistoryDetailResponse asAlreadySaved() {
        return new RecipeHistoryDetailResponse(id, searchLogId, savedAt, searchIngredients, mealType, goal, recipe, true);
    }
}
