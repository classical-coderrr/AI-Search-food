package com.example.food.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Learns cautious, explainable ingredient preferences from repeated recipe behavior.
 * It uses only user-owned Episodes and the maintained canonical ingredient taxonomy.
 */
@Service
public class MemoryBehaviorPatternExtractor {

    private static final int HISTORY_LIMIT = 100;
    private static final int WINDOW_DAYS = 90;
    private static final int MIN_SUPPORT_EVENTS = 3;
    private static final int MIN_DISTINCT_RECIPES = 2;
    private static final int MAX_PATTERNS_PER_RUN = 10;
    private static final int MAX_EVIDENCE_EPISODES_PER_PATTERN = 10;
    private static final BigDecimal MIN_PATTERN_SCORE = new BigDecimal("1.5000");
    private static final BigDecimal MAX_CONFIDENCE = new BigDecimal("0.7000");

    private final MemoryEpisodeService episodeService;
    private final TagNormalizationService tagNormalizationService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public MemoryBehaviorPatternExtractor(
            MemoryEpisodeService episodeService,
            TagNormalizationService tagNormalizationService,
            ObjectMapper objectMapper
    ) {
        this(episodeService, tagNormalizationService, objectMapper, Clock.systemDefaultZone());
    }

    MemoryBehaviorPatternExtractor(
            MemoryEpisodeService episodeService,
            TagNormalizationService tagNormalizationService,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.episodeService = episodeService;
        this.tagNormalizationService = tagNormalizationService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public List<CandidateSupport> extract(Long userId, MemoryEpisode currentEpisode) {
        if (userId == null || userId <= 0 || currentEpisode == null
                || !userId.equals(currentEpisode.getUserId())) {
            return List.of();
        }
        return extractFromHistory(currentEpisode, episodeService.listOwnedRecipeHistory(userId, HISTORY_LIMIT));
    }

    /** Pure entry point used by the fixed offline evaluation suite. */
    public List<CandidateSupport> extractFromHistory(
            MemoryEpisode currentEpisode,
            List<MemoryEpisode> history
    ) {
        if (currentEpisode == null || !isPositiveBehavior(currentEpisode)) {
            return List.of();
        }
        LocalDateTime cutoff = LocalDateTime.now(clock).minusDays(WINDOW_DAYS);
        Map<Long, MemoryEpisode> episodesById = new LinkedHashMap<>();
        if (history != null) {
            history.stream().filter(episode -> belongsToSameUser(currentEpisode, episode))
                    .filter(episode -> episode.getId() != null && episode.getOccurredAt() != null
                            && !episode.getOccurredAt().isBefore(cutoff)
                            && !"REJECTED".equalsIgnoreCase(episode.getStatus()))
                    .sorted(Comparator.comparing(MemoryEpisode::getOccurredAt)
                            .thenComparing(MemoryEpisode::getId))
                    .forEach(episode -> episodesById.put(episode.getId(), episode));
        }
        if (currentEpisode.getId() != null && currentEpisode.getOccurredAt() != null
                && !currentEpisode.getOccurredAt().isBefore(cutoff)) {
            episodesById.put(currentEpisode.getId(), currentEpisode);
        }

        List<MemoryEpisode> recent = new ArrayList<>(episodesById.values());
        if (recent.isEmpty()) return List.of();

        Map<String, MemoryEpisode> latestUnsavedEpisodes = latestUnsavedEpisodes(recent);
        Map<String, Long> latestReactionIds = latestReactionIds(recent);
        Map<String, TagNormalizationResult> normalizationCache = new HashMap<>();
        Map<String, List<IngredientSupport>> supportsByCanonicalGroup = new LinkedHashMap<>();

        for (MemoryEpisode episode : recent) {
            BehaviorSignal signal = behaviorSignal(episode, latestUnsavedEpisodes, latestReactionIds);
            if (signal == null) continue;
            Map<String, IngredientObservation> uniqueIngredients = new LinkedHashMap<>();
            for (String ingredient : ingredients(episode)) {
                String cacheKey = tagNormalizationService.normalizeText(ingredient);
                TagNormalizationResult normalized = normalizationCache.computeIfAbsent(cacheKey,
                        ignored -> tagNormalizationService.normalize("INGREDIENT_PREFERENCE", ingredient));
                if (normalized == null || !normalized.mapped()
                        || !"INGREDIENT".equalsIgnoreCase(normalized.category())) {
                    continue;
                }
                String groupId = firstText(normalized.canonicalGroupId(), normalized.canonicalId());
                if (!StringUtils.hasText(groupId) || !StringUtils.hasText(normalized.canonicalName())) continue;
                uniqueIngredients.putIfAbsent(groupId.toUpperCase(Locale.ROOT),
                        new IngredientObservation(normalized.canonicalName(), ingredient));
            }
            for (Map.Entry<String, IngredientObservation> ingredient : uniqueIngredients.entrySet()) {
                supportsByCanonicalGroup.computeIfAbsent(ingredient.getKey(), ignored -> new ArrayList<>())
                        .add(new IngredientSupport(episode, signal, ingredient.getValue()));
            }
        }

        List<PatternSupport> qualified = supportsByCanonicalGroup.entrySet().stream()
                .map(entry -> qualifiedPattern(entry.getKey(), entry.getValue()))
                .filter(pattern -> pattern != null)
                .sorted(Comparator.comparing(PatternSupport::score).reversed()
                        .thenComparing(PatternSupport::latestObservedAt, Comparator.reverseOrder())
                        .thenComparing(PatternSupport::canonicalGroupId))
                .limit(MAX_PATTERNS_PER_RUN)
                .toList();

        List<CandidateSupport> result = new ArrayList<>();
        for (PatternSupport pattern : qualified) {
            List<IngredientSupport> evidence = pattern.supports().stream()
                    .sorted(Comparator.comparing((IngredientSupport support) -> support.episode().getOccurredAt())
                            .reversed().thenComparing(support -> support.episode().getId(), Comparator.reverseOrder()))
                    .limit(MAX_EVIDENCE_EPISODES_PER_PATTERN)
                    .toList();
            for (IngredientSupport support : evidence) {
                result.add(new CandidateSupport(support.episode(), draft(pattern, support)));
            }
        }
        return List.copyOf(result);
    }

    private PatternSupport qualifiedPattern(String canonicalGroupId, List<IngredientSupport> supports) {
        Set<String> distinctEpisodes = new LinkedHashSet<>();
        Map<String, List<IngredientSupport>> byRecipe = new LinkedHashMap<>();
        for (IngredientSupport support : supports) {
            distinctEpisodes.add(String.valueOf(support.episode().getId()));
            byRecipe.computeIfAbsent(support.signal().recipeKey(), ignored -> new ArrayList<>()).add(support);
        }
        if (distinctEpisodes.size() < MIN_SUPPORT_EVENTS || byRecipe.size() < MIN_DISTINCT_RECIPES) return null;

        BigDecimal score = BigDecimal.ZERO;
        for (List<IngredientSupport> recipeSupports : byRecipe.values()) {
            List<BigDecimal> weights = recipeSupports.stream()
                    .map(support -> support.signal().weight())
                    .sorted(Comparator.reverseOrder()).toList();
            score = score.add(weights.get(0));
            if (weights.size() > 1) {
                BigDecimal additional = weights.stream().skip(1).reduce(BigDecimal.ZERO, BigDecimal::add)
                        .multiply(new BigDecimal("0.2500"));
                score = score.add(additional.min(new BigDecimal("0.2500")));
            }
        }
        if (score.compareTo(MIN_PATTERN_SCORE) < 0) return null;

        int eventCount = distinctEpisodes.size();
        int recipeCount = byRecipe.size();
        BigDecimal confidence = new BigDecimal("0.4500")
                .add(BigDecimal.valueOf(Math.min(5, eventCount - MIN_SUPPORT_EVENTS))
                        .multiply(new BigDecimal("0.0300")))
                .add(BigDecimal.valueOf(Math.min(3, recipeCount - MIN_DISTINCT_RECIPES))
                        .multiply(new BigDecimal("0.0600")))
                .min(MAX_CONFIDENCE);
        BigDecimal strength = new BigDecimal("0.5500")
                .add(score.subtract(MIN_PATTERN_SCORE).max(BigDecimal.ZERO)
                        .multiply(new BigDecimal("0.1000")))
                .min(new BigDecimal("0.7500"));
        IngredientSupport latest = supports.stream()
                .max(Comparator.comparing(support -> support.episode().getOccurredAt()))
                .orElseThrow();
        return new PatternSupport(canonicalGroupId, supports, score, confidence, strength,
                eventCount, recipeCount, latest.episode().getOccurredAt());
    }

    private MemoryCandidateDraft draft(PatternSupport pattern, IngredientSupport support) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("episodeType", support.episode().getEpisodeType());
        evidence.put("sourceType", support.episode().getSourceType());
        evidence.put("sourceId", support.episode().getSourceId());
        evidence.put("eventId", support.episode().getEventId());
        evidence.put("action", support.signal().action());
        evidence.put("recipeTitle", support.signal().recipeTitle());
        evidence.put("matchedIngredient", support.ingredient().rawName());
        evidence.put("canonicalGroupId", pattern.canonicalGroupId());
        evidence.put("patternWindowDays", WINDOW_DAYS);
        evidence.put("supportEpisodeCount", pattern.eventCount());
        evidence.put("distinctRecipeCount", pattern.recipeCount());
        return new MemoryCandidateDraft(
                "INGREDIENT_PREFERENCE",
                support.ingredient().canonicalName(),
                "LIKE",
                pattern.strength(),
                pattern.confidence(),
                "IMPLICIT_BEHAVIOR",
                "USER",
                "RECENT",
                "近" + WINDOW_DAYS + "天内有" + pattern.eventCount() + "条正向行为涉及"
                        + support.ingredient().canonicalName() + "，覆盖" + pattern.recipeCount()
                        + "道菜；本条证据为" + support.signal().label() + "《"
                        + support.signal().recipeTitle() + "》",
                false,
                evidence
        );
    }

