package com.example.food.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void prefersIngredientArrayWhenRecipeToolProvidesIt() throws Exception {
        JsonNode arguments = objectMapper.readTree("""
                {
                  "ingredients": ["番茄", "茄子", "鸡肉", "牛肉", "螃蟹"],
                  "meal_type": "dinner",
                  "servings": 3
                }
                """);

        assertThat(AgentService.recipeIngredientArgument(arguments, "今晚能做什么"))
                .isEqualTo("番茄、茄子、鸡肉、牛肉、螃蟹");
    }

    @Test
    void fallsBackToRequestTextWhenIngredientArrayIsMissing() throws Exception {
        JsonNode arguments = objectMapper.readTree("{\"meal_type\":\"dinner\"}");

        assertThat(AgentService.recipeIngredientArgument(arguments, "番茄和鸡蛋"))
                .isEqualTo("番茄和鸡蛋");
    }
}
