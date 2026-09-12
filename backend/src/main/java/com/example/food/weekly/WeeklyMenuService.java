package com.example.food.weekly;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
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
import com.example.food.user.preference.dto.DietPreferenceResponse;
import com.example.food.weekly.dto.WeeklyMenuAutoGenerateRequest;
import com.example.food.weekly.dto.WeeklyMenuItemRequest;
import com.example.food.weekly.dto.WeeklyMenuItemResponse;
import com.example.food.weekly.dto.WeeklyMenuResponse;
import com.example.food.weekly.dto.WeeklyMenuSaveRequest;
import com.example.food.weekly.dto.WeeklyMenuShoppingStatusRequest;
import com.example.food.weekly.dto.WeeklyShoppingItemResponse;
import com.example.food.weekly.dto.WeeklyShoppingStatusResponse;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class WeeklyMenuService {

    private static final int AUTO_RECIPE_LIMIT = 50;
    private static final int MAX_CANDIDATE_TITLE_LENGTH = 80;
    private static final int MAX_CANDIDATE_INGREDIENT_COUNT = 12;
    private static final Set<String> BREAKFAST_TITLE_MARKERS = Set.of(
            "包子", "馒头", "花卷", "面条", "炒面", "汤面", "拌面", "粥", "豆浆", "油条",
            "煎饼", "鸡蛋灌饼", "三明治", "吐司", "面包", "燕麦", "米粉", "河粉", "肠粉",
            "烧麦", "小笼包", "饭团", "馄饨", "饺子", "锅贴", "汤圆", "红薯", "玉米",
            "蛋饼", "茶叶蛋", "水煮蛋", "蒸蛋", "蛋羹"
    );
    private static final Set<String> MAIN_MEAL_TITLE_MARKERS = Set.of(
            "蒜蓉", "红烧", "糖醋", "宫保", "鱼香", "回锅", "水煮", "干锅", "爆炒", "清蒸",
            "炒", "炖", "焖", "卤", "肉丝", "肉片", "排骨", "鸡翅", "鸡腿", "牛肉", "茄子"
    );

    private static final Pattern SIMPLE_AMOUNT = Pattern.compile(
            "^\\s*(\\d+(?:\\.\\d+)?|\\d+/\\d+)\\s*(个|克|千克|毫升|升|汤匙|茶匙|片|块|根|瓣|包|把|只|条|枚|杯|碗|勺)\\s*$"
    );

    private final WeeklyMenuPlanMapper planMapper;
    private final WeeklyMenuItemMapper itemMapper;
    private final WeeklyMenuShoppingCheckMapper shoppingCheckMapper;
    private final RecipeRecordMapper recipeRecordMapper;
    private final RecipeIngredientMapper recipeIngredientMapper;
    private final UserPantryService userPantryService;
    private final IngredientNormalizer ingredientNormalizer;
    private final QwenRecipeClient qwenRecipeClient;
    private final UserHealthProfileService userHealthProfileService;
    private final UserNutritionTargetService userNutritionTargetService;
    private final UserDietPreferenceService userDietPreferenceService;
    private final ObjectMapper objectMapper;

    @org.springframework.beans.factory.annotation.Autowired
    public WeeklyMenuService(
            WeeklyMenuPlanMapper planMapper,
            WeeklyMenuItemMapper itemMapper,
            WeeklyMenuShoppingCheckMapper shoppingCheckMapper,
            RecipeRecordMapper recipeRecordMapper,
            RecipeIngredientMapper recipeIngredientMapper,
            UserPantryService userPantryService,
            IngredientNormalizer ingredientNormalizer,
            QwenRecipeClient qwenRecipeClient,
            UserHealthProfileService userHealthProfileService,
            UserNutritionTargetService userNutritionTargetService,
            UserDietPreferenceService userDietPreferenceService,
            ObjectMapper objectMapper
    ) {
        this.planMapper = planMapper;
        this.itemMapper = itemMapper;
        this.shoppingCheckMapper = shoppingCheckMapper;
        this.recipeRecordMapper = recipeRecordMapper;
        this.recipeIngredientMapper = recipeIngredientMapper;
        this.userPantryService = userPantryService;
        this.ingredientNormalizer = ingredientNormalizer;
        this.qwenRecipeClient = qwenRecipeClient;
        this.userHealthProfileService = userHealthProfileService;
        this.userNutritionTargetService = userNutritionTargetService;
        this.userDietPreferenceService = userDietPreferenceService;
        this.objectMapper = objectMapper;
    }

    public WeeklyMenuService(
            WeeklyMenuPlanMapper planMapper,
            WeeklyMenuItemMapper itemMapper,
            WeeklyMenuShoppingCheckMapper shoppingCheckMapper,
            RecipeRecordMapper recipeRecordMapper,
            RecipeIngredientMapper recipeIngredientMapper,
            UserPantryService userPantryService,
            IngredientNormalizer ingredientNormalizer,
            QwenRecipeClient qwenRecipeClient,
            UserHealthProfileService userHealthProfileService,
            UserDietPreferenceService userDietPreferenceService,
            ObjectMapper objectMapper
    ) {
        this(
                planMapper,
                itemMapper,
                shoppingCheckMapper,
                recipeRecordMapper,
                recipeIngredientMapper,
                userPantryService,
                ingredientNormalizer,
                qwenRecipeClient,
                userHealthProfileService,
                null,
                userDietPreferenceService,
                objectMapper
        );
    }

    public WeeklyMenuResponse get(Long userId, LocalDate requestedWeekStart) {
        LocalDate weekStart = normalizeWeekStart(requestedWeekStart == null ? LocalDate.now() : requestedWeekStart);
        WeeklyMenuPlan plan = planMapper.findByUserIdAndWeekStart(userId, weekStart);
        if (plan == null) {
            return emptyResponse(weekStart);
        }
        return toResponse(userId, plan, itemMapper.findByPlanId(plan.getId()));
    }

    @Transactional
    public WeeklyMenuResponse autoGenerate(Long userId, WeeklyMenuAutoGenerateRequest request) {
        LocalDate weekStart = normalizeWeekStart(request.weekStart());
        WeeklyMenuPlan existingPlan = planMapper.findByUserIdAndWeekStart(userId, weekStart);
        if (existingPlan != null && !request.overwrite()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "本周已有菜单，请确认覆盖");
        }

        List<AutoRecipeCandidate> candidates = loadAutoRecipeCandidates(userId);
        if (candidates.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请先保存至少一道菜谱");
        }

        List<QwenRecipeClient.WeeklyMenuSelection> selections = qwenRecipeClient.generateWeeklyMenu(
                buildAutoGeneratePrompt(userId, weekStart, request.mode(), candidates)
        );
        List<WeeklyMenuItemRequest> completedItems = completeAutoSelections(weekStart, selections, candidates);
        return save(userId, new WeeklyMenuSaveRequest(weekStart, completedItems));
    }

    @Transactional
    public WeeklyMenuResponse save(Long userId, WeeklyMenuSaveRequest request) {
        LocalDate weekStart = normalizeWeekStart(request.weekStart());
        List<ValidatedMenuItem> validatedItems = validateItems(userId, weekStart, request.items());
        WeeklyMenuPlan plan = planMapper.findByUserIdAndWeekStart(userId, weekStart);
        if (plan == null) {
            plan = new WeeklyMenuPlan();
            plan.setUserId(userId);
            plan.setWeekStartDate(weekStart);
            plan.setCreatedAt(LocalDateTime.now());
            plan.setUpdatedAt(plan.getCreatedAt());
            try {
                planMapper.insert(plan);
            } catch (DuplicateKeyException exception) {
                plan = planMapper.findByUserIdAndWeekStart(userId, weekStart);
                if (plan == null) {
                    throw exception;
                }
            }
        }

        itemMapper.deleteByPlanId(plan.getId());
        LocalDateTime now = LocalDateTime.now();
        for (ValidatedMenuItem validated : validatedItems) {
            WeeklyMenuItem item = new WeeklyMenuItem();
            item.setPlanId(plan.getId());
            item.setMenuDate(validated.menuDate());
            item.setMealType(validated.mealType().name());
            item.setRecipeId(validated.recipeId());
            item.setCreatedAt(now);
            itemMapper.insert(item);
        }
        plan.setUpdatedAt(now);
        planMapper.updateById(plan);
        return get(userId, weekStart);
    }

    @Transactional
    public WeeklyShoppingStatusResponse saveShoppingStatus(
            Long userId,
            WeeklyMenuShoppingStatusRequest request
    ) {
        LocalDate weekStart = normalizeWeekStart(request.weekStart());
        WeeklyMenuPlan plan = planMapper.findByUserIdAndWeekStart(userId, weekStart);
        if (plan == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "本周还没有保存菜单");
        }

        List<WeeklyShoppingItemResponse> shoppingItems = shoppingItems(userId, plan, itemMapper.findByPlanId(plan.getId()));
        String ingredientName = canonicalIngredient(request.ingredientName());
        String ingredientKey = ingredientKey(ingredientName);
        WeeklyShoppingItemResponse target = shoppingItems.stream()
                .filter(item -> ingredientKey(item.ingredientName()).equals(ingredientKey))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "该食材不在本周采购清单中"));
        ShoppingItemStatus status = ShoppingItemStatus.from(request.status(), null);

        WeeklyMenuShoppingCheck check = shoppingCheckMapper.findByIdentity(
                userId,
                plan.getId(),
                target.ingredientName()
        );
        LocalDateTime now = LocalDateTime.now();
        if (check == null) {
            check = new WeeklyMenuShoppingCheck();
            check.setUserId(userId);
            check.setPlanId(plan.getId());
            check.setIngredientName(target.ingredientName());
            check.setStatus(status.name());
            check.setCreatedAt(now);
            check.setUpdatedAt(now);
            shoppingCheckMapper.insert(check);
        } else {
            check.setStatus(status.name());
            check.setUpdatedAt(now);
            shoppingCheckMapper.updateById(check);
        }
        return new WeeklyShoppingStatusResponse(
                target.ingredientName(),
                target.amount(),
                target.alreadyOwned(),
                status.name()
        );
    }

    @Transactional
    public void delete(Long userId, LocalDate requestedWeekStart) {
        LocalDate weekStart = normalizeWeekStart(requestedWeekStart == null ? LocalDate.now() : requestedWeekStart);
        WeeklyMenuPlan plan = planMapper.findByUserIdAndWeekStart(userId, weekStart);
        if (plan != null) {
            planMapper.deleteById(plan.getId());
        }
    }

    private List<AutoRecipeCandidate> loadAutoRecipeCandidates(Long userId) {
        List<RecipeRecord> records = recipeRecordMapper.findSavedRecipes(
                userId,
                null,
                null,
                null,
                AUTO_RECIPE_LIMIT,
                0
        );
        if (records == null || records.isEmpty()) {
            return List.of();
        }

        Set<Long> recipeIds = records.stream()
                .map(RecipeRecord::getId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Map<Long, List<RecipeIngredient>> ingredientsByRecipe = recipeIngredientMapper
                .selectList(new QueryWrapper<RecipeIngredient>()
                        .in("recipe_id", recipeIds)
                        .orderByAsc("id"))
                .stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        RecipeIngredient::getRecipeId,
                        LinkedHashMap::new,
                        java.util.stream.Collectors.toList()
                ));

        return records.stream()
                .map(record -> {
                    List<String> ingredientNames = ingredientsByRecipe.getOrDefault(record.getId(), List.of()).stream()
                            .map(RecipeIngredient::getIngredientName)
                            .map(this::canonicalIngredient)
                            .filter(name -> !name.isBlank())
                            .distinct()
                            .limit(MAX_CANDIDATE_INGREDIENT_COUNT)
                            .toList();
                    return new AutoRecipeCandidate(
                            record.getId(),
                            limitPromptText(record.getTitle(), MAX_CANDIDATE_TITLE_LENGTH),
                            mealTypeHint(record),
                            ingredientNames
                    );
                })
                .toList();
    }

    private String buildAutoGeneratePrompt(
            Long userId,
            LocalDate weekStart,
            String mode,
            List<AutoRecipeCandidate> candidates
    ) {
        String candidateJson;
        try {
            candidateJson = objectMapper.writeValueAsString(candidates);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("候选菜谱序列化失败", exception);
        }

        boolean pantryMode = WeeklyMenuAutoGenerateRequest.PANTRY.equals(mode);
        String arrangementMode = pantryMode
                ? "根据当前食材库存安排，优先使用库存中已有食材"
                : "随机安排；不参考菜谱生成历史记录和历史偏好，也不参考当前食材库存";
        String pantryContext = pantryMode
                ? safeIngredients(userPantryService.listIngredientNames(userId))
                : "不参考库存";

        return """
                请为用户安排一周家庭菜谱计划，返回内容必须是可解析的 JSON，不要返回 Markdown 或额外解释。
                计划周起始日：%s（周一）
                自动安排模式：%s
                健康档案：%s
                饮食偏好：%s
                每日营养目标：%s
                用户已有食材：%s
                候选菜谱（只能使用其中的 recipeId；mealTypeHint=BREAKFAST 表示适合早餐，MAIN 表示适合午餐或晚餐，ANY 表示未指定）：
                %s

                严格要求：
                1. 为周一至周日每天安排早餐、午餐、晚餐，共 21 条 items。
                2. recipeId 必须来自候选菜谱，禁止虚构 ID 或菜名。
                3. 同一天尽量不要重复同一道菜；候选不足时允许跨天重复。
                4. 早餐优先选择 mealTypeHint=BREAKFAST，其次选择 ANY；应推荐包子、馒头、花卷、面条、粥、豆浆、油条、煎饼、三明治、吐司或燕麦等早餐主食和轻食，不要把蒜蓉炒菜、红烧肉等典型午餐或晚餐菜安排为早餐。
                5. 午餐和晚餐优先选择 mealTypeHint=MAIN 或 ANY；候选不足时才使用其他候选，不能因此虚构 recipeId。
                6. %s。
                7. 结合健康档案和饮食偏好安排，避免使用明确忌口或过敏食材。
                8. 每日营养目标只是软偏好，用于平衡一周菜谱搭配；明确忌口和过敏食材优先级更高。
                9. 只输出以下结构：{"items":[{"menuDate":"YYYY-MM-DD","mealType":"BREAKFAST|LUNCH|DINNER","recipeId":1}]}。
                10. 健康档案和营养目标只用于一般饮食推荐，不得输出疾病诊断、治疗方案或疗效保证。
                """.formatted(
                weekStart,
                arrangementMode,
                healthProfilePrompt(userId),
                dietPreferencePrompt(userId),
                nutritionTargetPrompt(userId),
                pantryContext,
                candidateJson,
                pantryMode ? "结合用户已有食材安排" : "按随机组合安排"
        ).strip();
    }

    private List<WeeklyMenuItemRequest> completeAutoSelections(
            LocalDate weekStart,
            List<QwenRecipeClient.WeeklyMenuSelection> selections,
            List<AutoRecipeCandidate> candidates
    ) {
        Set<Long> candidateIds = candidates.stream()
                .map(AutoRecipeCandidate::recipeId)
                .collect(java.util.stream.Collectors.toSet());
        LinkedHashMap<String, Long> selectedBySlot = new LinkedHashMap<>();
        List<QwenRecipeClient.WeeklyMenuSelection> safeSelections = selections == null ? List.of() : selections;
        for (QwenRecipeClient.WeeklyMenuSelection selection : safeSelections) {
            if (selection == null || selection.recipeId() == null || !candidateIds.contains(selection.recipeId())) {
                continue;
            }
            LocalDate menuDate;
            try {
                menuDate = LocalDate.parse(selection.menuDate());
            } catch (RuntimeException exception) {
                continue;
            }
            if (menuDate.isBefore(weekStart) || menuDate.isAfter(weekStart.plusDays(6))) {
                continue;
            }
            WeeklyMealType mealType;
            try {
                mealType = WeeklyMealType.from(selection.mealType());
            } catch (IllegalArgumentException exception) {
                continue;
            }
            selectedBySlot.putIfAbsent(menuDate + "|" + mealType.name(), selection.recipeId());
        }
        if (selectedBySlot.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "千问未返回有效的周菜单安排");
        }

        List<WeeklyMenuItemRequest> completed = new ArrayList<>();
        int fallbackIndex = 0;
        for (int day = 0; day < 7; day++) {
            LocalDate menuDate = weekStart.plusDays(day);
            Set<Long> usedOnDay = new HashSet<>();
            for (WeeklyMealType mealType : WeeklyMealType.values()) {
                String slot = menuDate + "|" + mealType.name();
                Long recipeId = selectedBySlot.get(slot);
                if (recipeId == null) {
                    recipeId = fallbackRecipe(candidates, usedOnDay, fallbackIndex++, mealType);
                }
                usedOnDay.add(recipeId);
                completed.add(new WeeklyMenuItemRequest(menuDate, mealType.name(), recipeId));
            }
        }
        return completed;
    }

    private Long fallbackRecipe(
            List<AutoRecipeCandidate> candidates,
            Set<Long> usedOnDay,
            int startIndex,
            WeeklyMealType mealType
    ) {
        List<Long> preferredIds = candidates.stream()
                .filter(candidate -> isPreferredForMeal(candidate, mealType))
                .map(AutoRecipeCandidate::recipeId)
                .toList();
        Long preferred = firstAvailableRecipe(preferredIds, usedOnDay, startIndex);
        return preferred != null
                ? preferred
                : firstAvailableRecipe(candidates.stream().map(AutoRecipeCandidate::recipeId).toList(), usedOnDay, startIndex);
    }

    private boolean isPreferredForMeal(AutoRecipeCandidate candidate, WeeklyMealType mealType) {
        String hint = candidate.mealTypeHint();
        if (mealType == WeeklyMealType.BREAKFAST) {
            return "BREAKFAST".equals(hint) || "ANY".equals(hint);
        }
        return "MAIN".equals(hint) || "ANY".equals(hint);
    }

    private Long firstAvailableRecipe(List<Long> candidateIds, Set<Long> usedOnDay, int startIndex) {
        if (candidateIds.isEmpty()) {
            return null;
        }
        for (int offset = 0; offset < candidateIds.size(); offset++) {
            Long candidate = candidateIds.get((startIndex + offset) % candidateIds.size());
            if (candidateIds.size() == 1 || !usedOnDay.contains(candidate)) {
                return candidate;
            }
        }
        return candidateIds.get(startIndex % candidateIds.size());
    }

    private String healthProfilePrompt(Long userId) {
        UserHealthProfileService.RecommendationContext profile =
                userHealthProfileService.getRecommendationContext(userId);
        if (profile == null) {
            return "未填写";
        }
        return "年龄段=" + ageRangeLabel(profile.ageRange())
                + "，身高=" + formatNumber(profile.heightCm()) + "厘米"
                + "，体重=" + formatNumber(profile.weightKg()) + "千克"
                + "，BMI=" + formatNumber(profile.bmi())
                + "，活动量=" + activityLevelLabel(profile.activityLevel());
    }

    private String dietPreferencePrompt(Long userId) {
        DietPreferenceResponse preference = userDietPreferenceService.get(userId);
        if (preference == null) {
            preference = DietPreferenceResponse.empty();
        }
        return "口味=" + preference.taste()
                + "，目标=" + preference.defaultGoal()
                + "，忌口=" + safeIngredients(preference.avoidIngredients())
                + "，过敏食材=" + safeIngredients(preference.allergenIngredients());
    }

    private String nutritionTargetPrompt(Long userId) {
        if (userNutritionTargetService == null) {
            return "未设置";
        }
        try {
            UserNutritionTargetService.RecommendationContext target =
                    userNutritionTargetService.getRecommendationContext(userId);
            if (target == null) {
                return "未设置";
            }
            return "每日热量=" + formatNumber(target.caloriesKcal()) + "千卡"
                    + "，蛋白质=" + formatNumber(target.proteinG()) + "克"
                    + "，脂肪=" + formatNumber(target.fatG()) + "克"
                    + "，碳水=" + formatNumber(target.carbohydrateG()) + "克"
                    + "（软偏好，仅供一般饮食参考）";
        } catch (RuntimeException exception) {
            return "未设置";
        }
    }

    private String ageRangeLabel(String value) {
        return switch (value == null ? "" : value) {
            case "AGE_18_29" -> "18-29岁";
            case "AGE_30_44" -> "30-44岁";
            case "AGE_45_59" -> "45-59岁";
            case "AGE_60_PLUS" -> "60岁及以上";
            default -> "未填写";
        };
    }

    private String activityLevelLabel(String value) {
        return switch (value == null ? "" : value) {
            case "LOW" -> "低";
            case "MODERATE" -> "中等";
            case "HIGH" -> "高";
            default -> "未填写";
        };
    }

    private String formatNumber(BigDecimal value) {
        return value == null ? "未填写" : value.stripTrailingZeros().toPlainString();
    }

    private String safeIngredients(List<String> ingredients) {
        if (ingredients == null || ingredients.isEmpty()) {
            return "未指定";
        }
        String joined = ingredients.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .limit(30)
                .collect(java.util.stream.Collectors.joining("、"));
        return joined.isBlank() ? "未指定" : joined;
    }

    private String limitPromptText(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return "未命名菜谱";
        }
        String normalized = value.trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }

    private String mealTypeHint(RecipeRecord record) {
        String title = record.getTitle() == null ? "" : record.getTitle().trim();
        if (containsAny(title, BREAKFAST_TITLE_MARKERS)) {
            return "BREAKFAST";
        }
        if (containsAny(title, MAIN_MEAL_TITLE_MARKERS)) {
            return "MAIN";
        }
        String recordedMealType = record.getMealType() == null
                ? ""
                : record.getMealType().trim().toLowerCase(Locale.ROOT);
        if ("breakfast".equals(recordedMealType)) {
            return "BREAKFAST";
        }
        if ("lunch".equals(recordedMealType) || "dinner".equals(recordedMealType)) {
            return "MAIN";
        }
        return "ANY";
    }

    private boolean containsAny(String value, Set<String> markers) {
        return markers.stream().anyMatch(value::contains);
    }

    private record AutoRecipeCandidate(
            Long recipeId,
            String title,
            String mealTypeHint,
            List<String> ingredients
    ) {
    }

    private List<ValidatedMenuItem> validateItems(
            Long userId,
            LocalDate weekStart,
            List<WeeklyMenuItemRequest> requests
    ) {
        if (requests == null || requests.isEmpty()) {
            return List.of();
        }
        LocalDate weekEnd = weekStart.plusDays(6);
        Set<String> slots = new LinkedHashSet<>();
        Set<Long> recipeIds = new LinkedHashSet<>();
        for (WeeklyMenuItemRequest request : requests) {
            if (request == null || request.menuDate() == null || request.recipeId() == null) {
                throw new IllegalArgumentException("菜单餐次信息不完整");
            }
            if (request.menuDate().isBefore(weekStart) || request.menuDate().isAfter(weekEnd)) {
                throw new IllegalArgumentException("菜单日期必须位于所选周内");
            }
            WeeklyMealType mealType = WeeklyMealType.from(request.mealType());
            String slot = request.menuDate() + "|" + mealType.name();
            if (!slots.add(slot)) {
                throw new IllegalArgumentException("同一天的同一餐次不能重复安排");
            }
            recipeIds.add(request.recipeId());
        }

        List<RecipeRecord> records = recipeRecordMapper.selectList(new QueryWrapper<RecipeRecord>()
                .eq("user_id", userId)
                .in("id", recipeIds));
        Map<Long, RecipeRecord> recordById = records.stream()
                .collect(java.util.stream.Collectors.toMap(RecipeRecord::getId, record -> record));
        if (recordById.size() != recipeIds.size()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "所选菜谱不存在或无权使用");
        }

        return requests.stream()
                .map(request -> new ValidatedMenuItem(
                        request.menuDate(),
                        WeeklyMealType.from(request.mealType()),
                        request.recipeId()
                ))
                .toList();
    }

    private WeeklyMenuResponse toResponse(Long userId, WeeklyMenuPlan plan, List<WeeklyMenuItem> menuItems) {
        Map<Long, RecipeRecord> recipes = recipeRecords(userId, menuItems);
        List<WeeklyMenuItemResponse> itemResponses = menuItems.stream()
                .map(item -> {
                    RecipeRecord recipe = recipes.get(item.getRecipeId());
                    return new WeeklyMenuItemResponse(
                            item.getId(),
                            item.getMenuDate(),
                            item.getMealType(),
                            item.getRecipeId(),
                            recipe == null ? "菜谱不可用" : recipe.getTitle()
                    );
                })
                .toList();
        return new WeeklyMenuResponse(
                plan.getId(),
                plan.getWeekStartDate(),
                plan.getWeekStartDate().plusDays(6),
                itemResponses,
                shoppingItems(userId, plan, menuItems),
                nutritionSummary(plan.getWeekStartDate(), menuItems, recipes)
        );
    }

    private Map<Long, RecipeRecord> recipeRecords(Long userId, List<WeeklyMenuItem> menuItems) {
        Set<Long> recipeIds = menuItems.stream()
                .map(WeeklyMenuItem::getRecipeId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (recipeIds.isEmpty()) {
            return Map.of();
        }
        return recipeRecordMapper.selectList(new QueryWrapper<RecipeRecord>()
                        .eq("user_id", userId)
                        .in("id", recipeIds)).stream()
                .collect(java.util.stream.Collectors.toMap(RecipeRecord::getId, record -> record));
    }

    private List<WeeklyShoppingItemResponse> shoppingItems(
            Long userId,
            WeeklyMenuPlan plan,
            List<WeeklyMenuItem> menuItems
    ) {
        if (menuItems.isEmpty()) {
            return List.of();
        }
        Map<Long, List<RecipeIngredient>> ingredientsByRecipe = recipeIngredientsByRecipe(menuItems);
        LinkedHashMap<String, IngredientAggregate> aggregates = new LinkedHashMap<>();
        for (WeeklyMenuItem menuItem : menuItems) {
            for (RecipeIngredient ingredient : ingredientsByRecipe.getOrDefault(menuItem.getRecipeId(), List.of())) {
                String name = canonicalIngredient(ingredient.getIngredientName());
                if (name.isBlank()) {
                    continue;
                }
                aggregates.computeIfAbsent(ingredientKey(name), key -> new IngredientAggregate(name))
                        .addAmount(ingredient.getAmount());
            }
        }

        Set<String> ownedKeys = userPantryService.listIngredientNames(userId).stream()
                .map(this::canonicalIngredient)
                .map(this::ingredientKey)
                .filter(key -> !key.isBlank())
                .collect(java.util.stream.Collectors.toSet());
        Map<String, ShoppingItemStatus> savedStatuses = shoppingCheckMapper
                .findByUserIdAndPlanId(userId, plan.getId())
                .stream()
                .collect(java.util.stream.Collectors.toMap(
                        check -> ingredientKey(check.getIngredientName()),
                        check -> ShoppingItemStatus.from(check.getStatus(), null),
                        (first, ignored) -> first
                ));

        return aggregates.values().stream()
                .map(aggregate -> {
                    boolean alreadyOwned = ownedKeys.contains(ingredientKey(aggregate.name()));
                    ShoppingItemStatus status = savedStatuses.getOrDefault(
                            ingredientKey(aggregate.name()),
                            alreadyOwned ? ShoppingItemStatus.READY : ShoppingItemStatus.PENDING
                    );
                    return new WeeklyShoppingItemResponse(
                            aggregate.name(),
                            aggregate.renderAmount(),
                            alreadyOwned,
                            status.name()
                    );
                })
                .toList();
    }

    private Map<Long, List<RecipeIngredient>> recipeIngredientsByRecipe(List<WeeklyMenuItem> menuItems) {
        Set<Long> recipeIds = menuItems.stream()
                .map(WeeklyMenuItem::getRecipeId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<RecipeIngredient> ingredients = recipeIngredientMapper.selectList(new QueryWrapper<RecipeIngredient>()
                .in("recipe_id", recipeIds)
                .orderByAsc("id"));
        return ingredients.stream().collect(java.util.stream.Collectors.groupingBy(
                RecipeIngredient::getRecipeId,
                LinkedHashMap::new,
                java.util.stream.Collectors.toList()
        ));
    }

    private WeeklyMenuResponse emptyResponse(LocalDate weekStart) {
        return new WeeklyMenuResponse(
                null,
                weekStart,
                weekStart.plusDays(6),
                List.of(),
                List.of(),
                nutritionSummary(weekStart, List.of(), Map.of())
        );
    }

    private WeeklyMenuResponse.NutritionSummary nutritionSummary(
            LocalDate weekStart,
            List<WeeklyMenuItem> menuItems,
            Map<Long, RecipeRecord> recipes
    ) {
        List<LocalDate> dates = java.util.stream.IntStream.range(0, 7)
                .mapToObj(weekStart::plusDays)
                .toList();
        Map<LocalDate, NutritionAccumulator> daily = new LinkedHashMap<>();
        dates.forEach(date -> daily.put(date, new NutritionAccumulator()));
        NutritionAccumulator weekly = new NutritionAccumulator();
        for (WeeklyMenuItem item : menuItems) {
            NutritionAccumulator accumulator = daily.computeIfAbsent(item.getMenuDate(), ignored -> new NutritionAccumulator());
            accumulator.assignedMealCount++;
            RecipeRecord recipe = recipes.get(item.getRecipeId());
            RecipeGenerateResponse.NutritionEstimate nutrition = recipe == null ? null : nutritionFromRecord(recipe);
            if (nutrition != null) {
                accumulator.add(nutrition);
                weekly.add(nutrition);
            }
        }
        List<WeeklyMenuResponse.DailyNutritionSummary> dailySummaries = daily.entrySet().stream()
                .map(entry -> new WeeklyMenuResponse.DailyNutritionSummary(
                        entry.getKey(),
                        entry.getValue().assignedMealCount,
                        entry.getValue().validEstimateMealCount,
                        entry.getValue().totals()
                ))
                .toList();
        return new WeeklyMenuResponse.NutritionSummary(
                menuItems.size(),
                weekly.validEstimateMealCount,
                dailySummaries,
                weekly.totals()
        );
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

    private static final class NutritionAccumulator {
        private int assignedMealCount;
        private int validEstimateMealCount;
        private BigDecimal caloriesKcal = BigDecimal.ZERO;
        private BigDecimal proteinG = BigDecimal.ZERO;
        private BigDecimal fatG = BigDecimal.ZERO;
        private BigDecimal carbohydrateG = BigDecimal.ZERO;

        private void add(RecipeGenerateResponse.NutritionEstimate nutrition) {
            validEstimateMealCount++;
            caloriesKcal = caloriesKcal.add(nutrition.caloriesKcal());
            proteinG = proteinG.add(nutrition.proteinG());
            fatG = fatG.add(nutrition.fatG());
            carbohydrateG = carbohydrateG.add(nutrition.carbohydrateG());
        }

        private WeeklyMenuResponse.NutritionTotals totals() {
            return validEstimateMealCount == 0
                    ? null
                    : new WeeklyMenuResponse.NutritionTotals(
                            caloriesKcal,
                            proteinG,
                            fatG,
                            carbohydrateG
                    );
        }
    }

    private LocalDate normalizeWeekStart(LocalDate date) {
        return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    private String canonicalIngredient(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return ingredientNormalizer.normalizeDistinct(value).stream()
                .findFirst()
                .map(IngredientNormalizer.NormalizedIngredient::canonicalName)
                .orElse("")
                .trim();
    }

    private String ingredientKey(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private record ValidatedMenuItem(
            LocalDate menuDate,
            WeeklyMealType mealType,
            Long recipeId
    ) {
    }

    private static final class IngredientAggregate {
        private final String name;
        private final AmountAccumulator amount = new AmountAccumulator();

        private IngredientAggregate(String name) {
            this.name = name;
        }

        private void addAmount(String value) {
            amount.add(value);
        }

        private String renderAmount() {
            return amount.render();
        }

        private String name() {
            return name;
        }
    }

    private static final class AmountAccumulator {
        private final List<String> rawAmounts = new ArrayList<>();
        private BigDecimal numericTotal;
        private String unit;
        private boolean summable = true;

        private void add(String rawValue) {
            String value = rawValue == null ? "" : rawValue.trim();
            if (value.isBlank()) {
                return;
            }
            if (!rawAmounts.contains(value)) {
                rawAmounts.add(value);
            }
            Matcher matcher = SIMPLE_AMOUNT.matcher(value);
            if (!matcher.matches()) {
                summable = false;
                return;
            }
            BigDecimal number = parseNumber(matcher.group(1));
            String nextUnit = matcher.group(2);
            if (number == null || (unit != null && !unit.equals(nextUnit))) {
                summable = false;
                return;
            }
            unit = nextUnit;
            numericTotal = numericTotal == null ? number : numericTotal.add(number);
        }

        private String render() {
            if (rawAmounts.isEmpty()) {
                return "未注明";
            }
            if (summable && numericTotal != null && unit != null) {
                return formatNumber(numericTotal) + unit;
            }
            return String.join("、", rawAmounts);
        }

        private static BigDecimal parseNumber(String value) {
            try {
                if (!value.contains("/")) {
                    return new BigDecimal(value);
                }
                String[] parts = value.split("/");
                return new BigDecimal(parts[0]).divide(new BigDecimal(parts[1]), 4, RoundingMode.HALF_UP);
            } catch (ArithmeticException | NumberFormatException exception) {
                return null;
            }
        }

        private static String formatNumber(BigDecimal value) {
            return value.stripTrailingZeros().toPlainString();
        }
    }
}
