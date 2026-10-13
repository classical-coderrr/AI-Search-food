package com.example.food.recipe;

import com.example.food.ai.recipe.dto.RecipeGenerateResponse.Ingredient;
import com.example.food.ai.recipe.dto.RecipeGenerateResponse.Step;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class RecipeContentIdentityTest {
    @Test
    void ingredientOrderAndWhitespaceDoNotCreateAnotherVersion() {
        var steps = List.of(new Step(1, "炒制", "炒熟鸡蛋", 5));
        String first = RecipeContentIdentity.fingerprint("番茄炒蛋", List.of(new Ingredient("番茄", "200 克"), new Ingredient("鸡蛋", "2个")), steps);
        String second = RecipeContentIdentity.fingerprint(" 番茄炒蛋 ", List.of(new Ingredient("鸡蛋", "2个"), new Ingredient("番茄", "２００克")), steps);
        assertThat(first).isEqualTo(second);
    }

    @Test
    void meaningfulAmountsAndStepOrderPreserveDifferentVersions() {
        var first = new Step(1, "准备", "准备材料", 3);
        var second = new Step(2, "炒制", "炒熟鸡蛋", 5);
        var ingredients = List.of(new Ingredient("鸡蛋", "2个"));
        String fingerprint = RecipeContentIdentity.fingerprint("炒蛋", ingredients, List.of(first, second));
        assertThat(RecipeContentIdentity.fingerprint("炒蛋", List.of(new Ingredient("鸡蛋", "3个")), List.of(first, second))).isNotEqualTo(fingerprint);
        assertThat(RecipeContentIdentity.fingerprint("炒蛋", ingredients, List.of(second, first))).isNotEqualTo(fingerprint);
        assertThat(RecipeContentIdentity.groupKey(" 炒蛋 ")).isEqualTo(RecipeContentIdentity.groupKey("炒蛋"));
    }
}
