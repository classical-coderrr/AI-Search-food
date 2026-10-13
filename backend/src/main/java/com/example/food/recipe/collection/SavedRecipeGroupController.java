package com.example.food.recipe.collection;

import com.example.food.common.ApiResponse;
import com.example.food.recipe.collection.dto.BatchOperationResponse;
import com.example.food.recipe.collection.dto.RecipeCollectionResponse;
import com.example.food.recipe.collection.dto.SavedRecipeResponse;
import com.example.food.security.AuthPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/users/me/saved-recipe-groups")
public class SavedRecipeGroupController {
    private final SavedRecipeGroupService service;

    public SavedRecipeGroupController(SavedRecipeGroupService service) { this.service = service; }

    @GetMapping
    public ApiResponse<SavedRecipeGroupService.GroupPage> list(@AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam(required = false) Long collectionId, @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String mealType, @RequestParam(required = false) String goal,
            @RequestParam(required = false) String tag, @RequestParam(defaultValue = "savedAtDesc") String sort,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.list(principal.id(), collectionId, keyword, mealType, goal, tag, sort, page, size));
    }

    @GetMapping("/collections")
    public ApiResponse<List<RecipeCollectionResponse>> collections(@AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(service.listCollections(principal.id()));
    }

    @GetMapping("/{key}/versions")
    public ApiResponse<List<SavedRecipeResponse>> versions(@AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable String key, @RequestParam(required = false) Long preferredId) {
        return ApiResponse.ok(service.versions(principal.id(), key, preferredId));
    }

    @PostMapping("/batch-move")
    public ApiResponse<BatchOperationResponse> move(@AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody BatchRequest request) {
        return ApiResponse.ok(service.batchMove(principal.id(), request.groupKeys(), request.collectionId()));
    }

    @PostMapping("/batch-tags")
    public ApiResponse<BatchOperationResponse> tags(@AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody BatchRequest request) {
        return ApiResponse.ok(service.batchTags(principal.id(), request.groupKeys(), request.addTags(), request.removeTags()));
    }

    @DeleteMapping("/batch")
    public ApiResponse<BatchOperationResponse> delete(@AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody BatchRequest request) {
        return ApiResponse.ok(service.batchDelete(principal.id(), request.groupKeys()));
    }

    @DeleteMapping("/{key}")
    public ApiResponse<BatchOperationResponse> deleteOne(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable String key) {
        return ApiResponse.ok(service.batchDelete(principal.id(), List.of(key)));
    }

    public record BatchRequest(@NotEmpty @Size(max = 100) List<@Pattern(regexp = "[a-f0-9]{64}") String> groupKeys,
                               Long collectionId, List<String> addTags, List<String> removeTags) { }
}