    private BehaviorSignal behaviorSignal(
            MemoryEpisode episode,
            Map<String, MemoryEpisode> latestUnsavedEpisodes,
            Map<String, Long> latestReactionIds
    ) {
        JsonNode payload = payload(episode);
        if (payload == null) return null;
        String type = upper(episode.getEpisodeType());
        String action = upper(text(payload, "action"));
        BigDecimal weight;
        String evidenceAction;
        String label;
        if ("RECIPE_SAVED".equals(type)) {
            String recipeId = firstText(text(payload, "recipeId"), episode.getSourceId());
            MemoryEpisode latestUnsaved = recipeId == null ? null : latestUnsavedEpisodes.get(recipeId);
            if (latestUnsaved != null && !occursAfter(episode, latestUnsaved)) return null;
            weight = new BigDecimal("0.5500");
            evidenceAction = "SAVE";
            label = "收藏菜谱";
        } else if ("RECIPE_FEEDBACK".equals(type) && "COOKED".equals(action)) {
            weight = new BigDecimal("0.6500");
            evidenceAction = "COOKED";
            label = "实际烹饪";
        } else if ("RECIPE_FEEDBACK".equals(type) && "REACTION".equals(action)
                && "LIKE".equals(upper(text(payload, "reaction")))) {
            String reactionKey = reactionKey(episode, payload);
            if (reactionKey == null || !episode.getId().equals(latestReactionIds.get(reactionKey))) return null;
            weight = new BigDecimal("0.8500");
            evidenceAction = "LIKE";
            label = "明确喜欢菜谱";
        } else if ("FINISHED_DISH_REVIEW".equals(type)) {
            BigDecimal rating = decimal(payload.path("overallScore"));
            if (rating == null || rating.compareTo(new BigDecimal("80")) < 0
                    || rating.compareTo(new BigDecimal("100")) > 0) return null;
            weight = new BigDecimal("0.9000");
            evidenceAction = "HIGH_RATING";
            label = "成品高分评价";
        } else {
            return null;
        }

        List<String> ingredients = ingredients(payload);
        String title = firstText(text(payload, "recipeTitle"), text(payload, "title"),
                text(payload, "recipeName"), text(payload, "resultTitle"));
        if (ingredients.isEmpty() || title == null) return null;
        String recipeKey = normalizeRecipeKey(title);
        if (!StringUtils.hasText(recipeKey)) {
            recipeKey = firstText(text(payload, "searchLogId"), text(payload, "recipeId"), episode.getSourceId());
        }
        if (!StringUtils.hasText(recipeKey)) return null;
        return new BehaviorSignal(evidenceAction, label, title, recipeKey, weight);
    }

