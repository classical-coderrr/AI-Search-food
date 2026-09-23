package com.example.food.pantry;

import com.example.food.pantry.dto.PantryItemResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class UserPantryPersistenceIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserPantryService service;

    private Long userId;

    @BeforeEach
    void setUp() {
        String phone = "139" + String.format("%08d", Math.abs(System.nanoTime() % 100_000_000));
        jdbcTemplate.update(
                "INSERT INTO users (phone, nickname) VALUES (?, ?)",
                phone,
                "pantry-consume-integration"
        );
        userId = jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE phone = ?",
                Long.class,
                phone
        );
    }

    @AfterEach
    void tearDown() {
        if (userId != null) {
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
        }
    }

    @Test
    void atomicallyDecrementsPersistedQuantityAndRejectsOverConsumption() {
        jdbcTemplate.update(
                "INSERT INTO user_pantry_items (user_id, ingredient_name, quantity, unit) VALUES (?, ?, ?, ?)",
                userId,
                "番茄",
                new BigDecimal("2.50"),
                "个"
        );
        Long itemId = jdbcTemplate.queryForObject(
                "SELECT id FROM user_pantry_items WHERE user_id = ? AND ingredient_name = ?",
                Long.class,
                userId,
                "番茄"
        );

        assertThat(service.consume(userId, itemId, new BigDecimal("1.25")).quantity())
                .isEqualByComparingTo("1.25");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT quantity FROM user_pantry_items WHERE id = ?",
                BigDecimal.class,
                itemId
        )).isEqualByComparingTo("1.25");

        assertThatThrownBy(() -> service.consume(userId, itemId, new BigDecimal("1.26")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("库存数量不足");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT quantity FROM user_pantry_items WHERE id = ?",
                BigDecimal.class,
                itemId
        )).isEqualByComparingTo("1.25");
    }

    @Test
    void directPantryActionsPersistMemoryEpisodes() {
        PantryItemResponse created = service.create(userId, new com.example.food.pantry.dto.PantryItemRequest(
                "potato", "vegetable", new BigDecimal("2"), "piece", null
        ));

        service.consume(userId, created.id(), new BigDecimal("1"));

        assertThat(jdbcTemplate.queryForList(
                "SELECT episode_type FROM memory_episodes WHERE user_id = ? AND source_type = ? ORDER BY id",
                String.class,
                userId,
                "PANTRY_DIRECT"
        )).containsExactly("PANTRY_STOCK_IN", "PANTRY_CONSUMED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM memory_episodes WHERE user_id = ? AND source_type = ?",
                Integer.class,
                userId,
                "PANTRY_DIRECT"
        )).isEqualTo(2);
    }

    @Test
    void expirySummaryOnlyReturnsItemsWithStockWithinWarningWindow() {
        LocalDate today = LocalDate.now();
        jdbcTemplate.update(
                "INSERT INTO user_pantry_items (user_id, ingredient_name, quantity, unit, expire_date) VALUES (?, ?, ?, ?, ?)",
                userId,
                "西兰花",
                new BigDecimal("1"),
                "棵",
                today.minusDays(1)
        );
        jdbcTemplate.update(
                "INSERT INTO user_pantry_items (user_id, ingredient_name, quantity, unit, expire_date) VALUES (?, ?, ?, ?, ?)",
                userId,
                "牛奶",
                new BigDecimal("2"),
                "盒",
                today.plusDays(3)
        );
        jdbcTemplate.update(
                "INSERT INTO user_pantry_items (user_id, ingredient_name, quantity, unit, expire_date) VALUES (?, ?, ?, ?, ?)",
                userId,
                "鸡蛋",
                BigDecimal.ZERO,
                "个",
                today.minusDays(1)
        );
        jdbcTemplate.update(
                "INSERT INTO user_pantry_items (user_id, ingredient_name, quantity, unit, expire_date) VALUES (?, ?, ?, ?, ?)",
                userId,
                "大米",
                new BigDecimal("1"),
                "袋",
                today.plusDays(8)
        );

        var summary = service.expirySummary(userId);

        assertThat(summary.expiredItems()).extracting(PantryItemResponse::ingredientName)
                .containsExactly("西兰花");
        assertThat(summary.expiringSoonItems()).extracting(PantryItemResponse::ingredientName)
                .containsExactly("牛奶");
    }

    @Test
    void createMergesRowsWithSameNameCategoryAndExpiryDate() {
        LocalDate expireDate = LocalDate.now().plusDays(10);

        service.create(userId, new com.example.food.pantry.dto.PantryItemRequest(
                "西红柿", "蔬菜", new BigDecimal("2.00"), "个", expireDate
        ));
        service.create(userId, new com.example.food.pantry.dto.PantryItemRequest(
                "番茄", "蔬菜", new BigDecimal("1.50"), "个", expireDate
        ));

        Integer rowCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_pantry_items WHERE user_id = ? AND category = ? AND expire_date = ?",
                Integer.class,
                userId,
                "蔬菜",
                expireDate
        );
        BigDecimal quantity = jdbcTemplate.queryForObject(
                "SELECT quantity FROM user_pantry_items WHERE user_id = ? AND category = ? AND expire_date = ?",
                BigDecimal.class,
                userId,
                "蔬菜",
                expireDate
        );

        assertThat(rowCount).isEqualTo(1);
        assertThat(quantity).isEqualByComparingTo("3.50");
    }
}
