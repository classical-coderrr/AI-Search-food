package com.example.food.ai.recipe;

import com.example.food.ai.recipe.dto.RecipeGenerateRequest;
import com.example.food.ai.recipe.dto.RecipeGenerateResponse;
import com.example.food.recipe.SearchLogService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@SpringBootTest
@ActiveProfiles("test")
class RecipeRecommendationBatchPersistenceIntegrationTest {

    @Autowired
    private RecipeRecommendationService recommendationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @SpyBean
    private SearchLogService searchLogService;

    @Test
    @Transactional
    void persistsEveryRecipeAndReturnsEverySearchLogId() {
        String anonymousId = "batch-success-" + UUID.randomUUID();

        List<RecipeGenerateResponse> persisted = recommendationService.persistBatch(
                new RecipeGenerateRequest("番茄、鸡蛋", "dinner", "balanced", "text"),
                List.of(recipe("番茄炒蛋"), recipe("番茄蛋花汤"), recipe("番茄蒸蛋")),
                3,
                null,
                anonymousId
        );

        assertThat(persisted).hasSize(3)
                .allSatisfy(item -> assertThat(item.searchLogId()).isNotNull());
        assertThat(persisted).extracting(RecipeGenerateResponse::searchLogId).doesNotHaveDuplicates();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM search_logs WHERE anonymous_id = ?",
                Integer.class,
                anonymousId
        )).isEqualTo(3);
    }

    @Test
    void rejectsAnUndersizedBatchBeforePersistingAnyRecipe() {
        String anonymousId = "batch-incomplete-" + UUID.randomUUID();

        assertThatThrownBy(() -> recommendationService.persistBatch(
                new RecipeGenerateRequest("番茄、鸡蛋", "dinner", "balanced", "text"),
                List.of(recipe("番茄炒蛋")),
                3,
                null,
                anonymousId
        )).isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM search_logs WHERE anonymous_id = ?",
                Integer.class,
                anonymousId
        )).isZero();
    }

    @Test
    void rollsBackEarlierSearchLogsWhenPersistingOneRecipeFails() {
        String anonymousId = "batch-rollback-" + UUID.randomUUID();
        AtomicInteger records = new AtomicInteger();
        doAnswer(invocation -> {
            if (records.incrementAndGet() == 2) {
                throw new IllegalStateException("simulated second recipe persistence failure");
            }
            return invocation.callRealMethod();
        }).when(searchLogService).record(any(), any(), isNull(), eq(anonymousId));

        assertThatThrownBy(() -> recommendationService.persistBatch(
                new RecipeGenerateRequest("番茄、鸡蛋", "dinner", "balanced", "text"),
                List.of(recipe("番茄炒蛋"), recipe("番茄蛋花汤"), recipe("番茄蒸蛋")),
                3,
                null,
                anonymousId
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("simulated second recipe persistence failure");

        Integer persistedCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM search_logs WHERE anonymous_id = ?",
                Integer.class,
                anonymousId
        );
        assertThat(persistedCount).isZero();
        verify(searchLogService, times(2)).record(any(), any(), isNull(), eq(anonymousId));
    }

    private RecipeGenerateResponse recipe(String title) {
        return new RecipeGenerateResponse(
                title,
                "家常菜",
                List.of(),
                List.of(new RecipeGenerateResponse.Ingredient("番茄", "2个")),
                List.of(new RecipeGenerateResponse.Step(1, "炒制", "完成烹饪", 5)),
                List.of(),
                List.of(),
                "qwen",
                "qwen-plus"
        );
    }
}