    private boolean isPositiveBehavior(MemoryEpisode episode) {
        if (episode == null) return false;
        JsonNode payload = payload(episode);
        if (payload == null || ingredients(payload).isEmpty()) return false;
        String type = upper(episode.getEpisodeType());
        String action = upper(text(payload, "action"));
        if ("RECIPE_SAVED".equals(type)) return true;
        if ("RECIPE_FEEDBACK".equals(type)) {
            return "COOKED".equals(action) || "REACTION".equals(action)
                    && "LIKE".equals(upper(text(payload, "reaction")));
        }
        if ("FINISHED_DISH_REVIEW".equals(type)) {
            BigDecimal score = decimal(payload.path("overallScore"));
            return score != null && score.compareTo(new BigDecimal("80")) >= 0
                    && score.compareTo(new BigDecimal("100")) <= 0;
        }
        return false;
    }

    private Map<String, MemoryEpisode> latestUnsavedEpisodes(List<MemoryEpisode> episodes) {
        Map<String, MemoryEpisode> latest = new HashMap<>();
        for (MemoryEpisode episode : episodes) {
            if (!"RECIPE_UNSAVED".equalsIgnoreCase(episode.getEpisodeType())) continue;
            JsonNode payload = payload(episode);
            if (payload == null) continue;
            String id = firstText(text(payload, "recipeId"), episode.getSourceId());
            if (id != null && episode.getOccurredAt() != null) {
                latest.merge(id, episode, (previous, current) -> occursAfter(current, previous) ? current : previous);
            }
        }
        return latest;
    }

