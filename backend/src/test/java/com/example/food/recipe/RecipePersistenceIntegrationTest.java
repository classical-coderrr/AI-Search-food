package com.example.food.recipe;

import com.example.food.ai.recipe.dto.RecipeGenerateRequest;
import com.example.food.ai.recipe.dto.RecipeGenerateResponse;
import com.example.food.recipe.dto.RecipeHistoryDetailResponse;
import com.example.food.recipe.dto.RecipeHistorySummaryResponse;
import com.example.food.recipe.dto.SaveRecipeRequest;
import com.example.food.security.AppRole;
import com.example.food.security.AuthPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class RecipePersistenceIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private SearchLogService searchLogService;

    @Autowired
    private SavedRecipeService savedRecipeService;

    @Test
    void persistsSearchAndSavedRecipeThenReloadsHistory() {
        jdbcTemplate.update(
                "INSERT INTO users (phone, nickname, role, enabled) VALUES (?, ?, 'USER', 1)",
                "13900000106",
                "菜谱测试用户"
        );
        Long userId = jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE phone = ?",
                Long.class,
                "13900000106"
        );
        AuthPrincipal principal = new AuthPrincipal(userId, "13900000106", AppRole.USER);

        RecipeGenerateRequest generateRequest = new RecipeGenerateRequest(
                "番茄、鸡蛋",
                "dinner",
                "balanced",
                "text"
        );
        RecipeGenerateResponse generatedRecipe = recipeResponse(null);
        Long searchLogId = searchLogService.record(
                generateRequest,
                generatedRecipe,
                principal,
                "anonymous-integration-test"
        );

        RecipeHistoryDetailResponse saved = savedRecipeService.save(
                saveRequest(searchLogId),
                principal,
                "anonymous-integration-test"
        );
        jdbcTemplate.update(
                "INSERT INTO users (phone, nickname, role, enabled) VALUES (?, ?, 'USER', 1)",
                "13900000107",
                "其他菜谱用户"
        );
        Long otherUserId = jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE phone = ?",
                Long.class,
                "13900000107"
        );
        jdbcTemplate.update(
                "INSERT INTO recipe_records (user_id, title) VALUES (?, ?)",
                otherUserId,
                "其他用户的菜谱"
        );
        Long otherRecipeId = jdbcTemplate.queryForObject(
                "SELECT id FROM recipe_records WHERE user_id = ? AND title = ?",
                Long.class,
                otherUserId,
                "其他用户的菜谱"
        );

        List<RecipeHistorySummaryResponse> history = savedRecipeService.list(userId, null, null, null, 20, 0);
        RecipeHistoryDetailResponse detail = savedRecipeService.detail(userId, saved.id());

        assertThat(searchLogId).isNotNull();
        assertThat(saved.id()).isNotNull();
        assertThat(history).extracting(RecipeHistorySummaryResponse::id).containsExactly(saved.id());
        assertThat(detail.recipe().title()).isEqualTo("番茄炒鸡蛋");
        assertThat(detail.recipe().ingredients()).hasSize(2);
        assertThat(detail.recipe().steps()).hasSize(2);
        assertThat(detail.recipe().nutritionEstimate()).isNotNull();
        assertThat(detail.recipe().nutritionEstimate().caloriesKcal()).isEqualByComparingTo("420");
        assertThat(detail.recipe().missingIngredients()).singleElement().satisfies(item -> {
            assertThat(item.name()).isEqualTo("鸡蛋");
            assertThat(item.substitutes()).containsExactly("嫩豆腐");
        });
        assertThat(detail.recipe().explanation().pairingLogic()).isEqualTo("番茄的酸甜与鸡蛋的鲜香互补");
        assertThat(detail.searchIngredients()).isEqualTo("番茄、鸡蛋");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM recipe_ingredients WHERE recipe_id = ?",
                Integer.class,
                saved.id()
        )).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM recipe_steps WHERE recipe_id = ?",
                Integer.class,
                saved.id()
        )).isEqualTo(2);
        assertThatThrownBy(() -> savedRecipeService.detail(userId, otherRecipeId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(((ResponseStatusException) exception).getStatusCode().value())
                        .isEqualTo(403));
    }

    @Test
    void agentIdempotencyKeyPersistsOneRecipeWhenTheSameSaveIsRetried() {
        Long userId = insertUser("13900000118", "菜谱幂等测试用户");
        AuthPrincipal principal = new AuthPrincipal(userId, "13900000118", AppRole.USER);
        Long searchLogId = insertSearchLog(userId, "番茄、鸡蛋", "dinner", "balanced");
        SaveRecipeRequest request = saveRequest(searchLogId);

        RecipeHistoryDetailResponse first = savedRecipeService.save(request, principal, null, "recipe-retry-key");
        RecipeHistoryDetailResponse retry = savedRecipeService.save(request, principal, null, "recipe-retry-key");

        assertThat(retry.id()).isEqualTo(first.id());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM recipe_records WHERE user_id = ? AND agent_idempotency_key = ?",
                Integer.class,
                userId,
                "recipe-retry-key"
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM recipe_ingredients WHERE recipe_id = ?",
                Integer.class,
                first.id()
        )).isEqualTo(request.ingredients().size());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM recipe_steps WHERE recipe_id = ?",
                Integer.class,
                first.id()
        )).isEqualTo(request.steps().size());
    }

    @Test
    void searchesAndCombinesSavedRecipeFiltersForCurrentUserWithPagination() {
        Long userId = insertUser("13900000116", "筛选测试用户");
        Long otherUserId = insertUser("13900000117", "其他筛选用户");
        LocalDateTime baseTime = LocalDateTime.of(2026, 8, 13, 12, 0);

        Long matchingLogId = insertSearchLog(userId, "番茄、鸡蛋", "dinner", "balanced");
        Long matchingRecipeId = insertRecipe(userId, matchingLogId, "番茄炒蛋", baseTime.plusMinutes(4));
        insertRecipe(userId, insertSearchLog(userId, "番茄", "breakfast", "balanced"),
                "早餐番茄蛋", baseTime.plusMinutes(3));
        insertRecipe(userId, insertSearchLog(userId, "番茄", "dinner", "low-fat"),
                "低脂番茄汤", baseTime.plusMinutes(2));
        insertRecipe(userId, insertSearchLog(userId, "青椒、猪肉", "dinner", "balanced"),
                "青椒肉丝", baseTime.plusMinutes(1));
        insertRecipe(otherUserId, insertSearchLog(otherUserId, "番茄、鸡蛋", "dinner", "balanced"),
                "番茄炒蛋", baseTime.plusMinutes(5));

        List<RecipeHistorySummaryResponse> filtered = savedRecipeService.list(
                userId, "番茄", "dinner", "balanced", 50, 0
        );
        List<RecipeHistorySummaryResponse> paged = savedRecipeService.list(
                userId, "", "不限", "不限", 2, 1
        );

        assertThat(filtered).extracting(RecipeHistorySummaryResponse::id).containsExactly(matchingRecipeId);
        assertThat(paged).extracting(RecipeHistorySummaryResponse::title)
                .containsExactly("早餐番茄蛋", "低脂番茄汤");
    }

    @Test
    void deletesOnlyOwnedRecipeUsingCascadesAndKeepsSearchLog() {
        Long userId = insertUser("13900000126", "删除测试用户");
        Long otherUserId = insertUser("13900000127", "其他删除用户");
        Long searchLogId = insertSearchLog(userId, "番茄、鸡蛋", "dinner", "balanced");
        Long recipeId = insertRecipe(userId, searchLogId, "待删除菜谱", LocalDateTime.now());
        Long otherRecipeId = insertRecipe(otherUserId, null, "他人菜谱", LocalDateTime.now());
        jdbcTemplate.update(
                "INSERT INTO recipe_ingredients (recipe_id, ingredient_name) VALUES (?, ?)",
                recipeId,
                "番茄"
        );
        jdbcTemplate.update(
                "INSERT INTO recipe_steps (recipe_id, step_no, instruction) VALUES (?, ?, ?)",
                recipeId,
                1,
                "切番茄"
        );

        savedRecipeService.delete(userId, recipeId);

        assertThat(count("recipe_records", "id", recipeId)).isZero();
        assertThat(count("recipe_ingredients", "recipe_id", recipeId)).isZero();
        assertThat(count("recipe_steps", "recipe_id", recipeId)).isZero();
        assertThat(count("search_logs", "id", searchLogId)).isEqualTo(1);
        assertThatThrownBy(() -> savedRecipeService.delete(userId, otherRecipeId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(((ResponseStatusException) exception).getStatusCode().value())
                        .isEqualTo(404));
        assertThatThrownBy(() -> savedRecipeService.delete(userId, Long.MAX_VALUE))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(((ResponseStatusException) exception).getStatusCode().value())
                        .isEqualTo(404));
        assertThat(count("recipe_records", "id", otherRecipeId)).isEqualTo(1);
    }

    private Long insertUser(String phone, String nickname) {
        jdbcTemplate.update(
                "INSERT INTO users (phone, nickname, role, enabled) VALUES (?, ?, 'USER', 1)",
                phone,
                nickname
        );
        return jdbcTemplate.queryForObject("SELECT id FROM users WHERE phone = ?", Long.class, phone);
    }

    private Long insertSearchLog(Long userId, String queryText, String mealType, String goal) {
        jdbcTemplate.update(
                """
                INSERT INTO search_logs (user_id, query_text, input_type, recognized_ingredients, meal_type, goal)
                VALUES (?, ?, 'text', '[]', ?, ?)
                """,
                userId,
                queryText,
                mealType,
                goal
        );
        return jdbcTemplate.queryForObject("SELECT MAX(id) FROM search_logs", Long.class);
    }

    private Long insertRecipe(Long userId, Long searchLogId, String title, LocalDateTime createdAt) {
        jdbcTemplate.update(
                "INSERT INTO recipe_records (user_id, search_log_id, title, created_at) VALUES (?, ?, ?, ?)",
                userId,
                searchLogId,
                title,
                createdAt
        );
        return jdbcTemplate.queryForObject("SELECT MAX(id) FROM recipe_records", Long.class);
    }

    private int count(String table, String column, Long value) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?",
                Integer.class,
                value
        );
    }

    private SaveRecipeRequest saveRequest(Long searchLogId) {
        RecipeGenerateResponse recipe = recipeResponse(searchLogId);
        return new SaveRecipeRequest(
                searchLogId,
                recipe.title(),
                recipe.summary(),
                recipe.effects(),
                recipe.ingredients(),
                recipe.missingIngredients(),
                recipe.steps(),
                recipe.tips(),
                recipe.videoKeywords(),
                recipe.explanation(),
                recipe.nutritionEstimate(),
                recipe.provider(),
                recipe.model()
        );
    }

    private RecipeGenerateResponse recipeResponse(Long searchLogId) {
        return new RecipeGenerateResponse(
                "番茄炒鸡蛋",
                "家常快手菜",
                List.of("补充蛋白质"),
                List.of(
                        new RecipeGenerateResponse.Ingredient("番茄", "2个"),
                        new RecipeGenerateResponse.Ingredient("鸡蛋", "3个")
                ),
                List.of(new RecipeGenerateResponse.MissingIngredient(
                        "鸡蛋",
                        "3个",
                        List.of("嫩豆腐"),
                        "用户未提供鸡蛋"
                )),
                List.of(
                        new RecipeGenerateResponse.Step(1, "准备食材", "番茄切块并打散鸡蛋", 5),
                        new RecipeGenerateResponse.Step(2, "翻炒", "依次炒熟鸡蛋和番茄", 8)
                ),
                List.of("番茄出汁后再调味"),
                List.of("番茄炒鸡蛋教程"),
                new RecipeGenerateResponse.Explanation(
                        "番茄的酸甜与鸡蛋的鲜香互补",
                        "包含蛋白质与维生素",
                        "分开炒制能保持嫩度"
                ),
                new RecipeGenerateResponse.NutritionEstimate(
                        2,
                        new java.math.BigDecimal("420"),
                        new java.math.BigDecimal("24"),
                        new java.math.BigDecimal("16"),
                        new java.math.BigDecimal("42"),
                        RecipeGenerateResponse.NutritionEstimate.AI_ESTIMATE
                ),
                "qwen",
                "qwen-plus",
                searchLogId
        );
    }
}
