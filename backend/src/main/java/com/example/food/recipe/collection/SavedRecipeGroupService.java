package com.example.food.recipe.collection;

import com.example.food.recipe.SavedRecipeIdentityService;
import com.example.food.recipe.collection.dto.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

@Service
public class SavedRecipeGroupService {
    private static final String JOINS = " FROM recipe_records rr LEFT JOIN search_logs sl ON sl.id = rr.search_log_id"
            + " LEFT JOIN recipe_collection_items rci ON rci.recipe_id = rr.id AND rci.user_id = rr.user_id"
            + " LEFT JOIN recipe_collections rc ON rc.id = rci.collection_id AND rc.user_id = rr.user_id ";
    private final JdbcTemplate jdbc;
    private final SavedRecipeIdentityService identities;
    private final SavedRecipeCollectionService collections;
    private final TransactionTemplate transaction;

    public SavedRecipeGroupService(JdbcTemplate jdbc, SavedRecipeIdentityService identities,
                                   SavedRecipeCollectionService collections, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.identities = identities;
        this.collections = collections;
        this.transaction = new TransactionTemplate(manager);
    }

    @Transactional
    public GroupPage list(Long userId, Long collectionId, String keyword, String mealType, String goal,
                          String tag, String sort, int page, int size) {
        if (page < 1 || size < 1 || size > 50 || (long) (page - 1) * size > Integer.MAX_VALUE) badRequest("分页参数无效");
        if (!List.of("savedAtAsc", "savedAtDesc").contains(sort)) badRequest("排序参数无效");
        Filter filter = filter(userId, collectionId, keyword, mealType, goal, tag);
        boolean ascending = "savedAtAsc".equals(sort);
        Long total = jdbc.queryForObject("SELECT COUNT(DISTINCT rr.dish_group_key)" + JOINS + filter.where(), Long.class, filter.args().toArray());
        List<Object> args = new ArrayList<>(filter.args());
        args.add(size);
        args.add((page - 1) * size);
        String aggregate = ascending ? "MIN" : "MAX";
        List<String> keys = jdbc.queryForList("SELECT rr.dish_group_key" + JOINS + filter.where()
                + " GROUP BY rr.dish_group_key ORDER BY " + aggregate + "(rr.created_at) " + (ascending ? "ASC" : "DESC")
                + ", rr.dish_group_key LIMIT ? OFFSET ?", String.class, args.toArray());
        List<Group> items = new ArrayList<>();
        for (String key : keys) {
            List<Object> matching = new ArrayList<>(filter.args());
            matching.add(key);
            SavedRecipeResponse recipe = jdbc.query(summarySql(filter.defaults()) + JOINS + filter.where()
                    + " AND rr.dish_group_key = ? ORDER BY rr.created_at DESC, rr.id DESC LIMIT 1",
                    rowMapper(filter.defaults(), userId), matching.toArray()).get(0);
            items.add(Group.of(key, 1, recipe));
        }
        return new GroupPage(items, total == null ? 0 : total, page, size,
                total == null || total == 0 ? 0 : (int) Math.ceil((double) total / size));
    }

    @Transactional
    public List<SavedRecipeResponse> versions(Long userId, String groupKey, Long preferredId) {
        validateKey(groupKey);
        identities.indexLegacyUser(userId);
        RecipeCollectionResponse defaults = defaults(userId);
        List<SavedRecipeResponse> result = jdbc.query(summarySql(defaults) + JOINS
                + " WHERE rr.user_id = ? AND rr.weekly_menu_plan_id IS NULL AND rr.dish_group_key = ?"
                + " ORDER BY rr.id LIMIT 1", rowMapper(defaults, userId), userId, groupKey);
        if (result.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "菜谱组不存在");
        return result;
    }

