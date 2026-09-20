package com.example.food.recipe;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.food.ai.recipe.dto.RecipeGenerateResponse;
import com.example.food.recipe.dto.RecipeHistoryDetailResponse;
import com.example.food.recipe.dto.RecipeHistorySummaryResponse;
import com.example.food.recipe.dto.SaveRecipeRequest;
import com.example.food.security.AppRole;
import com.example.food.security.AuthPrincipal;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class SavedRecipeService {

    private final SearchLogMapper searchLogMapper;
    private final RecipeRecordMapper recipeRecordMapper;
    private final RecipeIngredientMapper recipeIngredientMapper;
    private final RecipeStepMapper recipeStepMapper;
    private final ObjectMapper objectMapper;

    public SavedRecipeService(
            SearchLogMapper searchLogMapper,
            RecipeRecordMapper recipeRecordMapper,
            RecipeIngredientMapper recipeIngredientMapper,
            RecipeStepMapper recipeStepMapper,
            ObjectMapper objectMapper
    ) {
        this.searchLogMapper = searchLogMapper;
        this.recipeRecordMapper = recipeRecordMapper;
        this.recipeIngredientMapper = recipeIngredientMapper;
        this.recipeStepMapper = recipeStepMapper;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public RecipeHistoryDetailResponse save(
            SaveRecipeRequest request,
            AuthPrincipal principal,
            String anonymousId
    ) {
        return save(request, principal, anonymousId, null);
    }

    @Transactional
    public RecipeHistoryDetailResponse save(
            SaveRecipeRequest request,
            AuthPrincipal principal,
            String anonymousId,
            String agentIdempotencyKey
    ) {
        requireUser(principal);
        String normalizedIdempotencyKey = normalizeAgentIdempotencyKey(agentIdempotencyKey);
        if (normalizedIdempotencyKey != null) {
            RecipeRecord existing = recipeRecordMapper.findByUserIdAndAgentIdempotencyKey(
                    principal.id(), normalizedIdempotencyKey);
            if (existing != null) {
                return detail(principal.id(), existing.getId());
            }
        }
        SearchLog searchLog = searchLogMapper.selectById(request.searchLogId());
        if (searchLog == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "搜索记录不存在");
        }
        verifySearchLogOwnership(searchLog, principal, anonymousId);

        // A generated recipe belongs to one search record. Repeated Agent
        // save requests may carry different confirmation/idempotency keys,
        // so the operation key alone cannot prevent duplicate recipe rows.
        // Reuse the existing record for the same user and search result.
        RecipeRecord existingForSearch = recipeRecordMapper.findByUserIdAndSearchLogId(
                principal.id(), request.searchLogId());
        if (existingForSearch != null) {
            return detail(principal.id(), existingForSearch.getId());
        }

        RecipeGenerateResponse recipe = recipeFromRequest(request);
        RecipeGenerateResponse.NutritionEstimate nutrition = validNutrition(request.nutritionEstimate());
        RecipeRecord record = new RecipeRecord();
        record.setUserId(principal.id());
        record.setAgentIdempotencyKey(normalizedIdempotencyKey);
        record.setSearchLogId(request.searchLogId());
        record.setTitle(request.title().trim());
        if (nutrition != null) {
            record.setServings(nutrition.servings());
            record.setCaloriesKcal(nutrition.caloriesKcal());
            record.setProteinG(nutrition.proteinG());
            record.setFatG(nutrition.fatG());
            record.setCarbohydrateG(nutrition.carbohydrateG());
            record.setNutritionSource(RecipeGenerateResponse.NutritionEstimate.AI_ESTIMATE);
        }
        record.setSummary(request.summary());
        record.setEffects(toJson(request.effects()));
        record.setTips(toJson(request.tips()));
        record.setVideoKeywords(toJson(request.videoKeywords()));
        record.setAiModel(modelLabel(request.provider(), request.model()));
        record.setRawResponse(toJson(recipe));
        record.setCreatedAt(LocalDateTime.now());
        try {
            recipeRecordMapper.insert(record);
        } catch (DuplicateKeyException duplicate) {
            if (normalizedIdempotencyKey == null) {
                throw duplicate;
            }
            RecipeRecord existing = recipeRecordMapper.findByUserIdAndAgentIdempotencyKey(
                    principal.id(), normalizedIdempotencyKey);
            if (existing == null) {
                throw duplicate;
            }
            return detail(principal.id(), existing.getId());
        }

        insertIngredients(record.getId(), request.ingredients());
        insertSteps(record.getId(), request.steps());
        return detailResponse(record, searchLog, recipe);
    }

    public List<RecipeHistorySummaryResponse> list(Long userId, int limit, int offset) {
        return list(userId, null, null, null, limit, offset);
    }

    public List<RecipeHistorySummaryResponse> list(
            Long userId,
            String keyword,
            String mealType,
            String goal,
            int limit,
            int offset
    ) {
        validatePage(limit, offset);
        List<RecipeRecord> records = recipeRecordMapper.findSavedRecipes(
                userId,
                normalizeFilter(keyword),
                normalizeFilter(mealType),
                normalizeFilter(goal),
                limit,
                offset
        );
        return records.stream().map(this::summaryResponse).toList();
    }

    @Transactional
    public void delete(Long userId, Long recipeId) {
        if (recipeRecordMapper.deleteOwnedRecipe(recipeId, userId) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "菜谱不存在");
        }
    }

    public RecipeHistoryDetailResponse detail(Long userId, Long recipeId) {
        RecipeRecord record = recipeRecordMapper.selectById(recipeId);
        if (record == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "菜谱不存在");
        }
        if (!userId.equals(record.getUserId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权查看该菜谱");
        }

        SearchLog searchLog = record.getSearchLogId() == null
                ? null
                : searchLogMapper.selectById(record.getSearchLogId());
        List<RecipeIngredient> ingredients = recipeIngredientMapper.selectList(new QueryWrapper<RecipeIngredient>()
                .eq("recipe_id", recipeId)
                .orderByAsc("id"));
        List<RecipeStep> steps = recipeStepMapper.selectList(new QueryWrapper<RecipeStep>()
                .eq("recipe_id", recipeId)
                .orderByAsc("step_no"));
        RecipeGenerateResponse recipe = recipeFromStoredResponse(record);
        if (recipe == null) {
            recipe = recipeFromRecord(record, ingredients, steps);
        }
        return detailResponse(record, searchLog, recipe);
    }

    private void insertIngredients(Long recipeId, List<RecipeGenerateResponse.Ingredient> ingredients) {
        for (RecipeGenerateResponse.Ingredient ingredient : ingredients) {
            RecipeIngredient target = new RecipeIngredient();
            target.setRecipeId(recipeId);
            target.setIngredientName(requireText(ingredient.name(), "食材名称不能为空"));
            target.setAmount(ingredient.amount());
            target.setAlreadyOwned(false);
            recipeIngredientMapper.insert(target);
        }
    }

    private void insertSteps(Long recipeId, List<RecipeGenerateResponse.Step> steps) {
        for (RecipeGenerateResponse.Step step : steps) {
            RecipeStep target = new RecipeStep();
            target.setRecipeId(recipeId);
            target.setStepNo(step.order());
            target.setTitle(step.title());
            target.setInstruction(requireText(step.description(), "烹饪步骤不能为空"));
            target.setEstimatedMinutes(step.durationMinutes());
            recipeStepMapper.insert(target);
        }
    }

    private RecipeHistorySummaryResponse summaryResponse(RecipeRecord record) {
        SearchLog searchLog = record.getSearchLogId() == null
                ? null
                : searchLogMapper.selectById(record.getSearchLogId());
        return new RecipeHistorySummaryResponse(
                record.getId(),
                record.getTitle(),
                searchLog == null ? null : searchLog.getQueryText(),
                searchLog == null ? null : searchLog.getMealType(),
                searchLog == null ? null : searchLog.getGoal(),
                record.getCreatedAt()
        );
    }

    private RecipeHistoryDetailResponse detailResponse(
            RecipeRecord record,
            SearchLog searchLog,
            RecipeGenerateResponse recipe
    ) {
        return new RecipeHistoryDetailResponse(
                record.getId(),
                record.getSearchLogId(),
                record.getCreatedAt(),
                searchLog == null ? null : searchLog.getQueryText(),
                searchLog == null ? null : searchLog.getMealType(),
                searchLog == null ? null : searchLog.getGoal(),
                recipe
        );
    }

    private RecipeGenerateResponse recipeFromRequest(SaveRecipeRequest request) {
        return new RecipeGenerateResponse(
                request.title(),
                request.summary(),
                request.effects(),
                request.ingredients(),
                request.missingIngredients(),
                request.steps(),
                request.tips(),
                request.videoKeywords(),
                request.explanation(),
                validNutrition(request.nutritionEstimate()),
                request.provider(),
                request.model(),
                request.searchLogId()
        );
    }

    private RecipeGenerateResponse recipeFromStoredResponse(RecipeRecord record) {
        if (!StringUtils.hasText(record.getRawResponse())) {
            return null;
        }
        try {
            RecipeGenerateResponse stored = objectMapper.readValue(record.getRawResponse(), RecipeGenerateResponse.class);
            RecipeGenerateResponse.NutritionEstimate persistedNutrition = nutritionFromRecord(record);
            return stored.withSearchLogId(record.getSearchLogId())
                    .withNutritionEstimate(persistedNutrition != null
                            ? persistedNutrition
                            : validNutrition(stored.nutritionEstimate()));
        } catch (JsonProcessingException exception) {
            return null;
        }
    }

    private RecipeGenerateResponse recipeFromRecord(
            RecipeRecord record,
            List<RecipeIngredient> ingredients,
            List<RecipeStep> steps
    ) {
        List<RecipeGenerateResponse.Ingredient> recipeIngredients = ingredients.stream()
                .map(item -> new RecipeGenerateResponse.Ingredient(item.getIngredientName(), item.getAmount()))
                .toList();
        List<RecipeGenerateResponse.Step> recipeSteps = steps.stream()
                .map(item -> new RecipeGenerateResponse.Step(
                        item.getStepNo(),
                        item.getTitle(),
                        item.getInstruction(),
                        item.getEstimatedMinutes()
                ))
                .toList();
        return new RecipeGenerateResponse(
                record.getTitle(),
                record.getSummary(),
                readList(record.getEffects()),
                recipeIngredients,
                List.of(),
                recipeSteps,
                readList(record.getTips()),
                readList(record.getVideoKeywords()),
                RecipeGenerateResponse.Explanation.empty(),
                nutritionFromRecord(record),
                provider(record.getAiModel()),
                model(record.getAiModel()),
                record.getSearchLogId()
        );
    }

    private void verifySearchLogOwnership(SearchLog searchLog, AuthPrincipal principal, String anonymousId) {
        if (searchLog.getUserId() != null && !principal.id().equals(searchLog.getUserId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权使用该搜索记录");
        }
        if (searchLog.getUserId() == null
                && (!StringUtils.hasText(anonymousId)
                || !anonymousId.trim().equals(searchLog.getAnonymousId()))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权使用该搜索记录");
        }
    }

    private void requireUser(AuthPrincipal principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
        }
        if (principal.role() != AppRole.USER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅普通用户可以保存菜谱");
        }
    }

    private void validatePage(int limit, int offset) {
        if (limit < 1 || limit > 50 || offset < 0) {
            throw new IllegalArgumentException("分页参数不合法");
        }
    }

    private String normalizeFilter(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String normalized = value.trim();
        return "不限".equals(normalized) ? null : normalized;
    }

    private String normalizeAgentIdempotencyKey(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > 96) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "幂等键长度不能超过 96 个字符");
        }
        return normalized;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("菜谱内容序列化失败", exception);
        }
    }

    private List<String> readList(String value) {
        if (!StringUtils.hasText(value)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(value, new TypeReference<>() {
            });
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("菜谱内容解析失败", exception);
        }
    }

    private String modelLabel(String provider, String model) {
        return provider.trim() + ":" + model.trim();
    }

    private String provider(String modelLabel) {
        if (!StringUtils.hasText(modelLabel) || !modelLabel.contains(":")) {
            return modelLabel;
        }
        return modelLabel.substring(0, modelLabel.indexOf(':'));
    }

    private String model(String modelLabel) {
        if (!StringUtils.hasText(modelLabel) || !modelLabel.contains(":")) {
            return modelLabel;
        }
        return modelLabel.substring(modelLabel.indexOf(':') + 1);
    }

    private String requireText(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private RecipeGenerateResponse.NutritionEstimate validNutrition(
            RecipeGenerateResponse.NutritionEstimate estimate
    ) {
        return estimate != null && estimate.isValid() ? estimate : null;
    }

    private RecipeGenerateResponse.NutritionEstimate nutritionFromRecord(RecipeRecord record) {
        RecipeGenerateResponse.NutritionEstimate estimate = new RecipeGenerateResponse.NutritionEstimate(
                record.getServings(),
                record.getCaloriesKcal(),
                record.getProteinG(),
                record.getFatG(),
                record.getCarbohydrateG(),
                record.getNutritionSource()
        );
        return estimate.isValid() ? estimate : null;
    }
}
