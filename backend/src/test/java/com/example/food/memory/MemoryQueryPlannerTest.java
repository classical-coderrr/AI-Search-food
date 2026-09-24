package com.example.food.memory;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MemoryQueryPlannerTest {

    private final MemoryQueryPlanner planner = new MemoryQueryPlanner(
            Clock.fixed(Instant.parse("2026-09-24T10:00:00Z"), ZoneOffset.UTC));

    @Test
    void rewritesPostWorkoutDinnerWithoutHardFilteringInferredMetadata() {
        MemoryQueryPlan plan = planner.plan(7L, MemorySearchCommand.query("今晚健身完吃什么"));

        assertThat(plan.intent()).isEqualTo("POST_WORKOUT_RECIPE_RECALL");
        assertThat(plan.rewrittenQuery()).contains("训练后", "晚餐");
        assertThat(plan.scenes()).isEmpty();
        assertThat(plan.mealTypes()).isEmpty();
    }

    @Test
    void enforcesIdentityAndValidThresholds() {
        assertThatThrownBy(() -> planner.plan(null, MemorySearchCommand.query("菜谱")))
                .isInstanceOf(ResponseStatusException.class);
        MemorySearchCommand invalidThreshold = new MemorySearchCommand("菜谱", null, null, null,
                null, null, null, null, null, null,
                java.math.BigDecimal.valueOf(1.1), null, 5);
        assertThatThrownBy(() -> planner.plan(7L, invalidThreshold))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void preservesOnlyBoundedExplicitFilters() {
        MemorySearchCommand command = new MemorySearchCommand("鸡胸肉", null, null, null,
                List.of(" post_workout "), List.of("dinner"), null, null,
                List.of("鸡胸肉"), null, null, null, 1000);

        MemoryQueryPlan plan = planner.plan(7L, command);

        assertThat(plan.scenes()).containsExactly("POST_WORKOUT");
        assertThat(plan.mealTypes()).containsExactly("DINNER");
        assertThat(plan.limit()).isEqualTo(50);
    }
}
