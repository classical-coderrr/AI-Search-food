package com.example.food.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Extracts bounded, explainable memory candidates from an Episode.
 *
 * <p>This first implementation is deliberately deterministic. It gives the
 * persistence and provenance boundary a stable contract before an LLM-backed
 * extractor is introduced. Search and pantry events are retained as Episodes
 * but do not, by themselves, become long-term preferences.</p>
 */
@Service
public class MemoryExtractor {

    private static final BigDecimal SAVED_STRENGTH = new BigDecimal("0.5500");
    private static final BigDecimal SAVED_CONFIDENCE = new BigDecimal("0.4500");
    private static final BigDecimal FEEDBACK_STRENGTH = new BigDecimal("0.9000");
    private static final BigDecimal FEEDBACK_CONFIDENCE = new BigDecimal("0.8500");
    private static final BigDecimal COOKED_STRENGTH = new BigDecimal("0.6500");
    private static final BigDecimal COOKED_CONFIDENCE = new BigDecimal("0.6500");
    private static final int MAX_INGREDIENT_CANDIDATES = 30;
    private static final int MAX_TEXT_LENGTH = 512;

    private final ObjectMapper objectMapper;

    public MemoryExtractor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<MemoryCandidateDraft> extract(MemoryEpisode episode) {
        if (episode == null || !StringUtils.hasText(episode.getPayloadJson())) {
            return List.of();
        }

        JsonNode payload;
        try {
            payload = objectMapper.readTree(episode.getPayloadJson());
        } catch (Exception ignored) {
            return List.of();
        }
        if (payload == null || !payload.isObject()) {
            return List.of();
        }

        String episodeType = upper(episode.getEpisodeType());
        return switch (episodeType) {
            case "RECIPE_SAVED" -> extractSaved(episode, payload);
            case "RECIPE_FEEDBACK" -> extractFeedback(episode, payload);
            case "FINISHED_DISH_REVIEW" -> extractFinishedDishReview(episode, payload);
            case "USER_PREFERENCE_DECLARED", "USER_PREFERENCE_CONFIRMED" ->
                    extractExplicitPreference(episode, payload);
            default -> List.of();
        };
    }

    private List<MemoryCandidateDraft> extractSaved(MemoryEpisode episode, JsonNode payload) {
        List<MemoryCandidateDraft> drafts = new ArrayList<>();
        String title = firstText(payload, "recipeTitle", "title", "resultTitle", "recipeName");
        if (title != null) {
            drafts.add(draft(
                    "RECIPE_PREFERENCE",
                    title,
                    "LIKE",
                    SAVED_STRENGTH,
                    SAVED_CONFIDENCE,
                    "IMPLICIT_BEHAVIOR",
                    "RECENT",
                    "收藏菜谱：" + title,
                    false,
                    evidence(episode, "SAVE", title)
            ));
        }

        int count = 0;
        for (String ingredient : texts(payload.path("ingredients"))) {
            if (count++ >= MAX_INGREDIENT_CANDIDATES) {
                break;
            }
            drafts.add(draft(
                    "INGREDIENT_PREFERENCE",
                    ingredient,
                    "LIKE",
                    new BigDecimal("0.4500"),
                    new BigDecimal("0.4000"),
                    "IMPLICIT_BEHAVIOR",
                    "RECENT",
                    "收藏菜谱中的食材：" + ingredient,
                    false,
                    evidence(episode, "SAVE", ingredient)
            ));
        }

        String dietGoal = confirmableDietGoal(text(payload, "goal"));
        if (dietGoal != null) {
            drafts.add(draft(
                    "DIET_GOAL",
                    dietGoal,
                    "PURSUE",
                    new BigDecimal("0.5500"),
                    new BigDecimal("0.4500"),
                    "IMPLICIT_BEHAVIOR",
                    "RECENT",
                    "选择饮食目标并收藏菜谱：" + dietGoal,
                    false,
                    evidence(episode, "SAVE_GOAL", dietGoal)
            ));
        }
        return drafts;
    }

