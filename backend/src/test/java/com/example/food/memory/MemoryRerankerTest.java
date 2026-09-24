package com.example.food.memory;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryRerankerTest {

    @Test
    void ranksSemanticAndRecentMatchesAndReturnsExplainableFactors() {
        MemoryRankingProperties properties = new MemoryRankingProperties();
        MemoryReranker reranker = new MemoryReranker(properties,
                Clock.fixed(Instant.parse("2026-09-24T10:00:00Z"), ZoneOffset.UTC));
        MemoryQueryPlan plan = new MemoryQueryPlan("健身后鸡胸肉", "POST_WORKOUT_RECIPE_RECALL",
                "健身后鸡胸肉 训练后 高蛋白", null, List.of(), List.of(), List.of(), List.of(),
                null, null, List.of(), List.of(), null, null, 5);
        MemorySearchHit relevant = hit(1L, "黑椒鸡胸肉训练后晚餐", "POST_WORKOUT", "EXPLICIT_PREFERENCE",
                LocalDateTime.parse("2026-09-23T10:00:00"), "LIKE");
        MemorySearchHit unrelated = hit(2L, "番茄意面午餐", "WEEKDAY_LUNCH", "SHORT_TERM_TREND",
                LocalDateTime.parse("2026-08-01T10:00:00"), "LIKE");

        List<MemorySearchHit> ranked = reranker.rerank(plan, List.of(unrelated, relevant));

        assertThat(ranked.get(0).id()).isEqualTo(1L);
        assertThat(ranked.get(0).scores().semantic()).isPositive();
        assertThat(ranked.get(0).scores().recency()).isPositive();
        assertThat(ranked.get(0).scores().total()).isBetween(0.0, 1.0);
    }

    private MemorySearchHit hit(Long id, String title, String payload, String temporal,
                                LocalDateTime occurredAt, String preference) {
        return new MemorySearchHit("MEMORY_ITEM", id, "INGREDIENT_PREFERENCE", title,
                title, payload, preference, temporal, BigDecimal.valueOf(0.8),
                BigDecimal.valueOf(0.7), occurredAt, null);
    }
}
