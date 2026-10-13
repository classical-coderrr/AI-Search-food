package com.example.food.recipe;

import com.example.food.ai.recipe.dto.RecipeGenerateResponse;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class SavedRecipeIdentityService {
    private final JdbcTemplate jdbc;

    public SavedRecipeIdentityService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void lockUser(Long userId) {
        jdbc.queryForObject("SELECT id FROM users WHERE id = ? FOR UPDATE", Long.class, userId);
    }

    @Transactional
    public void indexLegacyUser(Long userId) {
        lockUser(userId);
        indexLegacy(jdbc, userId);
    }

    public Long findByDish(Long userId, String groupKey) {
        List<Long> recipes = jdbc.queryForList("SELECT id FROM recipe_records WHERE user_id = ? AND dish_group_key = ?"
                + " AND weekly_menu_plan_id IS NULL ORDER BY id LIMIT 1 FOR UPDATE", Long.class, userId, groupKey);
        return recipes.isEmpty() ? null : recipes.get(0);
    }

    /** Called inside the save transaction. Both reservations remain locked until commit. */
    public Long reserve(Long userId, Long searchLogId, String fingerprint, String groupKey) {
        indexLegacyUser(userId);
        insertSource(userId, searchLogId);
        Long source = jdbc.queryForObject("SELECT recipe_id FROM recipe_saved_sources WHERE user_id = ? AND search_log_id = ? FOR UPDATE",
                Long.class, userId, searchLogId);
        if (source != null) return source;
        try {
            jdbc.update("INSERT INTO recipe_dish_identities(user_id, dish_group_key) VALUES (?, ?)", userId, groupKey);
        } catch (DuplicateKeyException duplicate) {
            // Same normalized dish name is unique for each user, including concurrent saves.
        }
        Long dish = jdbc.queryForObject("SELECT recipe_id FROM recipe_dish_identities WHERE user_id = ? AND dish_group_key = ? FOR UPDATE",
                Long.class, userId, groupKey);
        if (dish == null) dish = findByDish(userId, groupKey);
        if (dish != null) {
            bindDish(userId, searchLogId, groupKey, dish);
            return dish;
        }
        try {
            jdbc.update("INSERT INTO recipe_content_identities(user_id, content_fingerprint) VALUES (?, ?)", userId, fingerprint);
        } catch (DuplicateKeyException duplicate) {
            // A unique reservation also protects callers on another application instance.
        }
        Long recipeId = jdbc.queryForObject("SELECT recipe_id FROM recipe_content_identities WHERE user_id = ? AND content_fingerprint = ? FOR UPDATE",
                Long.class, userId, fingerprint);
        if (recipeId == null) {
            List<Long> legacy = jdbc.queryForList("SELECT id FROM recipe_records WHERE user_id = ? AND content_fingerprint = ? AND weekly_menu_plan_id IS NULL ORDER BY id LIMIT 1",
                    Long.class, userId, fingerprint);
            recipeId = legacy.isEmpty() ? null : legacy.get(0);
        }
        if (recipeId != null) bind(userId, searchLogId, fingerprint, recipeId);
        return recipeId;
    }

    public void bind(Long userId, Long searchLogId, String fingerprint, Long recipeId) {
        jdbc.update("UPDATE recipe_content_identities SET recipe_id = ? WHERE user_id = ? AND content_fingerprint = ?",
                recipeId, userId, fingerprint);
        jdbc.update("UPDATE recipe_saved_sources SET recipe_id = ? WHERE user_id = ? AND search_log_id = ?",
                recipeId, userId, searchLogId);
    }

    public void bindDish(Long userId, Long searchLogId, String groupKey, Long recipeId) {
        jdbc.update("UPDATE recipe_dish_identities SET recipe_id = ? WHERE user_id = ? AND dish_group_key = ?",
                recipeId, userId, groupKey);
        jdbc.update("UPDATE recipe_saved_sources SET recipe_id = ? WHERE user_id = ? AND search_log_id = ?",
                recipeId, userId, searchLogId);
    }

    private void insertSource(Long userId, Long searchLogId) {
        try {
            jdbc.update("INSERT INTO recipe_saved_sources(user_id, search_log_id) VALUES (?, ?)", userId, searchLogId);
        } catch (DuplicateKeyException duplicate) {
            // Reuse the existing source reservation.
        }
    }

    public static void indexLegacy(JdbcTemplate jdbc, Long requestedUserId) {
        long afterId = 0;
        while (true) {
            List<LegacyRecipe> rows = jdbc.query("SELECT id, user_id, search_log_id, title FROM recipe_records WHERE user_id IS NOT NULL AND weekly_menu_plan_id IS NULL AND dish_group_key IS NULL AND id > ?"
                            + (requestedUserId == null ? "" : " AND user_id = " + requestedUserId.longValue())
                            + " ORDER BY id LIMIT 200",
                    (rs, index) -> new LegacyRecipe(rs.getLong("id"), rs.getLong("user_id"),
                            rs.getObject("search_log_id", Long.class), rs.getString("title")), afterId);
            if (rows.isEmpty()) return;
            for (LegacyRecipe row : rows) {
                List<RecipeGenerateResponse.Ingredient> ingredients = jdbc.query(
                        "SELECT ingredient_name, amount FROM recipe_ingredients WHERE recipe_id = ? ORDER BY id",
                        (rs, index) -> new RecipeGenerateResponse.Ingredient(rs.getString(1), rs.getString(2)), row.id());
                List<RecipeGenerateResponse.Step> steps = jdbc.query(
                        "SELECT step_no, title, instruction, estimated_minutes FROM recipe_steps WHERE recipe_id = ? ORDER BY step_no, id",
                        (rs, index) -> new RecipeGenerateResponse.Step(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getObject(4, Integer.class)), row.id());
                // Incomplete legacy rows get a distinct identity so missing data never causes destructive merging.
                String fingerprint = ingredients.isEmpty() || steps.isEmpty()
                        ? RecipeContentIdentity.groupKey("legacy:" + row.id())
                        : RecipeContentIdentity.fingerprint(row.title(), ingredients, steps);
                jdbc.update("UPDATE recipe_records SET dish_group_key = ?, content_fingerprint = ? WHERE id = ?",
                        RecipeContentIdentity.groupKey(row.title()), fingerprint, row.id());
                try {
                    jdbc.update("INSERT INTO recipe_content_identities(user_id, content_fingerprint, recipe_id) VALUES (?, ?, ?)",
                            row.userId(), fingerprint, row.id());
                } catch (DuplicateKeyException duplicate) { /* Keep the first original ID as the save identity. */ }
                if (row.searchLogId() != null) {
                    try {
                        jdbc.update("INSERT INTO recipe_saved_sources(user_id, search_log_id, recipe_id) VALUES (?, ?, ?)",
                                row.userId(), row.searchLogId(), row.id());
                    } catch (DuplicateKeyException duplicate) { /* Preserve existing source mapping. */ }
                }
                afterId = row.id();
            }
        }
    }

    private record LegacyRecipe(Long id, Long userId, Long searchLogId, String title) { }
}