    private String confirmableDietGoal(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_')) {
            case "protein", "high_protein", "高蛋白" -> "高蛋白";
            case "light", "low_calorie", "低热量" -> "低热量";
            case "fat_loss", "weight_loss", "减脂", "减重" -> "减脂";
            case "muscle_gain", "增肌", "肌肉增长" -> "增肌";
            case "low_sugar", "控糖" -> "控糖";
            default -> null;
        };
    }

    private List<MemoryCandidateDraft> extractFeedback(MemoryEpisode episode, JsonNode payload) {
        String action = upper(text(payload, "action"));
        if ("REACTION".equals(action)) {
            String reaction = upper(text(payload, "reaction"));
            if (!"LIKE".equals(reaction) && !"DISLIKE".equals(reaction)) {
                return List.of();
            }
            String title = firstText(payload, "recipeTitle", "title", "recipeName");
            if (title == null) {
                return List.of();
            }
            return List.of(draft(
                    "RECIPE_PREFERENCE",
                    title,
                    reaction,
                    FEEDBACK_STRENGTH,
                    FEEDBACK_CONFIDENCE,
                    "EXPLICIT_FEEDBACK",
                    "LONG_TERM",
                    ("LIKE".equals(reaction) ? "明确喜欢菜谱：" : "明确不喜欢菜谱：") + title,
                    true,
                    evidence(episode, reaction, title)
            ));
        }
        if ("COOKED".equals(action)) {
            String title = firstText(payload, "recipeTitle", "title", "recipeName");
            if (title == null) {
                return List.of();
            }
            return List.of(draft(
                    "RECIPE_BEHAVIOR",
                    title,
                    "COOKED",
                    COOKED_STRENGTH,
                    COOKED_CONFIDENCE,
                    "IMPLICIT_BEHAVIOR",
                    "RECENT",
                    "实际烹饪菜谱：" + title,
                    false,
                    evidence(episode, "COOKED", title)
            ));
        }
        return List.of();
    }

    private List<MemoryCandidateDraft> extractFinishedDishReview(
            MemoryEpisode episode,
            JsonNode payload
    ) {
        String title = firstText(payload, "recipeTitle", "title", "recipeName");
        BigDecimal score = decimal(payload.path("overallScore"));
        if (title == null || score == null) {
            return List.of();
        }
        String preference = score.compareTo(new BigDecimal("4.0")) >= 0
                ? "LIKE"
                : score.compareTo(new BigDecimal("2.0")) <= 0 ? "DISLIKE" : null;
        if (preference == null) {
            return List.of();
        }
        return List.of(draft(
                "RECIPE_PREFERENCE",
                title,
                preference,
                FEEDBACK_STRENGTH,
                FEEDBACK_CONFIDENCE,
                "EXPLICIT_FEEDBACK",
                "LONG_TERM",
                "成品评价 " + score.stripTrailingZeros().toPlainString() + " 星：" + title,
                true,
                evidence(episode, "RATING_" + preference, title)
        ));
    }

    private List<MemoryCandidateDraft> extractExplicitPreference(
            MemoryEpisode episode,
            JsonNode payload
    ) {
        String entity = firstText(payload, "entity", "ingredient", "canonicalName", "entityName");
        String preference = upper(firstText(payload, "preference", "polarity", "value"));
        if (entity == null || preference == null) {
            return List.of();
        }
        String candidateType = upper(firstText(payload, "candidateType", "type"));
        if (candidateType == null) {
            candidateType = "INGREDIENT_PREFERENCE";
        }
        return List.of(draft(
                candidateType,
                entity,
                preference,
                new BigDecimal("0.9500"),
                new BigDecimal("0.9500"),
                "EXPLICIT",
                "LONG_TERM",
                episode.getSummary() == null ? "用户明确表达偏好：" + entity : episode.getSummary(),
                true,
                evidence(episode, "EXPLICIT", entity)
        ));
    }

    private MemoryCandidateDraft draft(
            String candidateType,
            String entity,
            String preference,
            BigDecimal strength,
            BigDecimal confidence,
            String sourceType,
            String temporalType,
            String evidenceText,
            boolean explicitConfirmed,
            Map<String, Object> evidence
    ) {
        return new MemoryCandidateDraft(
                upper(candidateType),
                limit(entity, 255),
                upper(preference),
                strength,
                confidence,
                upper(sourceType),
                "USER",
                upper(temporalType),
                limit(evidenceText, MAX_TEXT_LENGTH),
                explicitConfirmed,
                evidence
        );
    }

    private Map<String, Object> evidence(MemoryEpisode episode, String action, String entity) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("episodeType", episode.getEpisodeType());
        values.put("sourceType", episode.getSourceType());
        values.put("sourceId", episode.getSourceId());
        values.put("eventId", episode.getEventId());
        values.put("action", action);
        values.put("entity", entity);
        return values;
    }

    private List<String> texts(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        node.forEach(item -> {
            String value = limitToText(item.asText());
            if (StringUtils.hasText(value) && !values.contains(value)) {
                values.add(value);
            }
        });
        return List.copyOf(values);
    }

    private String firstText(JsonNode node, String... names) {
        for (String name : names) {
            String value = text(node, name);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String text(JsonNode node, String name) {
        if (node == null || !node.hasNonNull(name)) {
            return null;
        }
        return limitToText(node.get(name).asText());
    }

    private BigDecimal decimal(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        try {
            return new BigDecimal(node.asText().trim());
        } catch (Exception ignored) {
            return null;
        }
    }

    private String upper(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private String limitToText(String value) {
        return limit(value, 255);
    }

    private String limit(String value, int maxLength) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String normalized = value.trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }
}
