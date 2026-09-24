package com.example.food.memory;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ContextBudgetManagerTest {

    private final ContextBudgetManager manager = new ContextBudgetManager();

    @Test
    void prioritizesCurrentTaskDeduplicatesAndTruncatesWithinBudget() {
        List<ContextBudgetManager.ContextEntry> entries = List.of(
                new ContextBudgetManager.ContextEntry("KNOWLEDGE_RAG", "DOC", 1L,
                        "用户偏好清淡鸡胸肉", 10, 0),
                new ContextBudgetManager.ContextEntry("SESSION", "SESSION", 2L,
                        "今晚训练后晚餐", 100, 1),
                new ContextBudgetManager.ContextEntry("KNOWLEDGE_RAG", "DOC", 3L,
                        "用户偏好清淡鸡胸肉", 5, 2),
                new ContextBudgetManager.ContextEntry("PERSONAL_MEMORY", "EPISODE", 4L,
                        "历史做过黑椒鸡胸肉饭并给出五星评价", 90, 3)
        );

        ContextBudgetManager.BudgetResult result = manager.allocate(entries, 10);

        assertThat(result.entries()).isNotEmpty();
        assertThat(result.entries().get(0).section()).isEqualTo("SESSION");
        assertThat(result.entries()).anySatisfy(entry -> assertThat(entry.section()).isEqualTo("PERSONAL_MEMORY"));
        assertThat(result.estimatedTokens()).isLessThanOrEqualTo(result.tokenBudget());
        assertThat(result.truncated()).isTrue();
        assertThat(result.entries()).filteredOn(entry -> entry.section().equals("KNOWLEDGE_RAG"))
                .hasSizeLessThanOrEqualTo(1);
    }
}
