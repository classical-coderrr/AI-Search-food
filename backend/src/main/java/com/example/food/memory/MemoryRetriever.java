package com.example.food.memory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;

/** Retrieves personal memory items and episodes only; never calls the knowledge/recipe RAG. */
@Service
public class MemoryRetriever {
    private static final Logger log = LoggerFactory.getLogger(MemoryRetriever.class);
    private final MemoryItemMapper itemMapper;
    private final MemoryEpisodeMapper episodeMapper;
    private final MemoryQueryPlanner planner;
    private final MemoryReranker reranker;
    private final MemoryRankingProperties properties;
    private final MemoryEmbeddingProperties embeddingProperties;
    private final MemorySessionService sessionService;
    private final MemoryEmbeddingIndexService embeddingIndexService;
    private final MemoryTargetFeedbackService targetFeedbackService;
    private final Clock clock;

    @Autowired
    public MemoryRetriever(MemoryItemMapper itemMapper, MemoryEpisodeMapper episodeMapper,
                           MemoryQueryPlanner planner, MemoryReranker reranker,
                           MemoryRankingProperties properties, MemoryEmbeddingProperties embeddingProperties,
                           MemorySessionService sessionService,
                           MemoryEmbeddingIndexService embeddingIndexService,
                           MemoryTargetFeedbackService targetFeedbackService) {
        this(itemMapper, episodeMapper, planner, reranker, properties, embeddingProperties,
                sessionService, embeddingIndexService, targetFeedbackService, Clock.systemDefaultZone());
    }

    MemoryRetriever(MemoryItemMapper itemMapper, MemoryEpisodeMapper episodeMapper,
                    MemoryQueryPlanner planner, MemoryReranker reranker,
                    MemoryRankingProperties properties, MemoryEmbeddingProperties embeddingProperties,
                    MemorySessionService sessionService,
                    MemoryEmbeddingIndexService embeddingIndexService, Clock clock) {
        this(itemMapper, episodeMapper, planner, reranker, properties, embeddingProperties,
                sessionService, embeddingIndexService, null, clock);
    }

    MemoryRetriever(MemoryItemMapper itemMapper, MemoryEpisodeMapper episodeMapper,
                    MemoryQueryPlanner planner, MemoryReranker reranker,
                    MemoryRankingProperties properties, MemoryEmbeddingProperties embeddingProperties,
                    MemorySessionService sessionService,
                    MemoryEmbeddingIndexService embeddingIndexService,
                    MemoryTargetFeedbackService targetFeedbackService, Clock clock) {
        this.itemMapper = itemMapper;
        this.episodeMapper = episodeMapper;
        this.planner = planner;
        this.reranker = reranker;
        this.properties = properties;
        this.embeddingProperties = embeddingProperties;
        this.sessionService = sessionService;
        this.embeddingIndexService = embeddingIndexService;
        this.targetFeedbackService = targetFeedbackService;
        this.clock = clock;
    }