    @Transactional
    public List<RecipeCollectionResponse> listCollections(Long userId) {
        identities.indexLegacyUser(userId);
        List<RecipeCollectionResponse> existing = collections.listCollections(userId);
        return existing.stream().map(collection -> {
            long count = jdbc.queryForObject("SELECT COUNT(DISTINCT rr.dish_group_key)" + JOINS
                            + " WHERE rr.user_id = ? AND rr.weekly_menu_plan_id IS NULL AND " + canonicalRecipe() + " AND "
                            + (collection.defaultCollection() ? "(rci.collection_id IS NULL OR rci.collection_id = ?)" : "rci.collection_id = ?"),
                    Long.class, userId, collection.id());
            return new RecipeCollectionResponse(collection.id(), collection.name(), collection.defaultCollection(), count,
                    collection.createdAt(), collection.updatedAt());
        }).toList();
    }

    public BatchOperationResponse batchMove(Long userId, List<String> keys, Long collectionId) {
        return operate(userId, keys, ids -> collections.batchMove(userId, new SavedRecipeBatchMoveRequest(ids, collectionId)));
    }

    public BatchOperationResponse batchTags(Long userId, List<String> keys, List<String> add, List<String> remove) {
        return operate(userId, keys, ids -> collections.batchTags(userId,
                new SavedRecipeBatchTagsRequest(ids, add == null ? List.of() : add, remove == null ? List.of() : remove)));
    }

    public BatchOperationResponse batchDelete(Long userId, List<String> keys) {
        return operate(userId, keys, ids -> collections.batchDelete(userId, new SavedRecipeBatchDeleteRequest(ids)));
    }

    private BatchOperationResponse operate(Long userId, List<String> requestedKeys,
                                            Function<List<Long>, BatchOperationResponse> operation) {
        if (requestedKeys == null || requestedKeys.isEmpty() || requestedKeys.size() > 100) badRequest("单次请选择1至100道菜谱");
        requestedKeys.forEach(this::validateKey);
        int success = 0;
        List<BatchOperationFailure> failures = new ArrayList<>();
        for (String key : requestedKeys.stream().distinct().toList()) {
            try {
                transaction.executeWithoutResult(status -> {
                    identities.lockUser(userId);
                    List<Long> ids = jdbc.queryForList("SELECT id FROM recipe_records WHERE user_id = ? AND dish_group_key = ? AND weekly_menu_plan_id IS NULL ORDER BY id",
                            Long.class, userId, key);
                    if (ids.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "菜谱组不存在");
                    for (int index = 0; index < ids.size(); index += 100) {
                        BatchOperationResponse result = operation.apply(ids.subList(index, Math.min(ids.size(), index + 100)));
                        if (result.failureCount() > 0) {
                            throw new ResponseStatusException(HttpStatus.CONFLICT, result.failures().get(0).reason());
                        }
                    }
                });
                success++;
            } catch (RuntimeException failure) {
                String reason = failure instanceof ResponseStatusException response ? response.getReason() : "整组操作失败，请重试";
                failures.add(new BatchOperationFailure(null, reason));
            }
        }
        return new BatchOperationResponse(success, failures.size(), failures);
    }