    private boolean occursAfter(MemoryEpisode first, MemoryEpisode second) {
        if (first == null || second == null || first.getOccurredAt() == null || second.getOccurredAt() == null) {
            return false;
        }
        int timeOrder = first.getOccurredAt().compareTo(second.getOccurredAt());
        if (timeOrder != 0) return timeOrder > 0;
        return first.getId() != null && second.getId() != null && first.getId() > second.getId();
    }

    private Map<String, Long> latestReactionIds(List<MemoryEpisode> episodes) {
        List<MemoryEpisode> newestFirst = episodes.stream()
                .sorted(Comparator.comparing(MemoryEpisode::getOccurredAt).reversed()
                        .thenComparing(MemoryEpisode::getId, Comparator.reverseOrder()))
                .toList();
        Map<String, Long> latest = new LinkedHashMap<>();
        for (MemoryEpisode episode : newestFirst) {
            if (!"RECIPE_FEEDBACK".equalsIgnoreCase(episode.getEpisodeType())) continue;
            JsonNode payload = payload(episode);
            String action = upper(text(payload, "action"));
            if (!"REACTION".equals(action) && !"REACTION_CLEARED".equals(action)) continue;
            String key = reactionKey(episode, payload);
            if (key != null) latest.putIfAbsent(key, episode.getId());
        }
        return latest;
    }

    private String reactionKey(MemoryEpisode episode, JsonNode payload) {
        return firstText(episode.getSourceId(), text(payload, "feedbackId"), text(payload, "searchLogId"),
                normalizeRecipeKey(firstText(text(payload, "recipeTitle"), text(payload, "title"))));
    }

    private List<String> ingredients(MemoryEpisode episode) {
        return ingredients(payload(episode));
    }

    private List<String> ingredients(JsonNode payload) {
        JsonNode values = payload == null ? null : payload.path("ingredients");
        if (values == null || !values.isArray()) return List.of();
        Set<String> result = new LinkedHashSet<>();
        for (JsonNode value : values) {
            String name = value.isTextual() ? value.asText()
                    : firstText(text(value, "name"), text(value, "ingredientName"), text(value, "canonicalName"));
            if (StringUtils.hasText(name)) result.add(name.trim());
        }
        return List.copyOf(result);
    }

    private JsonNode payload(MemoryEpisode episode) {
        if (episode == null || !StringUtils.hasText(episode.getPayloadJson())) return null;
        try {
            JsonNode parsed = objectMapper.readTree(episode.getPayloadJson());
            return parsed != null && parsed.isObject() ? parsed : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private String normalizeRecipeKey(String title) {
        if (!StringUtils.hasText(title)) return null;
        return title.trim().toLowerCase(Locale.ROOT).replaceAll("[\\p{Punct}\\p{Zs}\\s]+", "");
    }

    private BigDecimal decimal(JsonNode node) {
        try {
            return node == null || node.isMissingNode() || node.isNull()
                    ? null : new BigDecimal(node.asText().trim());
        } catch (Exception ignored) {
            return null;
        }
    }

    private String text(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) return null;
        String value = node.get(field).asText();
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) return value.trim();
        }
        return null;
    }

    private String upper(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private boolean belongsToSameUser(MemoryEpisode current, MemoryEpisode candidate) {
        if (candidate == null) return false;
        Long currentUserId = current.getUserId();
        return currentUserId == null ? candidate.getUserId() == null
                : currentUserId.equals(candidate.getUserId());
    }

    public record CandidateSupport(MemoryEpisode episode, MemoryCandidateDraft draft) { }

    private record BehaviorSignal(String action, String label, String recipeTitle,
                                  String recipeKey, BigDecimal weight) { }
    private record IngredientObservation(String canonicalName, String rawName) { }
    private record IngredientSupport(MemoryEpisode episode, BehaviorSignal signal,
                                     IngredientObservation ingredient) { }
    private record PatternSupport(String canonicalGroupId, List<IngredientSupport> supports,
                                  BigDecimal score, BigDecimal confidence, BigDecimal strength,
                                  int eventCount, int recipeCount, LocalDateTime latestObservedAt) { }
}