    public MemoryRetrievalResult search(Long userId, MemorySearchCommand command) {
        MemoryQueryPlan plan = planner.plan(userId, command);
        if (plan.sessionId() != null) {
            sessionService.findOwned(userId, plan.sessionId());
        }
        int candidateLimit = Math.max(plan.limit(), Math.min(1000,
                Math.max(1, properties.candidateLimit())));
        List<MemoryItem> items = itemMapper.searchActiveForRetrieval(
                userId, plan.memoryTypes(), plan.ingredients(), plan.dietGoals(),
                plan.timeFrom(), plan.timeTo(), plan.minConfidence(), plan.minImportance(), candidateLimit);
        List<MemoryEpisode> episodes = episodeMapper.searchOwnedForRetrieval(
                userId, plan.episodeTypes(), plan.scenes(), plan.mealTypes(), plan.ingredients(),
                plan.dietGoals(), plan.timeFrom(), plan.timeTo(), plan.minImportance(), candidateLimit);

        Map<MemoryVectorKey, MemorySearchHit> candidates = new LinkedHashMap<>();
        for (MemoryItem item : items) {
            MemorySearchHit hit = toItemHit(item);
            candidates.put(new MemoryVectorKey(hit.sourceKind(), hit.id()), hit);
        }
        for (MemoryEpisode episode : episodes) {
            MemorySearchHit hit = toEpisodeHit(episode);
            candidates.put(new MemoryVectorKey(hit.sourceKind(), hit.id()), hit);
        }

        Map<MemoryVectorKey, Double> vectorScores = new LinkedHashMap<>();
        try {
            int vectorLimit = Math.max(1, Math.min(embeddingProperties.scanLimit(),
                    Math.max(embeddingProperties.recallLimit(), plan.limit())));
            List<MemoryVectorMatch> vectorMatches = embeddingIndexService.search(userId,
                    plan.rewrittenQuery(), vectorLimit);
            Map<String, List<Long>> idsByKind = new LinkedHashMap<>();
            for (MemoryVectorMatch match : vectorMatches) {
                MemoryVectorKey key = new MemoryVectorKey(match.sourceKind(), match.sourceId());
                vectorScores.put(key, match.similarity());
                if (!candidates.containsKey(key) && match.sourceId() != null) {
                    idsByKind.computeIfAbsent(match.sourceKind(), ignored -> new ArrayList<>()).add(match.sourceId());
                }
            }
            List<Long> itemIds = idsByKind.getOrDefault("MEMORY_ITEM", List.of()).stream().distinct().toList();
            for (MemoryItem item : itemIds.isEmpty() ? List.<MemoryItem>of()
                    : itemMapper.findActiveOwnedByIds(userId, itemIds)) {
                if (matches(item, plan)) {
                    MemorySearchHit hit = toItemHit(item);
                    candidates.put(new MemoryVectorKey(hit.sourceKind(), hit.id()), hit);
                }
            }
            List<Long> episodeIds = idsByKind.getOrDefault("EPISODE", List.of()).stream().distinct().toList();
            for (MemoryEpisode episode : episodeIds.isEmpty() ? List.<MemoryEpisode>of()
                    : episodeMapper.findOwnedByIds(userId, episodeIds)) {
                if (matches(episode, plan)) {
                    MemorySearchHit hit = toEpisodeHit(episode);
                    candidates.put(new MemoryVectorKey(hit.sourceKind(), hit.id()), hit);
                }
            }
        } catch (RuntimeException vectorFailure) {
            log.warn("Memory vector recall failed; continuing with SQL and lexical recall ({})",
                    vectorFailure.getClass().getSimpleName());
            vectorScores.clear();
        }

        List<MemorySearchHit> candidateList = new ArrayList<>(candidates.values());
        Map<MemoryVectorKey, MemoryFeedbackType> userFeedback = targetFeedbackService == null
                ? Map.of()
                : targetFeedbackService.recentSignals(userId, plan.intent(), candidateList,
                        LocalDateTime.now(clock).minusDays(Math.max(1, properties.userFeedbackMaxAgeDays())));
        List<MemorySearchHit> ranked = reranker.rerank(plan, candidateList, vectorScores, userFeedback);
        List<MemorySearchHit> selected = ranked.stream().limit(plan.limit()).toList();
        MemoryRetrievalResult.RetrievalTrace trace = new MemoryRetrievalResult.RetrievalTrace(
                userId,
                plan.sessionId(),
                LocalDateTime.now(clock),
                Math.toIntExact(candidates.values().stream().filter(hit -> "MEMORY_ITEM".equals(hit.sourceKind())).count()),
                Math.toIntExact(candidates.values().stream().filter(hit -> "EPISODE".equals(hit.sourceKind())).count()),
                selected.stream().filter(hit -> "MEMORY_ITEM".equals(hit.sourceKind()))
                        .map(MemorySearchHit::id).toList(),
                selected.stream().filter(hit -> "EPISODE".equals(hit.sourceKind()))
                        .map(MemorySearchHit::id).toList()
        );
        return new MemoryRetrievalResult(plan, selected, trace);
    }

