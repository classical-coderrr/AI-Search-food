package com.example.food.recipe.collection;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.food.memory.MemoryBehaviorEpisodeEvent;
import com.example.food.memory.MemoryBehaviorEpisodeRecorder;
import com.example.food.recipe.RecipeRecord;
import com.example.food.recipe.RecipeRecordMapper;
import com.example.food.recipe.collection.dto.BatchOperationFailure;
import com.example.food.recipe.collection.dto.BatchOperationResponse;
import com.example.food.recipe.collection.dto.RecipeCollectionRequest;
import com.example.food.recipe.collection.dto.RecipeCollectionResponse;
import com.example.food.recipe.collection.dto.RecipeTagResponse;
import com.example.food.recipe.collection.dto.SavedRecipeBatchDeleteRequest;
import com.example.food.recipe.collection.dto.SavedRecipeBatchMoveRequest;
import com.example.food.recipe.collection.dto.SavedRecipeBatchTagsRequest;
import com.example.food.recipe.collection.dto.SavedRecipeCollectionRequest;
import com.example.food.recipe.collection.dto.SavedRecipePageResponse;
import com.example.food.recipe.collection.dto.SavedRecipeResponse;
import com.example.food.recipe.collection.dto.SavedRecipeTagsRequest;
import com.example.food.security.UserSecurityLogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class SavedRecipeCollectionService {

    public static final String DEFAULT_COLLECTION_NAME = "默认收藏夹";
    public static final int MAX_CUSTOM_COLLECTIONS = 20;
    public static final int MAX_RECIPE_TAGS = 10;
    public static final int MAX_USER_TAGS = 100;
    private static final int MAX_PAGE_SIZE = 50;
    private static final String DEFAULT_SORT = "savedAtDesc";

    private final RecipeCollectionMapper collectionMapper;
    private final RecipeCollectionItemMapper collectionItemMapper;
    private final SavedRecipeCollectionMapper savedRecipeMapper;
    private final RecipeRecordMapper recipeRecordMapper;
    private final RecipeTagMapper tagMapper;
    private final RecipeRecordTagMapper recordTagMapper;
    private final UserSecurityLogService securityLogService;
    private final MemoryBehaviorEpisodeRecorder memoryEpisodeRecorder;
    private final Clock clock;

    @Autowired
    public SavedRecipeCollectionService(
            RecipeCollectionMapper collectionMapper,
            RecipeCollectionItemMapper collectionItemMapper,
            SavedRecipeCollectionMapper savedRecipeMapper,
            RecipeRecordMapper recipeRecordMapper,
            RecipeTagMapper tagMapper,
            RecipeRecordTagMapper recordTagMapper,
            UserSecurityLogService securityLogService,
            MemoryBehaviorEpisodeRecorder memoryEpisodeRecorder
    ) {
        this(
                collectionMapper,
                collectionItemMapper,
                savedRecipeMapper,
                recipeRecordMapper,
                tagMapper,
                recordTagMapper,
                securityLogService,
                memoryEpisodeRecorder,
                Clock.systemDefaultZone()
        );
    }

    SavedRecipeCollectionService(
            RecipeCollectionMapper collectionMapper,
            RecipeCollectionItemMapper collectionItemMapper,
            SavedRecipeCollectionMapper savedRecipeMapper,
            RecipeRecordMapper recipeRecordMapper,
            RecipeTagMapper tagMapper,
            RecipeRecordTagMapper recordTagMapper,
            UserSecurityLogService securityLogService,
            Clock clock
    ) {
        this(collectionMapper, collectionItemMapper, savedRecipeMapper, recipeRecordMapper, tagMapper,
                recordTagMapper, securityLogService, null, clock);
    }

    SavedRecipeCollectionService(
            RecipeCollectionMapper collectionMapper,
            RecipeCollectionItemMapper collectionItemMapper,
            SavedRecipeCollectionMapper savedRecipeMapper,
            RecipeRecordMapper recipeRecordMapper,
            RecipeTagMapper tagMapper,
            RecipeRecordTagMapper recordTagMapper,
            UserSecurityLogService securityLogService,
            MemoryBehaviorEpisodeRecorder memoryEpisodeRecorder,
            Clock clock
    ) {
        this.collectionMapper = collectionMapper;
        this.collectionItemMapper = collectionItemMapper;
        this.savedRecipeMapper = savedRecipeMapper;
        this.recipeRecordMapper = recipeRecordMapper;
        this.tagMapper = tagMapper;
        this.recordTagMapper = recordTagMapper;
        this.securityLogService = securityLogService;
        this.memoryEpisodeRecorder = memoryEpisodeRecorder;
        this.clock = clock;
    }

    @Transactional
    public List<RecipeCollectionResponse> listCollections(Long userId) {
        RecipeCollection defaultCollection = ensureDefaultCollection(userId);
        return collectionMapper.findByUserId(userId).stream()
                .map(collection -> responseOf(collection, userId, defaultCollection.getId()))
                .toList();
    }

    @Transactional
    public RecipeCollectionResponse createCollection(Long userId, RecipeCollectionRequest request) {
        String name = normalizeCollectionName(request == null ? null : request.name());
        ensureDefaultCollection(userId);
        if (DEFAULT_COLLECTION_NAME.equals(name)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "默认收藏夹名称不能重复创建");
        }
        if (collectionMapper.countCustomByUserId(userId) >= MAX_CUSTOM_COLLECTIONS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "每位用户最多创建20个自定义收藏夹");
        }
        if (collectionMapper.findByUserIdAndName(userId, name) != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "收藏夹名称已存在");
        }
        RecipeCollection collection = new RecipeCollection();
        collection.setUserId(userId);
        collection.setName(name);
        collection.setDefaultCollection(false);
        LocalDateTime now = now();
        collection.setCreatedAt(now);
        collection.setUpdatedAt(now);
        try {
            collectionMapper.insert(collection);
        } catch (DuplicateKeyException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "收藏夹名称已存在");
        }
        securityLogService.record(userId, UserSecurityLogService.COLLECTION_CHANGE,
                "/api/users/me/recipe-collections", "create collection");
        return responseOf(collection, userId, null);
    }

    @Transactional
    public RecipeCollectionResponse renameCollection(
            Long userId,
            Long collectionId,
            RecipeCollectionRequest request
    ) {
        RecipeCollection collection = ownedCollection(userId, collectionId);
        if (Boolean.TRUE.equals(collection.getDefaultCollection())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "默认收藏夹不可重命名");
        }
        String name = normalizeCollectionName(request == null ? null : request.name());
        RecipeCollection duplicate = collectionMapper.findByUserIdAndName(userId, name);
        if (duplicate != null && !collectionId.equals(duplicate.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "收藏夹名称已存在");
        }
        try {
            collectionMapper.renameOwnedCustom(userId, collectionId, name, now());
        } catch (DuplicateKeyException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "收藏夹名称已存在");
        }
        collection.setName(name);
        collection.setUpdatedAt(now());
        securityLogService.record(userId, UserSecurityLogService.COLLECTION_CHANGE,
                "/api/users/me/recipe-collections/" + collectionId, "rename collection");
        return responseOf(collection, userId, null);
    }

    @Transactional
    public void deleteCollection(Long userId, Long collectionId, boolean confirmed) {
        RecipeCollection collection = ownedCollection(userId, collectionId);
        if (Boolean.TRUE.equals(collection.getDefaultCollection())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "默认收藏夹不可删除");
        }
        RecipeCollection defaultCollection = ensureDefaultCollection(userId);
        int recipeCount = collectionMapper.countCustomRecipes(userId, collectionId);
        if (recipeCount > 0 && !confirmed) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "收藏夹不为空，请确认后再删除");
        }
        collectionItemMapper.moveAllToDefault(userId, collectionId, defaultCollection.getId());
        collectionMapper.deleteById(collectionId);
        securityLogService.record(userId, UserSecurityLogService.COLLECTION_CHANGE,
                "/api/users/me/recipe-collections/" + collectionId, "delete collection and move recipes");
    }

    @Transactional
    public SavedRecipePageResponse listSavedRecipes(
            Long userId,
            Long collectionId,
            String keyword,
            String mealType,
            String goal,
            String tagName,
            String sort,
            int page,
            int size
    ) {
        validatePage(page, size);
        RecipeCollection defaultCollection = ensureDefaultCollection(userId);
        if (collectionId != null) {
            ownedCollection(userId, collectionId);
        }
        String normalizedKeyword = normalizeFilter(keyword, 128);
        String normalizedMealType = normalizeFilter(mealType, 32);
        String normalizedGoal = normalizeFilter(goal, 32);
        String normalizedTag = normalizeTag(tagName, false);
        boolean ascending = isAscending(sort);
        int offset = Math.toIntExact((long) (page - 1) * size);
        List<SavedRecipeListRow> rows = savedRecipeMapper.findPage(
                userId,
                defaultCollection.getId(),
                defaultCollection.getName(),
                        collectionId,
                        normalizedKeyword,
                        normalizedMealType,
                        normalizedGoal,
                        normalizedTag,
                ascending,
                size,
                offset
        );
        List<Long> recipeIds = rows.stream().map(SavedRecipeListRow::getId).filter(Objects::nonNull).toList();
        Map<Long, List<String>> tagsByRecipe = tagsByRecipe(userId, recipeIds);
        List<SavedRecipeResponse> items = rows.stream()
                .map(row -> new SavedRecipeResponse(
                        row.getId(),
                        row.getTitle(),
                        row.getSummary(),
                        row.getSearchIngredients(),
                        row.getMealType(),
                        row.getGoal(),
                        row.getCoverIngredient(),
                        row.getSavedAt(),
                        row.getCollectionId(),
                        row.getCollectionName(),
                        tagsByRecipe.getOrDefault(row.getId(), List.of())
                ))
                .toList();
        long total = savedRecipeMapper.count(
                userId,
                defaultCollection.getId(),
                collectionId,
                normalizedKeyword,
                normalizedMealType,
                normalizedGoal,
                normalizedTag
        );
        int totalPages = total == 0 ? 0 : (int) Math.ceil((double) total / size);
        return new SavedRecipePageResponse(items, total, page, size, totalPages);
    }

    @Transactional(readOnly = true)
    public List<RecipeTagResponse> listTags(Long userId) {
        return tagMapper.findSummariesByUserId(userId).stream()
                .map(row -> new RecipeTagResponse(row.getId(), row.getName(), row.getRecipeCount()))
                .toList();
    }

    @Transactional
    public void moveRecipe(Long userId, Long recipeId, SavedRecipeCollectionRequest request) {
        RecipeCollection target = ownedCollection(userId, request == null ? null : request.collectionId());
        moveOwnedRecipe(userId, recipeId, target.getId());
        securityLogService.record(userId, UserSecurityLogService.COLLECTION_CHANGE,
                "/api/users/me/saved-recipes/" + recipeId + "/collection", "move recipe");
    }

    @Transactional
    public void replaceTags(Long userId, Long recipeId, SavedRecipeTagsRequest request) {
        ownedRecipe(userId, recipeId);
        List<String> names = normalizeTagNames(request == null ? null : request.tags());
        Map<String, RecipeTag> tags = resolveTags(userId, names);
        replaceTagsOwned(userId, recipeId, tags.values());
        securityLogService.record(userId, UserSecurityLogService.COLLECTION_CHANGE,
                "/api/users/me/saved-recipes/" + recipeId + "/tags", "replace recipe tags");
    }

    public BatchOperationResponse batchMove(Long userId, SavedRecipeBatchMoveRequest request) {
        List<Long> recipeIds = normalizeRecipeIds(request == null ? null : request.recipeIds());
        RecipeCollection target = ownedCollection(userId, request == null ? null : request.collectionId());
        List<BatchOperationFailure> failures = new ArrayList<>();
        int success = 0;
        for (Long recipeId : recipeIds) {
            try {
                moveOwnedRecipe(userId, recipeId, target.getId());
                success++;
            } catch (RuntimeException exception) {
                failures.add(new BatchOperationFailure(recipeId, reasonOf(exception)));
            }
        }
        securityLogService.record(userId, UserSecurityLogService.BATCH_SAVED_RECIPE_CHANGE,
                "/api/users/me/saved-recipes/batch-move", "success=" + success + "; failure=" + failures.size());
        return new BatchOperationResponse(success, failures.size(), failures);
    }

    public BatchOperationResponse batchTags(Long userId, SavedRecipeBatchTagsRequest request) {
        List<Long> recipeIds = normalizeRecipeIds(request == null ? null : request.recipeIds());
        List<String> addNames = normalizeTagNames(request == null ? null : request.addTags());
        List<String> removeNames = normalizeTagNames(request == null ? null : request.removeTags());
        Map<String, RecipeTag> addTags = resolveTags(userId, addNames);
        Set<String> removeKeys = removeNames.stream().map(this::tagKey).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<BatchOperationFailure> failures = new ArrayList<>();
        int success = 0;
        for (Long recipeId : recipeIds) {
            try {
                RecipeRecord recipe = ownedRecipe(userId, recipeId);
                Map<String, RecipeTag> nextTags = currentTags(userId, recipe.getId());
                removeKeys.forEach(nextTags::remove);
                addTags.forEach(nextTags::put);
                if (nextTags.size() > MAX_RECIPE_TAGS) {
                    throw new IllegalArgumentException("每道菜谱最多添加10个标签");
                }
                replaceTagsOwned(userId, recipeId, nextTags.values());
                success++;
            } catch (RuntimeException exception) {
                failures.add(new BatchOperationFailure(recipeId, reasonOf(exception)));
            }
        }
        securityLogService.record(userId, UserSecurityLogService.BATCH_SAVED_RECIPE_CHANGE,
                "/api/users/me/saved-recipes/batch-tags", "success=" + success + "; failure=" + failures.size());
        return new BatchOperationResponse(success, failures.size(), failures);
    }

    public BatchOperationResponse batchDelete(Long userId, SavedRecipeBatchDeleteRequest request) {
        List<Long> recipeIds = normalizeRecipeIds(request == null ? null : request.recipeIds());
        List<BatchOperationFailure> failures = new ArrayList<>();
        int success = 0;
        for (Long recipeId : recipeIds) {
            try {
                RecipeRecord recipe = recipeRecordMapper.selectById(recipeId);
                if (recipeRecordMapper.deleteOwnedRecipe(recipeId, userId) == 0) {
                    throw new ResponseStatusException(HttpStatus.NOT_FOUND, "菜谱不存在");
                }
                recordRecipeUnsaved(userId, recipeId, recipe);
                success++;
            } catch (RuntimeException exception) {
                failures.add(new BatchOperationFailure(recipeId, reasonOf(exception)));
            }
        }
        securityLogService.record(userId, UserSecurityLogService.BATCH_SAVED_RECIPE_CHANGE,
                "/api/users/me/saved-recipes/batch", "success=" + success + "; failure=" + failures.size());
        return new BatchOperationResponse(success, failures.size(), failures);
    }

    private void recordRecipeUnsaved(Long userId, Long recipeId, RecipeRecord recipe) {
        if (memoryEpisodeRecorder == null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("recipeId", recipeId);
        payload.put("title", recipe == null ? null : recipe.getTitle());
        payload.put("searchLogId", recipe == null ? null : recipe.getSearchLogId());
        memoryEpisodeRecorder.record(new MemoryBehaviorEpisodeEvent(
                userId,
                null,
                null,
                "RECIPE_UNSAVED",
                "RECIPE_RECORD",
                String.valueOf(recipeId),
                "recipe-unsaved:" + recipeId,
                "recipe-unsaved:" + recipeId,
                "取消收藏菜谱：" + (recipe == null ? recipeId : recipe.getTitle()),
                payload,
                now(),
                new BigDecimal("0.6500")
        ));
    }

    RecipeCollection ensureDefaultCollection(Long userId) {
        RecipeCollection existing = collectionMapper.findDefaultByUserId(userId);
        if (existing != null) {
            return existing;
        }
        RecipeCollection created = new RecipeCollection();
        created.setUserId(userId);
        created.setName(DEFAULT_COLLECTION_NAME);
        created.setDefaultCollection(true);
        LocalDateTime now = now();
        created.setCreatedAt(now);
        created.setUpdatedAt(now);
        try {
            collectionMapper.insert(created);
            return created;
        } catch (DuplicateKeyException exception) {
            RecipeCollection concurrent = collectionMapper.findDefaultByUserId(userId);
            if (concurrent != null) {
                return concurrent;
            }
            throw exception;
        }
    }

    private RecipeCollectionResponse responseOf(
            RecipeCollection collection,
            Long userId,
            Long defaultCollectionId
    ) {
        boolean isDefault = Boolean.TRUE.equals(collection.getDefaultCollection());
        long count = isDefault
                ? collectionMapper.countDefaultRecipes(userId, collection.getId())
                : collectionMapper.countCustomRecipes(userId, collection.getId());
        return new RecipeCollectionResponse(
                collection.getId(),
                collection.getName(),
                isDefault,
                count,
                collection.getCreatedAt(),
                collection.getUpdatedAt()
        );
    }

    private RecipeCollection ownedCollection(Long userId, Long collectionId) {
        if (collectionId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "收藏夹不能为空");
        }
        RecipeCollection collection = collectionMapper.findOwned(userId, collectionId);
        if (collection == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "收藏夹不存在");
        }
        return collection;
    }

    private RecipeRecord ownedRecipe(Long userId, Long recipeId) {
        if (recipeId == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "菜谱不存在");
        }
        RecipeRecord recipe = recipeRecordMapper.selectById(recipeId);
        if (recipe == null || !userId.equals(recipe.getUserId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "菜谱不存在");
        }
        return recipe;
    }

    private void moveOwnedRecipe(Long userId, Long recipeId, Long collectionId) {
        ownedRecipe(userId, recipeId);
        RecipeCollection defaultCollection = ensureDefaultCollection(userId);
        RecipeCollectionItem item = collectionItemMapper.findByUserIdAndRecipeId(userId, recipeId);
        if (item == null) {
            item = new RecipeCollectionItem();
            item.setUserId(userId);
            item.setRecipeId(recipeId);
            item.setCollectionId(defaultCollection.getId());
            item.setCreatedAt(now());
            try {
                collectionItemMapper.insert(item);
            } catch (DuplicateKeyException exception) {
                item = collectionItemMapper.findByUserIdAndRecipeId(userId, recipeId);
            }
        }
        if (item == null || collectionItemMapper.moveOwnedRecipe(userId, recipeId, collectionId) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "菜谱不存在");
        }
    }

    private Map<Long, List<String>> tagsByRecipe(Long userId, List<Long> recipeIds) {
        if (recipeIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<String>> result = new LinkedHashMap<>();
        for (SavedRecipeTagRow row : recordTagMapper.findRowsByUserIdAndRecipeIds(userId, recipeIds)) {
            result.computeIfAbsent(row.getRecipeId(), ignored -> new ArrayList<>()).add(row.getTagName());
        }
        return result;
    }

    private Map<String, RecipeTag> currentTags(Long userId, Long recipeId) {
        Map<String, RecipeTag> tags = new LinkedHashMap<>();
        for (RecipeRecordTag relation : recordTagMapper.findByUserIdAndRecipeId(userId, recipeId)) {
            RecipeTag tag = tagMapper.selectById(relation.getTagId());
            if (tag != null && userId.equals(tag.getUserId())) {
                tags.put(tagKey(tag.getName()), tag);
            }
        }
        return tags;
    }

    private Map<String, RecipeTag> resolveTags(Long userId, List<String> names) {
        if (names.isEmpty()) {
            return new LinkedHashMap<>();
        }
        Map<String, RecipeTag> allTags = tagMapper.selectList(new QueryWrapper<RecipeTag>()
                        .eq("user_id", userId)
                        .orderByAsc("id"))
                .stream()
                .filter(tag -> tag.getName() != null)
                .collect(java.util.stream.Collectors.toMap(
                        tag -> tagKey(tag.getName()),
                        tag -> tag,
                        (first, ignored) -> first,
                        LinkedHashMap::new
                ));
        Map<String, RecipeTag> resolved = new LinkedHashMap<>();
        for (String name : names) {
            String key = tagKey(name);
            RecipeTag tag = allTags.get(key);
            if (tag == null) {
                if (allTags.size() >= MAX_USER_TAGS) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "每位用户最多创建100个标签");
                }
                tag = new RecipeTag();
                tag.setUserId(userId);
                tag.setName(name);
                tag.setCreatedAt(now());
                try {
                    tagMapper.insert(tag);
                } catch (DuplicateKeyException exception) {
                    tag = tagMapper.findByUserIdAndName(userId, name);
                }
                if (tag == null) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "标签创建失败，请重试");
                }
                allTags.put(key, tag);
            }
            resolved.put(key, tag);
        }
        return resolved;
    }

    private void replaceTagsOwned(Long userId, Long recipeId, Iterable<RecipeTag> tags) {
        recordTagMapper.deleteByUserIdAndRecipeId(userId, recipeId);
        for (RecipeTag tag : tags) {
            RecipeRecordTag relation = new RecipeRecordTag();
            relation.setUserId(userId);
            relation.setRecipeId(recipeId);
            relation.setTagId(tag.getId());
            relation.setCreatedAt(now());
            recordTagMapper.insert(relation);
        }
    }

    private List<Long> normalizeRecipeIds(List<Long> recipeIds) {
        if (recipeIds == null || recipeIds.isEmpty()) {
            throw new IllegalArgumentException("请选择至少一道菜谱");
        }
        if (recipeIds.size() > 100) {
            throw new IllegalArgumentException("单次最多操作100道菜谱");
        }
        LinkedHashSet<Long> distinct = new LinkedHashSet<>();
        for (Long recipeId : recipeIds) {
            if (recipeId == null || recipeId <= 0) {
                throw new IllegalArgumentException("菜谱编号不合法");
            }
            distinct.add(recipeId);
        }
        return List.copyOf(distinct);
    }

    private List<String> normalizeTagNames(List<String> names) {
        if (names == null) {
            throw new IllegalArgumentException("标签列表不能为空");
        }
        LinkedHashMap<String, String> distinct = new LinkedHashMap<>();
        for (String value : names) {
            String normalized = normalizeTag(value, true);
            distinct.putIfAbsent(tagKey(normalized), normalized);
        }
        return List.copyOf(distinct.values());
    }

    private String normalizeCollectionName(String value) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("收藏夹名称不能为空");
        }
        String normalized = value.trim();
        if (normalized.length() > 64) {
            throw new IllegalArgumentException("收藏夹名称不能超过64个字符");
        }
        return normalized;
    }

    private String normalizeTag(String value, boolean required) {
        if (!StringUtils.hasText(value)) {
            if (required) {
                throw new IllegalArgumentException("标签名称不能为空");
            }
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() < 1 || normalized.length() > 20) {
            throw new IllegalArgumentException("标签名称长度必须为1到20个字符");
        }
        return normalized;
    }

    private String tagKey(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeFilter(String value, int maxLength) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String normalized = value.trim();
        return normalized.length() > maxLength ? normalized.substring(0, maxLength) : normalized;
    }

    private boolean isAscending(String sort) {
        if (!StringUtils.hasText(sort)) {
            return false;
        }
        return switch (sort.trim()) {
            case "savedAtAsc", "asc", "ASC" -> true;
            case DEFAULT_SORT, "desc", "DESC" -> false;
            default -> throw new IllegalArgumentException("排序方式不合法");
        };
    }

    private void validatePage(int page, int size) {
        if (page < 1 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("分页参数不合法");
        }
        Math.toIntExact((long) (page - 1) * size);
    }

    private String reasonOf(RuntimeException exception) {
        if (exception instanceof ResponseStatusException statusException
                && StringUtils.hasText(statusException.getReason())) {
            return statusException.getReason();
        }
        return StringUtils.hasText(exception.getMessage()) ? exception.getMessage() : "操作失败";
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }
}
