package com.example.food.memory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** Retrieves personal memory items and episodes only; never calls the knowledge/recipe RAG. */
@Service
public class MemoryRetriever {
    private final MemoryItemMapper itemMapper;
    private final MemoryEpisodeMapper episodeMapper;
    private final MemoryQueryPlanner planner;
    private final MemoryReranker reranker;
    private final MemoryRankingProperties properties;
    private final MemorySessionService sessionService;
    private final Clock clock;

    @Autowired
    public MemoryRetriever(MemoryItemMapper itemMapper, MemoryEpisodeMapper episodeMapper,
                           MemoryQueryPlanner planner, MemoryReranker reranker,
                           MemoryRankingProperties properties, MemorySessionService sessionService) {
        this(itemMapper, episodeMapper, planner, reranker, properties, sessionService, Clock.systemDefaultZone());
    }

    MemoryRetriever(MemoryItemMapper itemMapper, MemoryEpisodeMapper episodeMapper,
                    MemoryQueryPlanner planner, MemoryReranker reranker,
                    MemoryRankingProperties properties, MemorySessionService sessionService, Clock clock) {
        this.itemMapper = itemMapper;
        this.episodeMapper = episodeMapper;
        this.planner = planner;
        this.reranker = reranker;
        this.properties = properties;
        this.sessionService = sessionService;
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

        List<MemorySearchHit> candidates = new ArrayList<>(items.size() + episodes.size());
        for (MemoryItem item : items) {
            String content = String.join(" ", nonEmpty(item.getCanonicalEntity(), item.getPreference(),
                    item.getMemoryType(), item.getCanonicalCategory(), item.getScope(), item.getTemporalType()));
            candidates.add(new MemorySearchHit("MEMORY_ITEM", item.getId(), item.getMemoryType(),
                    item.getCanonicalEntity(), content, null, item.getPreference(), item.getTemporalType(),
                    item.getConfidence(), item.getImportance(), item.getLastSeenAt(), null));
        }
        for (MemoryEpisode episode : episodes) {
            String content = String.join(" ", nonEmpty(episode.getEpisodeType(), episode.getSummary(),
                    episode.getSourceType(), episode.getSourceId()));
            candidates.add(new MemorySearchHit("EPISODE", episode.getId(), episode.getEpisodeType(),
                    episode.getSummary(), content, episode.getPayloadJson(), null, "TEMPORARY_CONTEXT",
                    java.math.BigDecimal.valueOf(0.5), episode.getImportance(), episode.getOccurredAt(), null));
        }

        List<MemorySearchHit> ranked = reranker.rerank(plan, candidates);
        List<MemorySearchHit> selected = ranked.stream().limit(plan.limit()).toList();
        MemoryRetrievalResult.RetrievalTrace trace = new MemoryRetrievalResult.RetrievalTrace(
                userId,
                plan.sessionId(),
                LocalDateTime.now(clock),
                items.size(),
                episodes.size(),
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
}
