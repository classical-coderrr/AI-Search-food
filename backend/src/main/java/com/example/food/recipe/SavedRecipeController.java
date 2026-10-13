package com.example.food.recipe;

import com.example.food.common.ApiResponse;
import com.example.food.recipe.dto.RecipeHistoryDetailResponse;
import com.example.food.recipe.dto.RecipeHistorySummaryResponse;
import com.example.food.recipe.dto.SaveRecipeRequest;
import com.example.food.security.AuthPrincipal;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/recipes/saved")
public class SavedRecipeController {

    private final SavedRecipeService savedRecipeService;

    public SavedRecipeController(SavedRecipeService savedRecipeService) {
        this.savedRecipeService = savedRecipeService;
    }

    @PostMapping
    public ApiResponse<RecipeHistoryDetailResponse> save(
            @Valid @RequestBody SaveRecipeRequest request,
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestHeader(value = "X-Anonymous-Id", required = false) String anonymousId
    ) {
        RecipeHistoryDetailResponse result = savedRecipeService.save(request, principal, anonymousId);
        return new ApiResponse<>(0, result.alreadySaved() ? SavedRecipeService.ALREADY_SAVED_MESSAGE : "ok", result);
    }

    @GetMapping
    public ApiResponse<List<RecipeHistorySummaryResponse>> list(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String mealType,
            @RequestParam(required = false) String goal,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset
    ) {
        return ApiResponse.ok(savedRecipeService.list(principal.id(), keyword, mealType, goal, limit, offset));
    }

    @GetMapping("/{id}")
    public ApiResponse<RecipeHistoryDetailResponse> detail(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable Long id
    ) {
        return ApiResponse.ok(savedRecipeService.detail(principal.id(), id));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable Long id
    ) {
        savedRecipeService.delete(principal.id(), id);
        return ApiResponse.ok(null);
    }
}
