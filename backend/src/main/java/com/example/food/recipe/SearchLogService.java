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
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class SearchLogService {

    private static final int MAX_PREFERENCE_GOAL_LENGTH = 80;

    private final SearchLogMapper searchLogMapper;
    private final SearchLogIngredientMapper searchLogIngredientMapper;
    private final IngredientNormalizer ingredientNormalizer;
    private final ObjectMapper objectMapper;
    private final IngredientImageService ingredientImageService;
    private final MemoryBehaviorEpisodeRecorder memoryEpisodeRecorder;

    @org.springframework.beans.factory.annotation.Autowired
    public SearchLogService(
            SearchLogMapper searchLogMapper,
            SearchLogIngredientMapper searchLogIngredientMapper,
            IngredientNormalizer ingredientNormalizer,
            ObjectMapper objectMapper,
            IngredientImageService ingredientImageService,
            MemoryBehaviorEpisodeRecorder memoryEpisodeRecorder
    ) {
        this.searchLogMapper = searchLogMapper;
        this.searchLogIngredientMapper = searchLogIngredientMapper;
        this.ingredientNormalizer = ingredientNormalizer;
        this.objectMapper = objectMapper;
        this.ingredientImageService = ingredientImageService;
        this.memoryEpisodeRecorder = memoryEpisodeRecorder;
    }

    public SearchLogService(
            SearchLogMapper searchLogMapper,
            SearchLogIngredientMapper searchLogIngredientMapper,
            IngredientNormalizer ingredientNormalizer,
            ObjectMapper objectMapper,
            IngredientImageService ingredientImageService
    ) {
        this(searchLogMapper, searchLogIngredientMapper, ingredientNormalizer, objectMapper,
                ingredientImageService, null);
    }

    @Transactional
    public Long record(
            RecipeGenerateRequest request,
            RecipeGenerateResponse response,
            AuthPrincipal principal,
            String anonymousId
    ) {
        List<IngredientNormalizer.NormalizedIngredient> ingredients =
                ingredientNormalizer.normalizeDistinct(request.ingredients());
        LocalDateTime createdAt = LocalDateTime.now();
        SearchLog searchLog = new SearchLog();
        searchLog.setUserId(userId(principal));
        searchLog.setAnonymousId(normalizeAnonymousId(anonymousId));
        searchLog.setQueryText(normalize(request.ingredients()));
        searchLog.setInputType(defaultValue(request.searchMode(), "text"));
        searchLog.setRecognizedIngredients(toJson(ingredients.stream()
                .map(IngredientNormalizer.NormalizedIngredient::originalName)
                .toList()));
        searchLog.setResultTitle(limitText(response.title(), 128));
        searchLog.setResultIngredients(toJson(resultIngredients(response)));
        searchLog.setAiModel(modelLabel(response));
        searchLog.setMealType(normalize(request.mealType()));
        searchLog.setGoal(resolveGoal(request));
        searchLog.setCreatedAt(createdAt);
        searchLogMapper.insert(searchLog);
        ingredients.forEach(ingredient -> searchLogIngredientMapper.insert(
                toIngredient(searchLog.getId(), ingredient, createdAt)
        ));
        if (ingredientImageService != null) {
            ingredientImageService.ensureQueuedAfterSearch(ingredients.stream()
                    .map(IngredientNormalizer.NormalizedIngredient::canonicalName)
                    .toList());
        }
        recordSearchEpisode(searchLog, ingredients);
        return searchLog.getId();
    }

    private void recordSearchEpisode(
            SearchLog searchLog,
            List<IngredientNormalizer.NormalizedIngredient> ingredients
    ) {
        if (memoryEpisodeRecorder == null || searchLog.getUserId() == null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("queryText", searchLog.getQueryText());
        payload.put("inputType", searchLog.getInputType());
        payload.put("ingredients", ingredients.stream()
                .map(IngredientNormalizer.NormalizedIngredient::canonicalName)
                .toList());
        payload.put("resultTitle", searchLog.getResultTitle());
        payload.put("resultIngredients", searchLog.getResultIngredients());
        payload.put("mealType", searchLog.getMealType());
        payload.put("goal", searchLog.getGoal());
        memoryEpisodeRecorder.record(new MemoryBehaviorEpisodeEvent(
                searchLog.getUserId(),
                null,
                null,
                "RECIPE_SEARCH",
                "SEARCH_LOG",
                String.valueOf(searchLog.getId()),
                "search:" + searchLog.getId(),
                "search:" + searchLog.getId(),
                "搜索菜谱：" + (searchLog.getQueryText() == null ? "未填写食材" : searchLog.getQueryText()),
                payload,
                searchLog.getCreatedAt(),
                new BigDecimal("0.2000")
        ));
    }

    private SearchLogIngredient toIngredient(
            Long searchLogId,
            IngredientNormalizer.NormalizedIngredient ingredient,
            LocalDateTime createdAt
    ) {
        SearchLogIngredient entity = new SearchLogIngredient();
        entity.setSearchLogId(searchLogId);
        entity.setOriginalName(ingredient.originalName());
        entity.setIngredientName(ingredient.canonicalName());
        entity.setCreatedAt(createdAt);
        return entity;
    }

    private Long userId(AuthPrincipal principal) {
        return principal != null && principal.role() == AppRole.USER ? principal.id() : null;
    }

    private String modelLabel(RecipeGenerateResponse response) {
        String provider = normalize(response.provider());
        String model = normalize(response.model());
        if (provider == null) {
            return model;
        }
        if (model == null) {
            return provider;
        }
        return provider + ":" + model;
    }

    private String toJson(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("搜索食材记录序列化失败", exception);
        }
    }

    private String defaultValue(String value, String defaultValue) {
        String normalized = normalize(value);
        return normalized == null ? defaultValue : normalized;
    }

    private String resolveGoal(RecipeGenerateRequest request) {
        String goal = normalize(request.goal());
        if (goal != null || request.dietPreference() == null) {
            return goal;
        }
        String preferenceGoal = normalize(request.dietPreference().defaultGoal());
        if (preferenceGoal == null || preferenceGoal.length() <= MAX_PREFERENCE_GOAL_LENGTH) {
            return preferenceGoal;
        }
        return preferenceGoal.substring(0, MAX_PREFERENCE_GOAL_LENGTH);
    }

    private String normalizeAnonymousId(String value) {
        String normalized = normalize(value);
        if (normalized == null) {
            return null;
        }
        return normalized.length() <= 64 ? normalized : normalized.substring(0, 64);
    }

    private List<String> resultIngredients(RecipeGenerateResponse response) {
        if (response == null || response.ingredients() == null) {
            return List.of();
        }
        return response.ingredients().stream()
                .map(RecipeGenerateResponse.Ingredient::name)
                .filter(this::hasText)
                .flatMap(value -> ingredientNormalizer.normalizeDistinct(value).stream())
                .map(IngredientNormalizer.NormalizedIngredient::canonicalName)
                .map(value -> limitText(value, 64))
                .distinct()
                .limit(30)
                .toList();
    }

    private String limitText(String value, int maxLength) {
        if (!hasText(value)) {
            return null;
        }
        String normalized = value.trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
