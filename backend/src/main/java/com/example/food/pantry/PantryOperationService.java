package com.example.food.pantry;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.food.pantry.dto.CookingConsumptionRequest;
import com.example.food.pantry.dto.CookingPreviewResponse;
import com.example.food.pantry.dto.PantryOperationResponse;
import com.example.food.pantry.dto.StockInRequest;
import com.example.food.memory.MemoryBehaviorEpisodeEvent;
import com.example.food.memory.MemoryBehaviorEpisodeRecorder;
import com.example.food.recipe.RecipeIngredient;
import com.example.food.recipe.RecipeIngredientMapper;
import com.example.food.recipe.RecipeRecord;
import com.example.food.recipe.RecipeRecordMapper;
import com.example.food.shopping.ShoppingItemCheck;
import com.example.food.shopping.ShoppingItemCheckMapper;
import com.example.food.shopping.ShoppingItemStatus;
import com.example.food.weekly.WeeklyMenuItem;
import com.example.food.weekly.WeeklyMenuItemMapper;
import com.example.food.weekly.WeeklyMenuPlan;
import com.example.food.weekly.WeeklyMenuPlanMapper;
import com.example.food.weekly.WeeklyMenuShoppingCheck;
import com.example.food.weekly.WeeklyMenuShoppingCheckMapper;
import com.example.food.stats.IngredientNormalizer;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class PantryOperationService {

    private static final int MAX_OPERATION_LIMIT = 50;
    private static final int UNDO_WINDOW_MINUTES = 5;

    private final PantryOperationMapper operationMapper;
    private final PantryOperationItemMapper operationItemMapper;
    private final UserPantryItemMapper pantryMapper;
    private final RecipeRecordMapper recipeMapper;
    private final RecipeIngredientMapper ingredientMapper;
    private final WeeklyMenuPlanMapper weeklyPlanMapper;
    private final WeeklyMenuItemMapper weeklyItemMapper;
    private final WeeklyMenuShoppingCheckMapper weeklyCheckMapper;
    private final ShoppingItemCheckMapper shoppingCheckMapper;
    private final IngredientNormalizer ingredientNormalizer;
    private final MemoryBehaviorEpisodeRecorder memoryEpisodeRecorder;
    private final IngredientAmountParser amountParser;
    private final Clock clock;

    @Autowired
    public PantryOperationService(
            PantryOperationMapper operationMapper,
            PantryOperationItemMapper operationItemMapper,
            UserPantryItemMapper pantryMapper,
            RecipeRecordMapper recipeMapper,
            RecipeIngredientMapper ingredientMapper,
            WeeklyMenuPlanMapper weeklyPlanMapper,
            WeeklyMenuItemMapper weeklyItemMapper,
            WeeklyMenuShoppingCheckMapper weeklyCheckMapper,
            ShoppingItemCheckMapper shoppingCheckMapper,
            IngredientNormalizer ingredientNormalizer,
            MemoryBehaviorEpisodeRecorder memoryEpisodeRecorder
    ) {
        this(operationMapper, operationItemMapper, pantryMapper, recipeMapper, ingredientMapper,
                weeklyPlanMapper, weeklyItemMapper, weeklyCheckMapper, shoppingCheckMapper,
                ingredientNormalizer, memoryEpisodeRecorder, new IngredientAmountParser(), Clock.systemDefaultZone());
    }

    PantryOperationService(
            PantryOperationMapper operationMapper,
            PantryOperationItemMapper operationItemMapper,
            UserPantryItemMapper pantryMapper,
            RecipeRecordMapper recipeMapper,
            RecipeIngredientMapper ingredientMapper,
            WeeklyMenuPlanMapper weeklyPlanMapper,
            WeeklyMenuItemMapper weeklyItemMapper,
            WeeklyMenuShoppingCheckMapper weeklyCheckMapper,
            ShoppingItemCheckMapper shoppingCheckMapper,
            IngredientNormalizer ingredientNormalizer,
            IngredientAmountParser amountParser,
            Clock clock
    ) {
        this(operationMapper, operationItemMapper, pantryMapper, recipeMapper, ingredientMapper,
                weeklyPlanMapper, weeklyItemMapper, weeklyCheckMapper, shoppingCheckMapper,
                ingredientNormalizer, null, amountParser, clock);
    }

    PantryOperationService(
            PantryOperationMapper operationMapper,
            PantryOperationItemMapper operationItemMapper,
            UserPantryItemMapper pantryMapper,
            RecipeRecordMapper recipeMapper,
            RecipeIngredientMapper ingredientMapper,
            WeeklyMenuPlanMapper weeklyPlanMapper,
            WeeklyMenuItemMapper weeklyItemMapper,
            WeeklyMenuShoppingCheckMapper weeklyCheckMapper,
            ShoppingItemCheckMapper shoppingCheckMapper,
            IngredientNormalizer ingredientNormalizer,
            MemoryBehaviorEpisodeRecorder memoryEpisodeRecorder,
            IngredientAmountParser amountParser,
            Clock clock
    ) {
        this.operationMapper = operationMapper;
        this.operationItemMapper = operationItemMapper;
        this.pantryMapper = pantryMapper;
        this.recipeMapper = recipeMapper;
        this.ingredientMapper = ingredientMapper;
        this.weeklyPlanMapper = weeklyPlanMapper;
        this.weeklyItemMapper = weeklyItemMapper;
        this.weeklyCheckMapper = weeklyCheckMapper;
        this.shoppingCheckMapper = shoppingCheckMapper;
        this.ingredientNormalizer = ingredientNormalizer;
        this.memoryEpisodeRecorder = memoryEpisodeRecorder;
        this.amountParser = amountParser;
        this.clock = clock;
    }

    public CookingPreviewResponse cookingPreview(Long userId, Long recipeId, Integer requestedServings) {
        RecipeRecord recipe = ownedRecipe(userId, recipeId);
        List<RecipeIngredient> ingredients = recipeIngredients(recipeId);
        int defaultServings = validServings(recipe.getServings()) ? recipe.getServings() : 0;
        int actualServings = requestedServings == null ? defaultServings : requestedServings;
        validateServings(actualServings);

        List<CookingPreviewResponse.Item> items = new ArrayList<>();
        for (RecipeIngredient ingredient : ingredients) {
            String name = canonicalName(ingredient.getIngredientName());
            IngredientAmountParser.ParsedAmount parsed = amountParser.parse(ingredient.getAmount());
            IngredientAmountParser.ParsedAmount scaled = defaultServings > 0
                    ? amountParser.scale(parsed, actualServings, defaultServings)
                    : new IngredientAmountParser.ParsedAmount(IngredientAmountParser.Status.UNQUANTIFIED, null, null, ingredient.getAmount());
            List<UserPantryItem> available = name.isBlank() ? List.of() : availableItems(userId, name);
            List<CookingPreviewResponse.Batch> batches = new ArrayList<>();
            BigDecimal availableQuantity = BigDecimal.ZERO;
            String availableUnit = scaled.unit();
            if (scaled.isParsed()) {
                for (UserPantryItem item : available) {
                    if (!amountParser.compatible(scaled.unit(), item.getUnit())) continue;
                    BigDecimal converted = amountParser.convert(item.getQuantity(), item.getUnit(), scaled.unit());
                    if (converted == null) continue;
                    availableQuantity = availableQuantity.add(converted);
                    batches.add(new CookingPreviewResponse.Batch(item.getId(), item.getQuantity(), item.getUnit(), item.getExpireDate()));
                }
            }
            String status;
            String message;
            if (!scaled.isParsed()) {
                status = parsed.status() == IngredientAmountParser.Status.UNQUANTIFIED ? "UNQUANTIFIED" : "INVALID";
                message = "用量无法安全解析，请在完成烹饪时手动填写或跳过";
            } else if (!available.isEmpty() && availableQuantity.signum() >= 0
                    && availableQuantity.compareTo(scaled.quantity()) >= 0) {
                status = "ENOUGH";
                message = "库存充足，将按最早到期批次扣减";
            } else if (hasExpiredOnly(userId, name)) {
                status = "EXPIRED_ONLY";
                message = "仅找到已过期库存，不会自动使用";
            } else if (!batches.isEmpty()) {
                status = "PARTIAL";
                message = "库存不足，可调整扣减数量或先采购";
            } else if (!available.isEmpty()) {
                status = "UNIT_MISMATCH";
                message = "库存单位不兼容，无法自动换算";
            } else {
                status = "MISSING";
                message = "未找到可用库存";
            }
            items.add(new CookingPreviewResponse.Item(
                    name,
                    ingredient.getAmount(),
                    scaled.quantity(),
                    scaled.unit(),
                    scaled.isParsed() ? availableQuantity : null,
                    availableUnit,
                    status,
                    message,
                    List.copyOf(batches)
            ));
        }
        int enough = (int) items.stream().filter(item -> "ENOUGH".equals(item.status())).count();
        int pending = items.size() - enough;
        return new CookingPreviewResponse(recipeId, recipe.getTitle(), defaultServings, actualServings,
                items.size(), enough, pending, List.copyOf(items));
    }

    @Transactional
    public PantryOperationResponse stockIn(Long userId, StockInRequest request) {
        if (request == null || request.quantity() == null) throw badRequest("入库数量不能为空");
        String idempotencyKey = requireIdempotencyKey(request.idempotencyKey());
        BigDecimal stockInQuantity = requireTwoDecimalQuantity(request.quantity(), "入库数量必须大于 0 且最多保留两位小数");
        PantryOperation existing = operationMapper.findByIdempotency(userId, PantryOperationType.STOCK_IN.name(), idempotencyKey);
        if (existing != null) return toResponse(userId, existing);
        String name = canonicalRequired(request.ingredientName());
        String unit = requireUnit(request.unit());
        validateSource(userId, request.sourceType(), request.sourceId(), name);
        PantryOperation operation = newOperation(userId, PantryOperationType.STOCK_IN, request.sourceType(), request.sourceId(), idempotencyKey, null);
        try {
            operationMapper.insert(operation);
        } catch (DuplicateKeyException duplicate) {
            return toResponse(userId, operationMapper.findByIdempotency(userId, PantryOperationType.STOCK_IN.name(), idempotencyKey));
        }
        String category = trimToNull(request.category());
        UserPantryItem item = pantryMapper.findByIdentityForUpdate(userId, name, category, request.expireDate());
        BigDecimal beforeQuantity = null;
        if (item == null) {
            item = new UserPantryItem();
            item.setUserId(userId);
            item.setIngredientName(name);
            item.setCategory(category);
            item.setQuantity(stockInQuantity);
            item.setUnit(unit);
            item.setExpireDate(request.expireDate());
            item.setCreatedAt(LocalDateTime.now(clock));
            item.setUpdatedAt(item.getCreatedAt());
            pantryMapper.insert(item);
        } else {
            beforeQuantity = item.getQuantity();
            mergeStockInQuantity(item, stockInQuantity, unit);
            item.setUpdatedAt(LocalDateTime.now(clock));
            pantryMapper.updateById(item);
        }
        insertOperationItem(operation.getId(), item, stockInQuantity, unit, beforeQuantity, item.getQuantity(), request.ingredientName());
        markSourceReady(userId, request.sourceType(), request.sourceId(), name);
        PantryOperationResponse response = toResponse(userId, operationMapper.selectById(operation.getId()));
        Map<String, Object> stockPayload = new LinkedHashMap<>();
        stockPayload.put("operationId", operation.getId());
        stockPayload.put("ingredientName", name);
        stockPayload.put("quantity", stockInQuantity);
        stockPayload.put("unit", unit);
        stockPayload.put("beforeQuantity", beforeQuantity);
        stockPayload.put("afterQuantity", item.getQuantity());
        stockPayload.put("expireDate", request.expireDate());
        stockPayload.put("sourceType", request.sourceType());
        stockPayload.put("sourceId", request.sourceId());
        recordPantryEpisode(
                userId,
                "PANTRY_STOCK_IN",
                operation.getId(),
                "pantry:stock-in:" + operation.getId(),
                "冰箱入库：" + name,
                stockPayload,
                operation.getCreatedAt(),
                new BigDecimal("0.3500")
        );
        return response;
    }

    @Transactional
    public PantryOperationResponse consume(Long userId, CookingConsumptionRequest request) {
        String idempotencyKey = requireIdempotencyKey(request.idempotencyKey());
        PantryOperation existing = operationMapper.findByIdempotency(userId, PantryOperationType.COOKING_CONSUME.name(), idempotencyKey);
        if (existing != null) return toResponse(userId, existing);
        RecipeRecord recipe = ownedRecipe(userId, request.recipeId());
        int actualServings = request.actualServings() == null ? recipe.getServings() == null ? 0 : recipe.getServings() : request.actualServings();
        validateServings(actualServings);
        CookingPreviewResponse preview = cookingPreview(userId, request.recipeId(), actualServings);
        Map<String, CookingConsumptionRequest.Item> requested = new LinkedHashMap<>();
        if (request.items() != null) {
            for (CookingConsumptionRequest.Item item : request.items()) {
                requested.put(canonicalRequired(item.ingredientName()), item);
            }
        }
        PantryOperation operation = newOperation(userId, PantryOperationType.COOKING_CONSUME, "RECIPE", request.recipeId(), idempotencyKey, actualServings);
        try {
            operationMapper.insert(operation);
        } catch (DuplicateKeyException duplicate) {
            return toResponse(userId, operationMapper.findByIdempotency(userId, PantryOperationType.COOKING_CONSUME.name(), idempotencyKey));
        }
        for (CookingPreviewResponse.Item previewItem : preview.items()) {
            CookingConsumptionRequest.Item override = requested.get(previewItem.ingredientName());
            boolean selected = override == null ? "ENOUGH".equals(previewItem.status()) : Boolean.TRUE.equals(override.selected());
            if (!selected) continue;
            boolean explicitQuantity = override != null && override.quantity() != null;
            BigDecimal quantity = explicitQuantity
                    ? requireTwoDecimalQuantity(override.quantity(), "扣减数量必须大于 0 且最多保留两位小数")
                    : roundConsumptionQuantity(previewItem.expectedQuantity());
            String unit = override == null || !StringUtils.hasText(override.unit()) ? previewItem.expectedUnit() : override.unit();
            if (quantity == null || quantity.signum() <= 0 || !StringUtils.hasText(unit)) {
                throw badRequest("已选择的食材必须填写有效扣减数量和单位");
            }
            consumeIngredient(userId, operation, previewItem.ingredientName(), quantity, unit, previewItem.rawAmount());
        }
        PantryOperationResponse response = toResponse(userId, operationMapper.selectById(operation.getId()));
        Map<String, Object> consumePayload = new LinkedHashMap<>();
        consumePayload.put("operationId", operation.getId());
        consumePayload.put("recipeId", request.recipeId());
        consumePayload.put("recipeTitle", recipe.getTitle());
        consumePayload.put("actualServings", actualServings);
        consumePayload.put("items", response.items());
        recordPantryEpisode(
                userId,
                "PANTRY_CONSUMED",
                operation.getId(),
                "pantry:consume:" + operation.getId(),
                "烹饪消耗库存：" + recipe.getTitle(),
                consumePayload,
                operation.getCreatedAt(),
                new BigDecimal("0.7000")
        );
        return response;
    }

    public List<PantryOperationResponse> recent(Long userId, int limit) {
        int safeLimit = Math.max(1, Math.min(MAX_OPERATION_LIMIT, limit));
        return operationMapper.findRecent(userId, safeLimit).stream().map(item -> toResponse(userId, item)).toList();
    }

    @Transactional
    public PantryOperationResponse undo(Long userId, Long operationId, String requestedKey) {
        PantryOperation operation = operationMapper.selectById(operationId);
        if (operation == null || !userId.equals(operation.getUserId())) throw notFound("库存变动记录不存在");
        if (PantryOperationType.REVERSAL.name().equals(operation.getOperationType())) {
            throw badRequest("撤销记录不能再次撤销");
        }
        if (!PantryOperationStatus.COMPLETED.name().equals(operation.getStatus())) throw badRequest("该变动已撤销或不可撤销");
        if (operation.getCreatedAt() == null || operation.getCreatedAt().plusMinutes(UNDO_WINDOW_MINUTES).isBefore(LocalDateTime.now(clock))) {
            throw badRequest("仅支持撤销 5 分钟内的库存变动");
        }
        List<PantryOperationItem> items = operationItemMapper.findByOperationId(operationId);
        for (PantryOperationItem item : items) {
            if (item.getPantryItemId() == null || operationItemMapper.countLaterOperations(userId, item.getPantryItemId(), operation.getCreatedAt(), operation.getId()) > 0) {
                throw badRequest("该库存批次已有后续变动，无法撤销");
            }
            UserPantryItem pantry = pantryMapper.findOwnedForUpdate(userId, item.getPantryItemId());
            if (pantry == null) throw badRequest("库存批次已不存在，无法撤销");
            BigDecimal current = pantry.getQuantity() == null ? BigDecimal.ZERO : pantry.getQuantity();
            BigDecimal delta = amountParser.convert(item.getQuantity(), item.getUnit(), pantry.getUnit());
            if (delta == null) throw badRequest("库存单位不兼容，无法安全撤销");
            BigDecimal next = PantryOperationType.STOCK_IN.name().equals(operation.getOperationType())
                    ? current.subtract(delta) : current.add(delta);
            if (next.signum() < 0) throw badRequest("当前库存不足以安全撤销入库");
        }
        String key = StringUtils.hasText(requestedKey) ? requestedKey.trim() : UUID.randomUUID().toString();
        PantryOperation reversal = newOperation(userId, PantryOperationType.REVERSAL, operation.getOperationType(), operationId, key, operation.getActualServings());
        operationMapper.insert(reversal);
        for (PantryOperationItem item : items) {
            UserPantryItem pantry = pantryMapper.findOwnedForUpdate(userId, item.getPantryItemId());
            BigDecimal current = pantry.getQuantity() == null ? BigDecimal.ZERO : pantry.getQuantity();
            BigDecimal delta = amountParser.convert(item.getQuantity(), item.getUnit(), pantry.getUnit());
            BigDecimal next = PantryOperationType.STOCK_IN.name().equals(operation.getOperationType()) ? current.subtract(delta) : current.add(delta);
            pantry.setQuantity(next.setScale(2, RoundingMode.HALF_UP));
            pantry.setUpdatedAt(LocalDateTime.now(clock));
            pantryMapper.updateById(pantry);
            insertOperationItem(reversal.getId(), pantry, delta, pantry.getUnit(), current, pantry.getQuantity(), item.getRawAmount());
        }
        if (operationMapper.markReversed(operationId, userId, PantryOperationStatus.REVERSED.name(), reversal.getId()) != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该库存变动已被其他操作撤销，请刷新后重试");
        }
        PantryOperationResponse response = toResponse(userId, operationMapper.selectById(reversal.getId()));
        recordPantryEpisode(
                userId,
                "PANTRY_OPERATION_REVERSED",
                reversal.getId(),
                "pantry:reversal:" + reversal.getId(),
                "撤销冰箱变动：" + operationId,
                Map.of(
                        "operationId", operationId,
                        "reversalOperationId", reversal.getId(),
                        "operationType", operation.getOperationType(),
                        "items", response.items()
                ),
                reversal.getCreatedAt(),
                new BigDecimal("0.3000")
        );
        return response;
    }

    private void consumeIngredient(Long userId, PantryOperation operation, String name, BigDecimal requestedQuantity, String requestedUnit, String rawAmount) {
        String targetUnit = amountParser.canonicalUnit(requestedUnit);
        if (targetUnit == null) throw badRequest("扣减单位无法识别");
        BigDecimal remaining = requestedQuantity;
        List<UserPantryItem> batches = pantryMapper.findAvailableForUpdate(userId, name);
        for (UserPantryItem pantry : batches) {
            if (!name.equals(canonicalName(pantry.getIngredientName()))) continue;
            if (pantry.getExpireDate() != null && pantry.getExpireDate().isBefore(LocalDate.now(clock))) continue;
            BigDecimal availableInTarget = amountParser.convert(pantry.getQuantity(), pantry.getUnit(), targetUnit);
            if (availableInTarget == null) continue;
            BigDecimal takeInTarget = availableInTarget.min(remaining);
            BigDecimal takeInPantry = amountParser.convert(takeInTarget, targetUnit, pantry.getUnit());
            if (takeInPantry == null) continue;
            BigDecimal before = pantry.getQuantity();
            BigDecimal after = before.subtract(takeInPantry);
            pantry.setQuantity(after.max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP));
            pantry.setUpdatedAt(LocalDateTime.now(clock));
            pantryMapper.updateById(pantry);
            insertOperationItem(operation.getId(), pantry, takeInPantry, pantry.getUnit(), before, pantry.getQuantity(), rawAmount);
            remaining = remaining.subtract(takeInTarget);
            if (remaining.signum() <= 0) return;
        }
        throw new ResponseStatusException(HttpStatus.CONFLICT, "库存已发生变化或不足，请刷新后重新确认");
    }

    private void mergeStockInQuantity(UserPantryItem item, BigDecimal incomingQuantity, String incomingUnit) {
        if (item.getQuantity() == null) {
            item.setQuantity(incomingQuantity);
            if (!StringUtils.hasText(item.getUnit())) {
                item.setUnit(incomingUnit);
            }
            return;
        }
        BigDecimal addition;
        if (!StringUtils.hasText(item.getUnit())) {
            item.setUnit(incomingUnit);
            addition = incomingQuantity;
        } else {
            addition = amountParser.convert(incomingQuantity, incomingUnit, item.getUnit());
            if (addition == null) {
                throw badRequest("相同食材的库存单位不兼容，无法合并");
            }
        }
        item.setQuantity(item.getQuantity().add(addition).setScale(2, RoundingMode.HALF_UP));
    }

    private void validateSource(Long userId, String sourceType, Long sourceId, String ingredientName) {
        String type = sourceType.trim().toUpperCase(Locale.ROOT);
        if ("RECIPE".equals(type)) {
            RecipeRecord recipe = ownedRecipe(userId, sourceId);
            if (recipeIngredients(recipe.getId()).stream().noneMatch(item -> canonicalName(item.getIngredientName()).equals(ingredientName))) {
                throw notFound("该食材不属于来源菜谱");
            }
            if (recipe.getSearchLogId() != null) {
                ShoppingItemCheck check = shoppingCheckMapper.findByIdentity(userId, recipe.getSearchLogId(), ingredientName);
                requirePurchasedOrReady(check == null ? null : check.getStatus(), check == null || check.isChecked());
            }
            return;
        }
        if ("WEEKLY_MENU".equals(type)) {
            WeeklyMenuPlan plan = weeklyPlanMapper.selectById(sourceId);
            if (plan == null || !userId.equals(plan.getUserId())) throw notFound("周菜单不存在");
            List<WeeklyMenuItem> menuItems = weeklyItemMapper.findByPlanId(sourceId);
            boolean found = menuItems.stream().flatMap(item -> recipeIngredients(item.getRecipeId()).stream())
                    .anyMatch(item -> canonicalName(item.getIngredientName()).equals(ingredientName));
            if (!found) throw notFound("该食材不属于来源周菜单");
            WeeklyMenuShoppingCheck check = weeklyCheckMapper.findByIdentity(userId, sourceId, ingredientName);
            requirePurchasedOrReady(check == null ? null : check.getStatus(), check == null);
            return;
        }
        throw badRequest("不支持的采购来源");
    }

    private void markSourceReady(Long userId, String sourceType, Long sourceId, String name) {
        if ("WEEKLY_MENU".equalsIgnoreCase(sourceType)) {
            WeeklyMenuShoppingCheck check = weeklyCheckMapper.findByIdentity(userId, sourceId, name);
            if (check == null) {
                check = new WeeklyMenuShoppingCheck();
                check.setUserId(userId); check.setPlanId(sourceId); check.setIngredientName(name); check.setCreatedAt(LocalDateTime.now(clock));
            }
            check.setStatus(ShoppingItemStatus.READY.name()); check.setUpdatedAt(LocalDateTime.now(clock));
            if (check.getId() == null) weeklyCheckMapper.insert(check); else weeklyCheckMapper.updateById(check);
        }
        if ("RECIPE".equalsIgnoreCase(sourceType)) {
            RecipeRecord recipe = recipeMapper.selectById(sourceId);
            if (recipe != null && recipe.getSearchLogId() != null) {
                ShoppingItemCheck check = shoppingCheckMapper.findByIdentity(userId, recipe.getSearchLogId(), name);
                if (check != null) {
                    check.setStatus(ShoppingItemStatus.READY.name()); check.setChecked(true); check.setUpdatedAt(LocalDateTime.now(clock)); shoppingCheckMapper.updateById(check);
                }
            }
        }
    }

    private List<UserPantryItem> availableItems(Long userId, String name) {
        LocalDate today = LocalDate.now(clock);
        return pantryMapper.findByUserId(userId).stream()
                .filter(item -> name.equals(canonicalName(item.getIngredientName())))
                .filter(item -> item.getQuantity() != null && item.getQuantity().signum() > 0)
                .filter(item -> item.getExpireDate() == null || !item.getExpireDate().isBefore(today))
                .sorted(Comparator.comparing(UserPantryItem::getExpireDate, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    private boolean hasExpiredOnly(Long userId, String name) {
        LocalDate today = LocalDate.now(clock);
        List<UserPantryItem> all = pantryMapper.findByUserId(userId).stream()
                .filter(item -> name.equals(canonicalName(item.getIngredientName())))
                .filter(item -> item.getQuantity() != null && item.getQuantity().signum() > 0)
                .toList();
        return !all.isEmpty() && all.stream().allMatch(item -> item.getExpireDate() != null && item.getExpireDate().isBefore(today));
    }

    private boolean hasAnyMatchingUnit(Long userId, String name) {
        return pantryMapper.findByUserId(userId).stream().anyMatch(item -> name.equals(canonicalName(item.getIngredientName()))
                && item.getQuantity() != null && item.getQuantity().signum() > 0);
    }

    private List<RecipeIngredient> recipeIngredients(Long recipeId) {
        return ingredientMapper.selectList(new QueryWrapper<RecipeIngredient>().eq("recipe_id", recipeId).orderByAsc("id"));
    }

    private RecipeRecord ownedRecipe(Long userId, Long recipeId) {
        RecipeRecord recipe = recipeMapper.selectById(recipeId);
        if (recipe == null) throw notFound("菜谱不存在");
        if (!userId.equals(recipe.getUserId())) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权使用该菜谱");
        return recipe;
    }

    private PantryOperation newOperation(Long userId, PantryOperationType type, String sourceType, Long sourceId, String key, Integer servings) {
        PantryOperation operation = new PantryOperation();
        operation.setUserId(userId); operation.setOperationType(type.name()); operation.setSourceType(sourceType == null ? null : sourceType.toUpperCase(Locale.ROOT));
        operation.setSourceId(sourceId); operation.setIdempotencyKey(key.trim()); operation.setStatus(PantryOperationStatus.COMPLETED.name()); operation.setActualServings(servings); operation.setCreatedAt(LocalDateTime.now(clock));
        return operation;
    }

    private void insertOperationItem(Long operationId, UserPantryItem pantry, BigDecimal quantity, String unit, BigDecimal before, BigDecimal after, String rawAmount) {
        PantryOperationItem item = new PantryOperationItem(); item.setOperationId(operationId); item.setPantryItemId(pantry == null ? null : pantry.getId());
        item.setIngredientName(pantry == null ? "" : pantry.getIngredientName()); item.setQuantity(quantity.setScale(2, RoundingMode.HALF_UP)); item.setUnit(unit); item.setBeforeQuantity(before); item.setAfterQuantity(after); item.setRawAmount(rawAmount); item.setCreatedAt(LocalDateTime.now(clock)); operationItemMapper.insert(item);
    }

    private PantryOperationResponse toResponse(Long userId, PantryOperation operation) {
        if (operation == null || !userId.equals(operation.getUserId())) throw notFound("库存变动记录不存在");
        List<PantryOperationItem> operationItems = operationItemMapper.findByOperationId(operation.getId());
        List<PantryOperationResponse.Item> items = operationItems.stream().map(item -> new PantryOperationResponse.Item(item.getPantryItemId(), item.getIngredientName(), item.getQuantity(), item.getUnit(), item.getBeforeQuantity(), item.getAfterQuantity(), item.getRawAmount())).toList();
        boolean noLaterOperation = operationItems.stream().allMatch(item -> item.getPantryItemId() != null
                && operationItemMapper.countLaterOperations(userId, item.getPantryItemId(), operation.getCreatedAt(), operation.getId()) == 0);
        boolean undoable = !PantryOperationType.REVERSAL.name().equals(operation.getOperationType())
                && PantryOperationStatus.COMPLETED.name().equals(operation.getStatus())
                && operation.getCreatedAt() != null
                && !operation.getCreatedAt().plusMinutes(UNDO_WINDOW_MINUTES).isBefore(LocalDateTime.now(clock))
                && noLaterOperation;
        return new PantryOperationResponse(operation.getId(), operation.getOperationType(), operation.getSourceType(), operation.getSourceId(), operation.getStatus(), operation.getActualServings(), operation.getCreatedAt(), operation.getReversedAt(), items, undoable);
    }

    private void recordPantryEpisode(
            Long userId,
            String episodeType,
            Long sourceId,
            String eventKey,
            String summary,
            Map<String, Object> payload,
            LocalDateTime occurredAt,
            BigDecimal importance
    ) {
        if (memoryEpisodeRecorder == null) {
            return;
        }
        memoryEpisodeRecorder.record(new MemoryBehaviorEpisodeEvent(
                userId,
                null,
                null,
                episodeType,
                "PANTRY_OPERATION",
                String.valueOf(sourceId),
                eventKey,
                eventKey,
                summary,
                payload,
                occurredAt,
                importance
        ));
    }

    private String canonicalRequired(String value) { String name = canonicalName(value); if (!StringUtils.hasText(name)) throw badRequest("食材名称无效"); return name; }
    private String canonicalName(String value) { return ingredientNormalizer.normalizeDistinct(value == null ? "" : value).stream().findFirst().map(IngredientNormalizer.NormalizedIngredient::canonicalName).orElse("").trim(); }
    private String requireUnit(String value) { String unit = amountParser.canonicalUnit(value); if (unit == null) throw badRequest("库存单位无法识别"); return unit; }
    private String trimToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private boolean validServings(Integer servings) { return servings != null && servings >= 1 && servings <= 20; }
    private void validateServings(int servings) { if (!validServings(servings)) throw badRequest("实际份数必须在 1 到 20 之间"); }
    private String requireIdempotencyKey(String key) {
        if (!StringUtils.hasText(key) || key.trim().length() > 96) throw badRequest("缺少有效幂等键");
        return key.trim();
    }
    private BigDecimal requireTwoDecimalQuantity(BigDecimal quantity, String message) {
        if (quantity == null || quantity.signum() <= 0 || quantity.stripTrailingZeros().scale() > 2) throw badRequest(message);
        return quantity.setScale(2, RoundingMode.HALF_UP);
    }
    private BigDecimal roundConsumptionQuantity(BigDecimal quantity) {
        BigDecimal rounded = quantity == null ? null : quantity.setScale(2, RoundingMode.HALF_UP);
        if (rounded == null || rounded.signum() <= 0) throw badRequest("菜谱用量低于库存精度，无法安全扣减");
        return rounded;
    }
    private void requirePurchasedOrReady(String status, boolean legacyReady) {
        ShoppingItemStatus parsed;
        try {
            parsed = ShoppingItemStatus.from(status, legacyReady);
        } catch (IllegalArgumentException exception) {
            throw badRequest("采购状态不合法，请重新刷新采购清单");
        }
        if (parsed != ShoppingItemStatus.PURCHASED && parsed != ShoppingItemStatus.READY) {
            throw badRequest("请先将采购状态更新为已购买，再确认入库");
        }
    }
    private ResponseStatusException badRequest(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private ResponseStatusException notFound(String message) { return new ResponseStatusException(HttpStatus.NOT_FOUND, message); }
}