    private Filter filter(Long userId, Long collectionId, String keyword, String mealType, String goal, String tag) {
        identities.indexLegacyUser(userId);
        List<RecipeCollectionResponse> owned = collections.listCollections(userId);
        RecipeCollectionResponse defaults = owned.stream().filter(RecipeCollectionResponse::defaultCollection).findFirst().orElseThrow();
        if (collectionId != null && owned.stream().noneMatch(item -> item.id().equals(collectionId))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "收藏夹不存在");
        }
        StringBuilder where = new StringBuilder(" WHERE rr.user_id = ? AND rr.weekly_menu_plan_id IS NULL AND " + canonicalRecipe());
        List<Object> args = new ArrayList<>();
        args.add(userId);
        if (collectionId != null) {
            where.append(collectionId.equals(defaults.id()) ? " AND (rci.collection_id IS NULL OR rci.collection_id = ?)" : " AND rci.collection_id = ?");
            args.add(collectionId);
        }
        if (hasText(keyword)) {
            if (keyword.length() > 128) badRequest("搜索条件过长");
            where.append(" AND (rr.title LIKE ? OR sl.query_text LIKE ? OR EXISTS (SELECT 1 FROM recipe_ingredients ri WHERE ri.recipe_id = rr.id AND ri.ingredient_name LIKE ?))");
            for (int index = 0; index < 3; index++) args.add("%" + keyword.trim() + "%");
        }
        if (hasText(mealType)) { where.append(" AND sl.meal_type = ?"); args.add(mealType.trim()); }
        if (hasText(goal)) { where.append(" AND sl.goal = ?"); args.add(goal.trim()); }
        if (hasText(tag)) {
            if (tag.length() > 20) badRequest("标签过长");
            where.append(" AND EXISTS (SELECT 1 FROM recipe_record_tags rrt JOIN recipe_tags rt ON rt.id = rrt.tag_id AND rt.user_id = rr.user_id WHERE rrt.recipe_id = rr.id AND rrt.user_id = rr.user_id AND rt.name = ?)");
            args.add(tag.trim());
        }
        return new Filter(where.toString(), args, defaults);
    }

    private RecipeCollectionResponse defaults(Long userId) {
        return collections.listCollections(userId).stream().filter(RecipeCollectionResponse::defaultCollection).findFirst().orElseThrow();
    }

    private String summarySql(RecipeCollectionResponse defaults) {
        return "SELECT rr.id, rr.title, rr.summary, sl.query_text, sl.meal_type, sl.goal, rr.created_at, rci.collection_id, rc.name AS collection_name,"
                + " (SELECT ri.ingredient_name FROM recipe_ingredients ri WHERE ri.recipe_id = rr.id ORDER BY ri.id LIMIT 1) AS cover_ingredient";
    }

    private RowMapper<SavedRecipeResponse> rowMapper(RecipeCollectionResponse defaults, Long userId) {
        return (rs, index) -> {
            long recipeId = rs.getLong("id");
            List<String> tags = jdbc.queryForList("SELECT t.name FROM recipe_record_tags r JOIN recipe_tags t ON t.id = r.tag_id AND t.user_id = r.user_id WHERE r.user_id = ? AND r.recipe_id = ? ORDER BY t.name", String.class, userId, recipeId);
            Long collectionId = rs.getObject("collection_id", Long.class);
            return new SavedRecipeResponse(recipeId, rs.getString("title"), rs.getString("summary"), rs.getString("query_text"),
                    rs.getString("meal_type"), rs.getString("goal"), rs.getString("cover_ingredient"),
                    rs.getTimestamp("created_at").toLocalDateTime(), collectionId == null ? defaults.id() : collectionId,
                    collectionId == null ? defaults.name() : rs.getString("collection_name"), tags);
        };
    }

    private boolean hasText(String value) { return value != null && !value.isBlank(); }
    private String canonicalRecipe() {
        return "rr.id = (SELECT MIN(original.id) FROM recipe_records original WHERE original.user_id = rr.user_id"
                + " AND original.dish_group_key = rr.dish_group_key AND original.weekly_menu_plan_id IS NULL)";
    }
    private void validateKey(String key) {
        if (key == null || !key.matches("[a-f0-9]{64}")) badRequest("菜谱组标识无效");
    }
    private void badRequest(String message) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }

    private record Filter(String where, List<Object> args, RecipeCollectionResponse defaults) { }
    public record GroupPage(List<Group> items, long total, int page, int size, int totalPages) { }
    public record Group(Long id, String groupKey, int versionCount, String title, String summary,
                        String searchIngredients, String mealType, String goal, String coverIngredient,
                        java.time.LocalDateTime savedAt, Long collectionId, String collectionName, List<String> tags) {
        static Group of(String key, int count, SavedRecipeResponse item) {
            return new Group(item.id(), key, count, item.title(), item.summary(), item.searchIngredients(), item.mealType(),
                    item.goal(), item.coverIngredient(), item.savedAt(), item.collectionId(), item.collectionName(), item.tags());
        }
    }
}