    private List<String> nonEmpty(String... values) {
        List<String> result = new ArrayList<>();
        for (String value : values) if (StringUtils.hasText(value)) result.add(value.trim());
        return result;
    }

    private MemorySearchHit toItemHit(MemoryItem item) {
        String content = String.join(" ", nonEmpty(item.getCanonicalEntity(), item.getPreference(),
                item.getMemoryType(), item.getCanonicalCategory(), item.getScope(), item.getTemporalType()));
        return new MemorySearchHit("MEMORY_ITEM", item.getId(), item.getVersion(), item.getMemoryType(),
                item.getCanonicalEntity(), content, null, item.getPreference(), item.getTemporalType(),
                item.getConfidence(), item.getImportance(), item.getLastSeenAt(), null);
    }

    private MemorySearchHit toEpisodeHit(MemoryEpisode episode) {
        String content = String.join(" ", nonEmpty(episode.getEpisodeType(), episode.getSummary(),
                episode.getSourceType(), episode.getSourceId()));
        return new MemorySearchHit("EPISODE", episode.getId(), episode.getEpisodeType(),
                episode.getSummary(), content, episode.getPayloadJson(), null, "TEMPORARY_CONTEXT",
                java.math.BigDecimal.valueOf(0.5), episode.getImportance(), episode.getOccurredAt(), null);
    }

    private boolean matches(MemoryItem item, MemoryQueryPlan plan) {
        if (!matchesExact(plan.memoryTypes(), item.getMemoryType())) return false;
        if (!matchesAny(plan.ingredients(), item.getCanonicalEntity(), item.getCanonicalId())) return false;
        if (!matchesAny(plan.dietGoals(), item.getCanonicalEntity(), item.getCanonicalId())) return false;
        if (plan.timeFrom() != null && (item.getLastSeenAt() == null || item.getLastSeenAt().isBefore(plan.timeFrom()))) return false;
        if (plan.timeTo() != null && (item.getLastSeenAt() == null || item.getLastSeenAt().isAfter(plan.timeTo()))) return false;
        if (plan.minConfidence() != null && (item.getConfidence() == null
                || item.getConfidence().compareTo(plan.minConfidence()) < 0)) return false;
        return plan.minImportance() == null || item.getImportance() != null
                && item.getImportance().compareTo(plan.minImportance()) >= 0;
    }

    private boolean matches(MemoryEpisode episode, MemoryQueryPlan plan) {
        if (!matchesExact(plan.episodeTypes(), episode.getEpisodeType())) return false;
        if (plan.timeFrom() != null && (episode.getOccurredAt() == null || episode.getOccurredAt().isBefore(plan.timeFrom()))) return false;
        if (plan.timeTo() != null && (episode.getOccurredAt() == null || episode.getOccurredAt().isAfter(plan.timeTo()))) return false;
        if (plan.minImportance() != null && (episode.getImportance() == null
                || episode.getImportance().compareTo(plan.minImportance()) < 0)) return false;
        return matchesPayload(plan.scenes(), episode.getPayloadJson())
                && matchesPayload(plan.mealTypes(), episode.getPayloadJson())
                && matchesPayload(plan.ingredients(), episode.getPayloadJson())
                && matchesPayload(plan.dietGoals(), episode.getPayloadJson());
    }

    private boolean matchesExact(List<String> filters, String actual) {
        return filters == null || filters.isEmpty() || filters.stream().anyMatch(value ->
                value != null && actual != null && value.equalsIgnoreCase(actual));
    }

    private boolean matchesAny(List<String> filters, String... actualValues) {
        if (filters == null || filters.isEmpty()) return true;
        for (String filter : filters) {
            if (filter == null) continue;
            for (String actual : actualValues) {
                if (actual != null && actual.toLowerCase(Locale.ROOT).contains(filter.toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean matchesPayload(List<String> filters, String payload) {
        if (filters == null || filters.isEmpty()) return true;
        String normalized = payload == null ? "" : payload.toLowerCase(Locale.ROOT);
        return filters.stream().anyMatch(value -> value != null && normalized.contains(value.toLowerCase(Locale.ROOT)));
    }
}
