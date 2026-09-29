package com.example.food.memory;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PromptTemplateManagerTest {

    private final PromptTemplateManager manager = new PromptTemplateManager();

    @Test
    void loadsAllVersionedMemoryPromptResources() {
        for (String prompt : new String[]{
                "memory-agent-context-v1",
                "memory-extraction-v1",
                "memory-query-rewrite-v1",
                "memory-merge-v1",
                "profile-consolidation-v1",
                "personalized-skill-v1",
                "personalized-skill-v2"
        }) {
            assertThat(manager.require(prompt)).isNotBlank();
        }
    }

    @Test
    void rendersContextWithoutLeavingTemplateVariablesBehind() {
        String rendered = manager.render("memory-agent-context-v1", Map.of("memoryContext", "[PERSONAL_MEMORY]\n香菜"));

        assertThat(rendered).contains("LIKE/liked 只表示喜欢或倾向", "[PERSONAL_MEMORY]\n香菜")
                .doesNotContain("{{memoryContext}}");
    }

    @Test
    void rejectsUnknownVersionsInvalidNamesAndMissingVariables() {
        assertThatThrownBy(() -> manager.require("../application"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> manager.require("memory-extraction-v999"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> manager.render("memory-agent-context-v1", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("memoryContext");
    }
}
