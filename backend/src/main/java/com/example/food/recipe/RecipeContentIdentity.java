package com.example.food.recipe;

import com.example.food.ai.recipe.dto.RecipeGenerateResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/** Versioned deterministic identity; presentation-only fields are deliberately excluded. */
public final class RecipeContentIdentity {
    private RecipeContentIdentity() { }

    public static String normalize(String value) {
        return value == null ? "" : Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{Z}]+", "").trim();
    }

    public static String groupKey(String title) {
        return hash("dish-v1|" + normalize(title));
    }

    public static String fingerprint(String title, List<RecipeGenerateResponse.Ingredient> ingredients,
                                     List<RecipeGenerateResponse.Step> steps) {
        List<String> normalizedIngredients = ingredients.stream()
                .map(item -> part(normalize(item.name())) + part(normalize(item.amount()))).sorted().toList();
        List<String> normalizedSteps = steps.stream()
                .map(step -> part(normalize(step.description())) + part(String.valueOf(step.durationMinutes())))
                .toList();
        return hash("recipe-v1|" + part(normalize(title)) + part(String.join("", normalizedIngredients))
                + part(String.join("", normalizedSteps)));
    }

    private static String part(String value) {
        return value.length() + ":" + value;
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
