package com.example.food.pantry;

import com.example.food.memory.MemoryBehaviorEpisodeEvent;
import com.example.food.memory.MemoryBehaviorEpisodeRecorder;
import com.example.food.pantry.dto.PantryItemRequest;
import com.example.food.pantry.dto.PantryItemResponse;
import com.example.food.pantry.dto.PantryExpirySummaryResponse;
import com.example.food.pantry.dto.PantryReadinessRequest;
import com.example.food.pantry.dto.PantryReadinessResponse;
import com.example.food.stats.IngredientNormalizer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class UserPantryService {

    public static final int EXPIRY_WARNING_DAYS = 7;

    private final UserPantryItemMapper mapper;
    private final IngredientNormalizer ingredientNormalizer;
    private final IngredientAmountParser amountParser;
    private final Clock clock;
    private final MemoryBehaviorEpisodeRecorder memoryEpisodeRecorder;

    @Autowired
    public UserPantryService(
            UserPantryItemMapper mapper,
            IngredientNormalizer ingredientNormalizer,
            MemoryBehaviorEpisodeRecorder memoryEpisodeRecorder
    ) {
        this(mapper, ingredientNormalizer, memoryEpisodeRecorder, new IngredientAmountParser(), Clock.systemDefaultZone());
    }

    public UserPantryService(UserPantryItemMapper mapper, IngredientNormalizer ingredientNormalizer) {
        this(mapper, ingredientNormalizer, null, new IngredientAmountParser(), Clock.systemDefaultZone());
    }

    UserPantryService(UserPantryItemMapper mapper, IngredientNormalizer ingredientNormalizer, Clock clock) {
        this(mapper, ingredientNormalizer, null, new IngredientAmountParser(), clock);
    }

    UserPantryService(
            UserPantryItemMapper mapper,
            IngredientNormalizer ingredientNormalizer,
            IngredientAmountParser amountParser,
            Clock clock
    ) {
        this(mapper, ingredientNormalizer, null, amountParser, clock);
    }

    UserPantryService(
            UserPantryItemMapper mapper,
            IngredientNormalizer ingredientNormalizer,
            MemoryBehaviorEpisodeRecorder memoryEpisodeRecorder,
            IngredientAmountParser amountParser,
            Clock clock
    ) {
        this.mapper = mapper;
        this.ingredientNormalizer = ingredientNormalizer;
        this.amountParser = amountParser;
        this.clock = clock;
        this.memoryEpisodeRecorder = memoryEpisodeRecorder;
    }

    public List<PantryItemResponse> list(Long userId) {
        return mapper.findByUserId(userId).stream().map(this::toResponse).toList();
    }

    public List<String> listIngredientNames(Long userId) {
        return mapper.findByUserId(userId).stream()
                .map(UserPantryItem::getIngredientName)
                .distinct()
                .toList();
    }

    public PantryReadinessResponse readiness(Long userId, PantryReadinessRequest request) {
        List<UserPantryItem> pantryItems = mapper.findByUserId(userId);
        LocalDate today = LocalDate.now(clock);
        LocalDate warningUntil = today.plusDays(EXPIRY_WARNING_DAYS);
        List<PantryReadinessResponse.Item> items = request.ingredients().stream()
                .map(ingredient -> readinessItem(ingredient, pantryItems, today, warningUntil))
                .toList();
        int readyCount = (int) items.stream().filter(item -> "ENOUGH".equals(item.status())).count();
        int shortageCount = (int) items.stream().filter(PantryReadinessResponse.Item::shortage).count();
        int expiringSoonCount = (int) items.stream().filter(PantryReadinessResponse.Item::expiringSoon).count();
        int itemCount = items.size();
        int readinessPercent = itemCount == 0 ? 0 : readyCount * 100 / itemCount;
        return new PantryReadinessResponse(
                itemCount,
                readyCount,
                shortageCount,
                expiringSoonCount,
                readinessPercent,
                List.copyOf(items)
        );
    }

    private PantryReadinessResponse.Item readinessItem(
            PantryReadinessRequest.Ingredient ingredient,
            List<UserPantryItem> pantryItems,
            LocalDate today,
            LocalDate warningUntil
    ) {
        String rawName = ingredient.name() == null ? "" : ingredient.name().trim();
        List<IngredientNormalizer.NormalizedIngredient> normalized = ingredientNormalizer.normalizeDistinct(rawName);
        String name = normalized.size() == 1 ? normalized.get(0).canonicalName() : "";
        String rawAmount = ingredient.amount() == null ? "" : ingredient.amount().trim();
        IngredientAmountParser.ParsedAmount expected = amountParser.parse(rawAmount);
        List<UserPantryItem> sameName = name.isBlank()
                ? List.of()
                : pantryItems.stream()
                .filter(item -> name.equals(canonicalName(item.getIngredientName())))
                .filter(item -> item.getQuantity() != null && item.getQuantity().signum() > 0)
                .toList();
        List<UserPantryItem> active = sameName.stream()
                .filter(item -> item.getExpireDate() == null || !item.getExpireDate().isBefore(today))
                .sorted(Comparator.comparing(UserPantryItem::getExpireDate, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        boolean expiringSoon = active.stream().anyMatch(item -> item.getExpireDate() != null
                && !item.getExpireDate().isAfter(warningUntil));

        if (normalized.size() != 1) {
            return readinessItem(name, rawAmount, expected, null, null, "INVALID",
                    "食材名称无法比较，请检查名称", false, expiringSoon, List.of());
        }
        if (!expected.isParsed()) {
            String status = expected.status() == IngredientAmountParser.Status.UNQUANTIFIED
                    ? "UNQUANTIFIED" : "INVALID";
            return readinessItem(name, rawAmount, expected, null, null, status,
                    "用量无法安全比较，将在烹饪时再次确认", false, expiringSoon, List.of());
        }

        BigDecimal availableQuantity = BigDecimal.ZERO;
        List<PantryReadinessResponse.Batch> batches = new ArrayList<>();
        boolean hasCompatible = false;
        for (UserPantryItem item : active) {
            BigDecimal converted = amountParser.convert(item.getQuantity(), item.getUnit(), expected.unit());
            if (converted == null) {
                continue;
            }
            hasCompatible = true;
            availableQuantity = availableQuantity.add(converted);
            batches.add(new PantryReadinessResponse.Batch(item.getId(), item.getQuantity(), item.getUnit(), item.getExpireDate()));
        }

        if (hasCompatible && availableQuantity.compareTo(expected.quantity()) >= 0) {
            return readinessItem(name, rawAmount, expected, availableQuantity, expected.unit(), "ENOUGH",
                    "库存充足", false, expiringSoon, batches);
        }
        if (hasCompatible) {
            return readinessItem(name, rawAmount, expected, availableQuantity, expected.unit(), "PARTIAL",
                    "库存不足，还需要补充", true, expiringSoon, batches);
        }
        if (!active.isEmpty()) {
            return readinessItem(name, rawAmount, expected, null, expected.unit(), "UNIT_MISMATCH",
                    "库存单位无法比较", false, expiringSoon, List.of());
        }
        if (!sameName.isEmpty()) {
            return readinessItem(name, rawAmount, expected, null, expected.unit(), "EXPIRED_ONLY",
                    "仅有已过期库存，暂不计入准备度", true, false, List.of());
        }
        return readinessItem(name, rawAmount, expected, null, expected.unit(), "MISSING",
                "完全缺少该食材", true, false, List.of());
    }

    private PantryReadinessResponse.Item readinessItem(
            String name,
            String rawAmount,
            IngredientAmountParser.ParsedAmount expected,
            BigDecimal availableQuantity,
            String availableUnit,
            String status,
            String message,
            boolean shortage,
            boolean expiringSoon,
            List<PantryReadinessResponse.Batch> batches
    ) {
        return new PantryReadinessResponse.Item(
                name,
                rawAmount,
                expected.quantity(),
                expected.unit(),
                availableQuantity,
                availableUnit,
                status,
                message,
                shortage,
                expiringSoon,
                List.copyOf(batches)
        );
    }

    private String canonicalName(String value) {
        return ingredientNormalizer.normalizeDistinct(value == null ? "" : value).stream()
                .findFirst()
                .map(IngredientNormalizer.NormalizedIngredient::canonicalName)
                .orElse("")
                .trim();
    }

    public PantryExpirySummaryResponse expirySummary(Long userId) {
        LocalDate today = LocalDate.now(clock);
        LocalDate warningUntil = today.plusDays(EXPIRY_WARNING_DAYS);
        List<UserPantryItem> alerts = mapper.findExpiryAlertsByUserId(userId, warningUntil);
        List<PantryItemResponse> expiredItems = new ArrayList<>();
        List<PantryItemResponse> expiringSoonItems = new ArrayList<>();

        for (UserPantryItem item : alerts) {
            PantryItemResponse response = toResponse(item);
            if (item.getExpireDate().isBefore(today)) {
                expiredItems.add(response);
            } else {
                expiringSoonItems.add(response);
            }
        }

        return new PantryExpirySummaryResponse(
                today,
                EXPIRY_WARNING_DAYS,
                List.copyOf(expiredItems),
                List.copyOf(expiringSoonItems)
        );
    }

    @Transactional
    public PantryItemResponse create(Long userId, PantryItemRequest request) {
        LocalDateTime now = LocalDateTime.now(clock);
        UserPantryItem item = new UserPantryItem();
        item.setUserId(userId);
        applyRequest(item, request);
        UserPantryItem existing = mapper.findByIdentityForUpdate(
                userId,
                item.getIngredientName(),
                item.getCategory(),
                item.getExpireDate()
        );
        if (existing != null) {
            BigDecimal beforeQuantity = existing.getQuantity();
            String incomingUnit = item.getUnit();
            mergeQuantity(existing, item.getQuantity(), item.getUnit());
            existing.setUpdatedAt(now);
            mapper.updateById(existing);
            recordDirectPantryEpisode(
                    userId,
                    "PANTRY_STOCK_IN",
                    existing,
                    item.getQuantity(),
                    incomingUnit,
                    beforeQuantity,
                    existing.getQuantity(),
                    now,
                    new BigDecimal("0.3500")
            );
            return toResponse(existing);
        }
        item.setCreatedAt(now);
        item.setUpdatedAt(now);
        mapper.insert(item);
        recordDirectPantryEpisode(
                userId,
                "PANTRY_STOCK_IN",
                item,
                item.getQuantity(),
                item.getUnit(),
                null,
                item.getQuantity(),
                now,
                new BigDecimal("0.3500")
        );
        return toResponse(item);
    }

    @Transactional
    public PantryItemResponse update(Long userId, Long itemId, PantryItemRequest request) {
        UserPantryItem item = findOwned(userId, itemId);
        applyRequest(item, request);
        item.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(item);
        return toResponse(item);
    }

    @Transactional
    public PantryItemResponse consume(Long userId, Long itemId, BigDecimal quantity) {
        validateConsumeQuantity(quantity);
        UserPantryItem item = findOwned(userId, itemId);
        if (item.getQuantity() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "当前库存数量为空，无法消耗");
        }

        BigDecimal beforeQuantity = item.getQuantity();
        String unit = item.getUnit();
        LocalDateTime now = LocalDateTime.now(clock);
        int updatedRows = mapper.consumeByUserIdAndId(userId, itemId, quantity);
        if (updatedRows == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "库存数量不足");
        }
        UserPantryItem updated = mapper.selectById(itemId);
        PantryItemResponse response = toResponse(updated);
        recordDirectPantryEpisode(
                userId,
                "PANTRY_CONSUMED",
                updated,
                quantity,
                unit,
                beforeQuantity,
                updated.getQuantity(),
                now,
                new BigDecimal("0.7000")
        );
        return response;
    }

    private void recordDirectPantryEpisode(
            Long userId,
            String episodeType,
            UserPantryItem item,
            BigDecimal quantity,
            String unit,
            BigDecimal beforeQuantity,
            BigDecimal afterQuantity,
            LocalDateTime occurredAt,
            BigDecimal importance
    ) {
        if (memoryEpisodeRecorder == null || item == null) {
            return;
        }
        String eventKey = "pantry-direct:" + episodeType + ":" + UUID.randomUUID();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("pantryItemId", item.getId());
        payload.put("ingredientName", item.getIngredientName());
        payload.put("category", item.getCategory());
        payload.put("quantity", quantity);
        payload.put("unit", unit);
        payload.put("beforeQuantity", beforeQuantity);
        payload.put("afterQuantity", afterQuantity);
        payload.put("expireDate", item.getExpireDate());
        memoryEpisodeRecorder.record(new MemoryBehaviorEpisodeEvent(
                userId,
                null,
                null,
                episodeType,
                "PANTRY_DIRECT",
                item.getId() == null ? null : String.valueOf(item.getId()),
                eventKey,
                eventKey,
                episodeType + ": " + item.getIngredientName(),
                payload,
                occurredAt,
                importance
        ));
    }

    @Transactional
    public void delete(Long userId, Long itemId) {
        UserPantryItem item = findOwned(userId, itemId);
        mapper.deleteById(item.getId());
    }

    private UserPantryItem findOwned(Long userId, Long itemId) {
        UserPantryItem item = mapper.selectById(itemId);
        if (item == null || !userId.equals(item.getUserId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "食材不存在");
        }
        return item;
    }

    private void validateConsumeQuantity(BigDecimal quantity) {
        if (quantity == null || quantity.signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "消耗数量必须大于 0");
        }
        if (quantity.scale() > 2 || quantity.precision() - quantity.scale() > 8) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "消耗数量最多支持 8 位整数和 2 位小数");
        }
    }

    private void applyRequest(UserPantryItem item, PantryItemRequest request) {
        item.setIngredientName(normalizeIngredientName(request.ingredientName()));
        item.setCategory(trimToNull(request.category()));
        item.setQuantity(request.quantity());
        item.setUnit(trimToNull(request.unit()));
        item.setExpireDate(request.expireDate());
    }

    private void mergeQuantity(UserPantryItem existing, BigDecimal incomingQuantity, String incomingUnit) {
        if (incomingQuantity == null) {
            return;
        }
        if (existing.getQuantity() == null) {
            existing.setQuantity(incomingQuantity);
            if (!hasText(existing.getUnit()) && hasText(incomingUnit)) {
                existing.setUnit(incomingUnit);
            }
            return;
        }

        BigDecimal addition = incomingQuantity;
        if (hasText(existing.getUnit()) && hasText(incomingUnit)) {
            addition = amountParser.convert(incomingQuantity, incomingUnit, existing.getUnit());
            if (addition == null) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "相同食材的库存单位不兼容，无法合并"
                );
            }
        } else if (!hasText(existing.getUnit()) && hasText(incomingUnit)) {
            existing.setUnit(incomingUnit);
        }
        existing.setQuantity(existing.getQuantity().add(addition));
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String normalizeIngredientName(String value) {
        List<IngredientNormalizer.NormalizedIngredient> ingredients =
                ingredientNormalizer.normalizeDistinct(value);
        if (ingredients.size() != 1) {
            throw new IllegalArgumentException("食材名称只能填写一种食材");
        }
        return ingredients.get(0).canonicalName();
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private PantryItemResponse toResponse(UserPantryItem item) {
        return new PantryItemResponse(
                item.getId(),
                item.getIngredientName(),
                item.getCategory(),
                item.getQuantity(),
                item.getUnit(),
                item.getExpireDate(),
                item.getUpdatedAt()
        );
    }
}
