package com.example.food.memory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class MemoryReranker {
    private static final Pattern LATIN_OR_DIGIT = Pattern.compile("[a-z0-9]+(?:[._-][a-z0-9]+)*");
    private static final Pattern CJK = Pattern.compile("[\\p{IsHan}]+");
    private final MemoryRankingProperties properties;
    private final Clock clock;

    @Autowired
    public MemoryReranker(MemoryRankingProperties properties) {
        this(properties, Clock.systemDefaultZone());
    }

    MemoryReranker(MemoryRankingProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public List<MemorySearchHit> rerank(MemoryQueryPlan plan, List<MemorySearchHit> candidates) {
        List<MemorySearchHit> result = new ArrayList<>(candidates.size());
        for (MemorySearchHit candidate : candidates) {
            double semantic = semanticScore(plan.rewrittenQuery(), candidate);
            double recency = recencyScore(candidate);
            double importance = unit(candidate.importance());
            double confidence = unit(candidate.confidence());
            double feedback = feedbackScore(candidate, plan.originalQuery());
            double context = contextScore(plan, candidate);
            double total = weighted(semantic, recency, importance, confidence, feedback, context);
            result.add(new MemorySearchHit(candidate.sourceKind(), candidate.id(), candidate.memoryType(),
                    candidate.title(), candidate.content(), candidate.payload(), candidate.preference(),
                    candidate.temporalType(), candidate.confidence(), candidate.importance(), candidate.occurredAt(),
                    new MemorySearchHit.ScoreBreakdown(semantic, recency, importance, confidence,
                            feedback, context, total)));
        }
        result.sort(Comparator.comparingDouble((MemorySearchHit hit) -> hit.scores().total()).reversed()
                .thenComparing(hit -> hit.occurredAt() == null ? LocalDateTime.MIN : hit.occurredAt(),
                        Comparator.reverseOrder())
                .thenComparing(MemorySearchHit::id, Comparator.nullsLast(Comparator.reverseOrder())));
        return List.copyOf(result);
    }

    private double weighted(double semantic, double recency, double importance,
                            double confidence, double feedback, double context) {
        double[] weights = {properties.semanticWeight(), properties.recencyWeight(),
                properties.importanceWeight(), properties.confidenceWeight(),
                properties.feedbackWeight(), properties.contextWeight()};
        double[] values = {semantic, recency, importance, confidence, feedback, context};
        double sum = 0;
        double weightTotal = 0;
        for (int i = 0; i < weights.length; i++) {
            double weight = Math.max(0, weights[i]);
            sum += weight * values[i];
            weightTotal += weight;
        }
        return weightTotal == 0 ? 0 : clamp(sum / weightTotal);
    }

    private double semanticScore(String query, MemorySearchHit hit) {
        Set<String> queryTerms = terms(query);
        if (queryTerms.isEmpty()) return 0;
        Set<String> hitTerms = terms(String.join(" ", safe(hit.title()), safe(hit.content()), safe(hit.payload())));
        long matches = queryTerms.stream().filter(hitTerms::contains).count();
        return clamp((double) matches / queryTerms.size());
    }

    private double recencyScore(MemorySearchHit hit) {
        if (hit.occurredAt() == null) return 0;
        long days = Math.max(0, ChronoUnit.SECONDS.between(hit.occurredAt(), LocalDateTime.now(clock)) / 86400);
        double halfLife = halfLife(hit.temporalType(), hit.sourceKind());
        return clamp(Math.exp(-Math.log(2) * days / halfLife));
    }

    private double halfLife(String temporalType, String sourceKind) {
        String type = temporalType == null ? "" : temporalType.toUpperCase(Locale.ROOT);
        if (type.contains("EXPLICIT")) return positive(properties.explicitPreferenceHalfLifeDays(), 3650);
        if (type.contains("IMPLICIT")) return positive(properties.implicitPreferenceHalfLifeDays(), 180);
        if (type.contains("SHORT_TERM")) return positive(properties.shortTermTrendHalfLifeDays(), 30);
        if (type.contains("BEHAVIOR")) return positive(properties.behaviorPatternHalfLifeDays(), 365);
        if (type.contains("TEMPORARY") || "EPISODE".equals(sourceKind)) {
            return positive(properties.temporaryContextHalfLifeDays(), 7);
        }
        return positive(properties.implicitPreferenceHalfLifeDays(), 180);
    }

    private double feedbackScore(MemorySearchHit hit, String query) {
        String value = (safe(hit.preference()) + " " + safe(hit.content()) + " " + safe(hit.payload()))
                .toLowerCase(Locale.ROOT);
        String normalizedQuery = safe(query).toLowerCase(Locale.ROOT);
        boolean asksAvoid = containsAny(normalizedQuery, "不吃", "不喜欢", "避开", "不要", "avoid", "dislike");
        if (asksAvoid && containsAny(value, "dislike", "不喜欢", "不吃", "avoid")) return 1;
        if (containsAny(value, "\"rating\":5", "\"rating\": 5", "5星", "很好吃", "like")) return 0.95;
        if (containsAny(value, "\"rating\":1", "\"rating\": 2", "难吃", "不喜欢", "dislike")) return 0.10;
        return 0.50;
    }

    private double contextScore(MemoryQueryPlan plan, MemorySearchHit hit) {
        List<String> context = new ArrayList<>();
        context.addAll(plan.scenes());
        context.addAll(plan.mealTypes());
        context.addAll(plan.ingredients());
        context.addAll(plan.dietGoals());
        if (context.isEmpty()) return 0.50;
        String text = (safe(hit.title()) + " " + safe(hit.content()) + " " + safe(hit.payload()))
                .toLowerCase(Locale.ROOT);
        long matches = context.stream().filter(value -> text.contains(value.toLowerCase(Locale.ROOT))).count();
        return clamp((double) matches / context.size());
    }

    private Set<String> terms(String value) {
        if (!StringUtils.hasText(value)) return Set.of();
        String normalized = value.toLowerCase(Locale.ROOT);
        Set<String> result = new LinkedHashSet<>();
        Matcher latin = LATIN_OR_DIGIT.matcher(normalized);
        while (latin.find()) result.add(latin.group());
        Matcher cjk = CJK.matcher(normalized);
        while (cjk.find()) {
            String run = cjk.group();
            if (run.length() < 2) result.add(run);
            else {
                result.add(run);
                for (int i = 0; i < run.length() - 1; i++) result.add(run.substring(i, i + 2));
            }
        }
        return result;
    }

    private boolean containsAny(String value, String... terms) {
        for (String term : terms) if (value.contains(term)) return true;
        return false;
    }

    private double unit(BigDecimal value) {
        return value == null ? 0 : clamp(value.doubleValue());
    }

    private double clamp(double value) {
        return Math.max(0, Math.min(1, value));
    }

    private double positive(double value, double fallback) {
        return Double.isFinite(value) && value > 0 ? value : fallback;
    }

    private String safe(String value) { return value == null ? "" : value; }
}
