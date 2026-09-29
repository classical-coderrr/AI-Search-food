package com.example.food.weekly;

import com.example.food.weekly.dto.WeeklyMenuItemRequest;
import com.example.food.weekly.dto.WeeklyMenuResponse;
import com.example.food.weekly.dto.WeeklyMenuSaveRequest;
import com.example.food.weekly.dto.WeeklyMenuShoppingStatusRequest;
import com.example.food.weekly.dto.WeeklyShoppingStatusResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class WeeklyMenuPersistenceIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private WeeklyMenuService weeklyMenuService;

    private Long userId;
    private Long firstRecipeId;
    private Long secondRecipeId;
    private final LocalDate monday = LocalDate.of(2026, 8, 31);

    @BeforeEach
    void setUp() {
        String phone = "139" + String.format("%08d", Math.abs(System.nanoTime() % 100_000_000));
        jdbcTemplate.update(
                "INSERT INTO users (phone, nickname) VALUES (?, ?)",
                phone,
                "weekly-menu-integration"
        );
        userId = jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE phone = ?",
                Long.class,
                phone
        );
        firstRecipeId = insertRecipe("番茄炒蛋");
        secondRecipeId = insertRecipe("鸡蛋汤");
        insertIngredient(firstRecipeId, "番茄", "2个");
        insertIngredient(firstRecipeId, "鸡蛋", "2个");
        insertIngredient(secondRecipeId, "鸡蛋", "3个");
        insertIngredient(secondRecipeId, "食用油", "100克");
        jdbcTemplate.update(
                "INSERT INTO user_pantry_items (user_id, ingredient_name) VALUES (?, ?)",
                userId,
                "番茄"
        );
    }

    @AfterEach
    void tearDown() {
        if (userId != null) {
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
        }
    }

    @Test
    void persistsWeeklyMenuAggregatesIngredientsAndUpdatesShoppingStatus() {
        WeeklyMenuResponse saved = weeklyMenuService.save(userId, new WeeklyMenuSaveRequest(
                monday.plusDays(2),
                List.of(
                        new WeeklyMenuItemRequest(monday, "BREAKFAST", firstRecipeId),
                        new WeeklyMenuItemRequest(monday.plusDays(1), "DINNER", secondRecipeId)
                )
        ));

        assertThat(saved.id()).isNotNull();
        assertThat(saved.weekStart()).isEqualTo(monday);
        assertThat(saved.items()).hasSize(2);
        assertThat(saved.shoppingItems()).extracting("ingredientName")
                .containsExactly("番茄", "鸡蛋", "食用油");
        assertThat(saved.shoppingItems().get(0).alreadyOwned()).isTrue();
        assertThat(saved.shoppingItems().get(1).amount()).isEqualTo("5个");

        List<Map<String, Object>> recordedEpisodes = jdbcTemplate.queryForList(
                "SELECT episode_type, source_type, source_id, idempotency_key, payload_json "
                        + "FROM memory_episodes WHERE user_id = ? AND episode_type = ?",
                userId,
                "WEEKLY_MENU_SELECTED"
        );
        assertThat(recordedEpisodes).hasSize(1);
        assertThat(recordedEpisodes.get(0).get("source_type")).isEqualTo("WEEKLY_MENU_PLAN");
        assertThat(recordedEpisodes.get(0).get("source_id").toString()).isEqualTo(saved.id().toString());
        assertThat(recordedEpisodes.get(0).get("idempotency_key").toString())
                .startsWith("weekly-menu:" + saved.id() + ":");
        assertThat(recordedEpisodes.get(0).get("payload_json").toString())
                .contains("番茄炒蛋", "鸡蛋汤", "BREAKFAST", "DINNER");

        weeklyMenuService.save(userId, new WeeklyMenuSaveRequest(
                monday,
                List.of(
                        new WeeklyMenuItemRequest(monday, "BREAKFAST", firstRecipeId),
                        new WeeklyMenuItemRequest(monday.plusDays(1), "DINNER", secondRecipeId)
                )
        ));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM memory_episodes WHERE user_id = ? AND episode_type = ?",
                Integer.class,
                userId,
                "WEEKLY_MENU_SELECTED"
        )).isEqualTo(1);

        WeeklyShoppingStatusResponse status = weeklyMenuService.saveShoppingStatus(
                userId,
                new WeeklyMenuShoppingStatusRequest(monday.plusDays(4), "鸡蛋", "PURCHASING")
        );
        WeeklyMenuResponse loaded = weeklyMenuService.get(userId, monday.plusDays(6));

        assertThat(status.status()).isEqualTo("PURCHASING");
        assertThat(loaded.shoppingItems().stream()
                .filter(item -> item.ingredientName().equals("鸡蛋"))
                .findFirst()
                .orElseThrow()
                .status()).isEqualTo("PURCHASING");
    }

    private Long insertRecipe(String title) {
        jdbcTemplate.update(
                "INSERT INTO recipe_records (user_id, title, ai_model) VALUES (?, ?, ?)",
                userId,
                title,
                "test:qwen"
        );
        return jdbcTemplate.queryForObject(
                "SELECT id FROM recipe_records WHERE user_id = ? AND title = ?",
                Long.class,
                userId,
                title
        );
    }

    private void insertIngredient(Long recipeId, String name, String amount) {
        jdbcTemplate.update(
                "INSERT INTO recipe_ingredients (recipe_id, ingredient_name, amount) VALUES (?, ?, ?)",
                recipeId,
                name,
                amount
        );
    }
}
