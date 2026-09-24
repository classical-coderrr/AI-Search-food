package com.example.food.memory;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.Locale;

/**
 * Resolves extracted entities to maintained canonical tags before they enter
 * candidate and consolidated-memory keys. Unknown entities remain usable as
 * raw values and can be mapped later without losing provenance.
 */
@Service
public class TagNormalizationService {

    private static final BigDecimal UNMAPPED_CONFIDENCE = BigDecimal.ZERO.setScale(4);
    private final CanonicalTagMapper tagMapper;

    public TagNormalizationService(CanonicalTagMapper tagMapper) {
        this.tagMapper = tagMapper;
    }

    public TagNormalizationResult normalize(String candidateType, String entity) {
        String original = entity == null ? "" : entity.trim();
        String normalized = normalizeText(original);
        if (!StringUtils.hasText(normalized)) {
            return unmapped(original);
        }

        CanonicalTag tag = tagMapper.findByNormalizedAlias(normalized, categoryFor(candidateType));
        if (tag == null) {
            return unmapped(original);
        }
        String groupId = StringUtils.hasText(tag.getMergeCanonicalId())
                ? tag.getMergeCanonicalId()
                : tag.getCanonicalId();
        return new TagNormalizationResult(
                original,
                tag.getCanonicalId(),
                tag.getId(),
                tag.getCanonicalName(),
                tag.getCategory(),
                tag.getParentCanonicalId(),
                groupId,
                BigDecimal.ONE.setScale(4),
                "ALIAS"
        );
    }

    public String normalizeText(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String normalized = Normalizer.normalize(value.trim(), Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[\\u2018\\u2019\\u201c\\u201d\"`]+", "")
                .replaceAll("[_/\\-]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return normalized;
    }

    private TagNormalizationResult unmapped(String original) {
        String display = original == null ? "" : original.replaceAll("\\s+", " ").trim();
        return new TagNormalizationResult(
                original,
                null,
                null,
                display,
                null,
                null,
                null,
                UNMAPPED_CONFIDENCE,
                "UNMAPPED"
        );
    }

    private String categoryFor(String candidateType) {
        if (!StringUtils.hasText(candidateType)) {
            return null;
        }
        String type = candidateType.trim().toUpperCase(Locale.ROOT);
        if (type.startsWith("INGREDIENT")) return "INGREDIENT";
        if (type.startsWith("CUISINE")) return "CUISINE";
        if (type.startsWith("TASTE")) return "TASTE";
        if (type.startsWith("COOKING_METHOD")) return "COOKING_METHOD";
        if (type.startsWith("DIET_GOAL")) return "DIET_GOAL";
        if (type.startsWith("MEAL_TYPE")) return "MEAL_TYPE";
        if (type.startsWith("NUTRITION")) return "NUTRITION";
        if (type.startsWith("COOKING_DIFFICULTY")) return "COOKING_DIFFICULTY";
        if (type.startsWith("SCENE")) return "SCENE";
        return null;
    }
}
