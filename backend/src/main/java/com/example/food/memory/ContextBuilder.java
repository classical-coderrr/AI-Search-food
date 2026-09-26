package com.example.food.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.math.BigDecimal;

/** Builds task-scoped context while keeping personal memory and external knowledge separate. */
@Service
public class ContextBuilder {
    private final MemoryConsolidationService consolidationService;
    private final MemorySessionService sessionService;
    private final MemoryRankingProperties properties;
    private final ContextBudgetManager budgetManager;
    private final ObjectMapper objectMapper;
    private final PersonalizedSkillService personalizedSkillService;
    private final MemoryConflictResolver conflictResolver;
    private final MemoryEpisodeService episodeService;

    @Autowired
    public ContextBuilder(MemoryConsolidationService consolidationService,
                          MemorySessionService sessionService,
                          MemoryRankingProperties properties,
                          ContextBudgetManager budgetManager,
                          ObjectMapper objectMapper,
                          PersonalizedSkillService personalizedSkillService,
                          MemoryConflictResolver conflictResolver,
                          MemoryEpisodeService episodeService) {
        this.consolidationService = consolidationService;
        this.sessionService = sessionService;
        this.properties = properties;
        this.budgetManager = budgetManager;
        this.objectMapper = objectMapper;
        this.personalizedSkillService = personalizedSkillService;
        this.conflictResolver = conflictResolver;
        this.episodeService = episodeService;
    }

    public ContextBuilder(MemoryConsolidationService consolidationService,
                          MemorySessionService sessionService,
                          MemoryRankingProperties properties,
                          ContextBudgetManager budgetManager,
                          ObjectMapper objectMapper,
                          PersonalizedSkillService personalizedSkillService,
                          MemoryConflictResolver conflictResolver) {
        this(consolidationService, sessionService, properties, budgetManager, objectMapper,
                personalizedSkillService, conflictResolver, null);
    }

    public ContextBuilder(MemoryConsolidationService consolidationService,
                          MemorySessionService sessionService,
                          MemoryRankingProperties properties,
                          ContextBudgetManager budgetManager,
                          ObjectMapper objectMapper,
                          PersonalizedSkillService personalizedSkillService) {
        this(consolidationService, sessionService, properties, budgetManager, objectMapper,
                personalizedSkillService, new MemoryConflictResolver(objectMapper));
    }

    public ContextBuilder(MemoryConsolidationService consolidationService,
                          MemorySessionService sessionService,
                          MemoryRankingProperties properties,
                          ContextBudgetManager budgetManager,
                          ObjectMapper objectMapper) {
        this(consolidationService, sessionService, properties, budgetManager, objectMapper,
                new PersonalizedSkillService(objectMapper));
    }

    public ContextBuildResult build(Long userId, Long sessionId,
                                    MemoryRetrievalResult retrieval,
                                    List<ExternalContext> knowledge,
                                    List<ExternalContext> toolResults,
                                    Integer requestedTokenBudget) {
        if (userId == null || userId <= 0) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.UNAUTHORIZED, "用户身份无效");
        }
        int tokenBudget = requestedTokenBudget == null || requestedTokenBudget < 1
                ? properties.defaultTokenBudget() : requestedTokenBudget;
        List<ContextBudgetManager.ContextEntry> entries = new ArrayList<>();
        int order = 0;

        MemorySession session = sessionId == null ? null : sessionService.findOwned(userId, sessionId);
        if (session != null) {
            String text = join("当前任务：" + safe(session.getCurrentTask()),
                    "当前目标：" + safe(session.getCurrentGoal()),
                    "当前会话约束：" + safe(session.getContextJson()));
            entries.add(new ContextBudgetManager.ContextEntry("SESSION", "SESSION", session.getId(),
                    text, 100, order++));
        }

        if (toolResults != null) {
            for (ExternalContext value : toolResults) {
                entries.add(new ContextBudgetManager.ContextEntry("TOOL_RESULTS", value.sourceKind(),
                        value.id(), value.text(), 95, order++));
            }
        }

        if (retrieval != null) {
            for (MemorySearchHit hit : chronologicalEpisodeOrder(retrieval.hits())) {
                String occurredAt = "EPISODE".equals(hit.sourceKind()) && hit.occurredAt() != null
                        ? "事件发生时间：" + hit.occurredAt() : null;
                String text = join(occurredAt, hit.title(), hit.content(), hit.payload());
                entries.add(new ContextBudgetManager.ContextEntry("PERSONAL_MEMORY", hit.sourceKind(),
                        hit.id(), text, 90, order++));
            }
        }

        MemoryProfile profile = consolidationService.getOwnedProfile(userId);
        String query = retrieval == null || retrieval.queryPlan() == null
                ? null : retrieval.queryPlan().originalQuery();
        PersonalizedSkillService.SkillContext skill = personalizedSkillService.resolve(
                query, profile == null ? null : profile.getProfileJson());
        if (skill != null) {
            entries.add(new ContextBudgetManager.ContextEntry("PERSONALIZED_SKILL",
                    "SKILL:" + skill.skillName(), null, skill.promptContext(), 88, order++));
        }
        if (profile != null && StringUtils.hasText(profile.getProfileJson())) {
            String relevantProfile = relevantProfile(profile.getProfileJson(),
                    retrieval == null ? null : retrieval.queryPlan());
            if (StringUtils.hasText(relevantProfile)) {
                MemoryQueryPlan plan = retrieval == null ? null : retrieval.queryPlan();
                if (conflictResolver.hasPreferenceConflicts(relevantProfile)) {
                    Set<Long> conflictingItemIds = conflictResolver.conflictingMemoryItemIds(relevantProfile);
                    List<MemoryItem> conflictingItems = consolidationService.listOwnedItems(userId, 500).stream()
                            .filter(item -> item.getId() != null && conflictingItemIds.contains(item.getId()))
                            .toList();
                    List<MemoryCandidate> sourceCandidates = consolidationService
                            .listOwnedSourceCandidates(userId, conflictingItems);
                    List<MemorySearchHit> conflictEpisodes = conflictEpisodes(userId, conflictingItems,
                            retrieval == null ? List.of() : retrieval.hits());
                    List<String> explanations = conflictResolver.explain(relevantProfile, plan,
                            conflictEpisodes, conflictingItems, sourceCandidates);
                    for (String explanation : explanations) {
                        entries.add(new ContextBudgetManager.ContextEntry("MEMORY_CONFLICTS",
                                "MEMORY_CONFLICTS", userId, explanation, 94, order++));
                    }
                }
                entries.add(new ContextBudgetManager.ContextEntry("STRUCTURED_PROFILE", "PROFILE", userId,
                        relevantProfile, 80, order++));
            }
        }

        if (knowledge != null) {
            for (ExternalContext value : knowledge) {
                entries.add(new ContextBudgetManager.ContextEntry("KNOWLEDGE_RAG", value.sourceKind(),
                        value.id(), value.text(), 70, order++));
            }
        }

        ContextBudgetManager.BudgetResult allocation = budgetManager.allocate(entries, tokenBudget);
        Map<String, List<String>> sections = new LinkedHashMap<>();
        List<Long> usedMemoryItemIds = new ArrayList<>();
        List<Long> usedEpisodeIds = new ArrayList<>();
        List<Long> knowledgeIds = new ArrayList<>();
        for (ContextBudgetManager.IncludedEntry entry : allocation.entries()) {
            sections.computeIfAbsent(entry.section(), ignored -> new ArrayList<>()).add(entry.text());
            if ("MEMORY_ITEM".equals(entry.sourceKind())) usedMemoryItemIds.add(entry.id());
            if ("EPISODE".equals(entry.sourceKind())) usedEpisodeIds.add(entry.id());
            if ("KNOWLEDGE_RAG".equals(entry.section()) && entry.id() != null) knowledgeIds.add(entry.id());
        }
        String promptContext = sections.entrySet().stream()
                .map(entry -> "[" + entry.getKey() + "]\n" + String.join("\n", entry.getValue()))
                .collect(java.util.stream.Collectors.joining("\n\n"));
        return new ContextBuildResult(promptContext, Map.copyOf(sections),
                List.copyOf(usedMemoryItemIds), List.copyOf(usedEpisodeIds), List.copyOf(knowledgeIds),
                allocation.estimatedTokens(), allocation.tokenBudget(), allocation.truncated());
    }

    private List<MemorySearchHit> chronologicalEpisodeOrder(List<MemorySearchHit> hits) {
        if (hits == null || hits.size() < 2) return hits == null ? List.of() : List.copyOf(hits);
        List<MemorySearchHit> episodes = hits.stream()
                .filter(hit -> "EPISODE".equals(hit.sourceKind()))
                .sorted(java.util.Comparator.comparing(MemorySearchHit::occurredAt,
                        java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder()))
                        .thenComparing(MemorySearchHit::id,
                                java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())))
                .toList();
        if (episodes.size() < 2) return List.copyOf(hits);

        List<MemorySearchHit> ordered = new ArrayList<>(hits.size());
        int episodeIndex = 0;
        for (MemorySearchHit hit : hits) {
            if ("EPISODE".equals(hit.sourceKind())) ordered.add(episodes.get(episodeIndex++));
            else ordered.add(hit);
        }
        return List.copyOf(ordered);
    }

    private String join(String... values) {
        return java.util.Arrays.stream(values).filter(StringUtils::hasText).collect(
                java.util.stream.Collectors.joining("\n"));
    }

    private String safe(String value) { return value == null ? "" : value; }

    private List<MemorySearchHit> conflictEpisodes(Long userId, List<MemoryItem> items,
                                                    List<MemorySearchHit> retrievedHits) {
        LinkedHashMap<Long, MemorySearchHit> episodes = new LinkedHashMap<>();
        if (retrievedHits != null) {
            retrievedHits.stream()
                    .filter(hit -> "EPISODE".equals(hit.sourceKind()) && hit.id() != null)
                    .forEach(hit -> episodes.putIfAbsent(hit.id(), hit));
        }
        List<Long> sourceIds = sourceEpisodeIds(items);
        if (episodeService == null || sourceIds.isEmpty()) return List.copyOf(episodes.values());
        for (MemoryEpisode episode : episodeService.findOwnedByIds(userId, sourceIds)) {
            if (episode == null || episode.getId() == null) continue;
            BigDecimal importance = episode.getImportance() == null
                    ? new BigDecimal("0.5000") : episode.getImportance();
            episodes.putIfAbsent(episode.getId(), new MemorySearchHit("EPISODE", episode.getId(),
                    episode.getEpisodeType(), episode.getSummary(), episode.getSummary(),
                    episode.getPayloadJson(), null, "TEMPORARY_CONTEXT",
                    new BigDecimal("0.5000"), importance, episode.getOccurredAt(), null));
        }
        return List.copyOf(episodes.values());
    }

    private List<Long> sourceEpisodeIds(List<MemoryItem> items) {
        if (items == null || items.isEmpty()) return List.of();
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        for (MemoryItem item : items) {
            if (item == null || !StringUtils.hasText(item.getSourceEpisodeIdsJson())) continue;
            try {
                JsonNode sourceIds = objectMapper.readTree(item.getSourceEpisodeIdsJson());
                if (!sourceIds.isArray()) continue;
                sourceIds.forEach(value -> {
                    if (value.canConvertToLong() && value.asLong() > 0) ids.add(value.asLong());
                });
            } catch (Exception ignored) {
                // Malformed provenance is skipped; retrieval hits can still provide evidence.
            }
            if (ids.size() >= 500) break;
        }
        return ids.stream().limit(500).toList();
    }

    private String relevantProfile(String profileJson, MemoryQueryPlan plan) {
        try {
            JsonNode profile = objectMapper.readTree(profileJson);
            if (!(profile instanceof ObjectNode source)) return null;
            String query = plan == null || plan.originalQuery() == null
                    ? "" : plan.originalQuery().toLowerCase(java.util.Locale.ROOT);
            boolean feedbackQuestion = containsAny(query, "反馈", "评价", "评分")
                    && !containsAny(query, "昨天", "yesterday", "上次");
            boolean profileQuestion = containsAny(query, "偏好", "口味", "饮食习惯", "画像", "记得我",
                    "喜欢", "不喜欢", "不吃", "忌口", "不爱") || feedbackQuestion;
            boolean recommendation = containsAny(query, "推荐", "吃什么", "做什么", "今晚", "晚餐",
                    "晚饭", "健身", "训练", "recommend");
            boolean historyLookup = containsAny(query, "昨天", "上次", "历史", "找", "查看", "收藏过", "做过")
                    && !recommendation && !profileQuestion;
            if (historyLookup) return null;

            ObjectNode selected = objectMapper.createObjectNode();
            copyIfPresent(source, selected, "schemaVersion");
            copyIfPresent(source, selected, "updatedAt");
            copyIfPresent(source, selected, "summary");
            if (profileQuestion) {
                copyIfPresent(source, selected, "ingredientPreferences");
                copyIfPresent(source, selected, "recipePreferences");
                copyIfPresent(source, selected, "behaviorPatterns");
                copyIfPresent(source, selected, "dietGoals");
                copyIfPresent(source, selected, "otherMemories");
            } else if (recommendation || plan != null
                    && "POST_WORKOUT_RECIPE_RECALL".equals(plan.intent())) {
                copyIfPresent(source, selected, "ingredientPreferences");
                copyIfPresent(source, selected, "recipePreferences");
                copyIfPresent(source, selected, "behaviorPatterns");
                copyIfPresent(source, selected, "dietGoals");
            }
            boolean hasRelevantProfileData = selected.has("ingredientPreferences")
                    || selected.has("recipePreferences")
                    || selected.has("behaviorPatterns")
                    || selected.has("dietGoals")
                    || selected.has("otherMemories");
            return hasRelevantProfileData ? objectMapper.writeValueAsString(selected) : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private void copyIfPresent(ObjectNode source, ObjectNode target, String field) {
        JsonNode value = source.get(field);
        if (value != null && !value.isNull()) target.set(field, value.deepCopy());
    }

    private boolean containsAny(String value, String... terms) {
        for (String term : terms) if (value.contains(term)) return true;
        return false;
    }

    public record ExternalContext(String sourceKind, Long id, String text) { }

    public record ContextBuildResult(String promptContext, Map<String, List<String>> sections,
                                     List<Long> usedMemoryItemIds, List<Long> usedEpisodeIds,
                                     List<Long> knowledgeIds, int estimatedTokens,
                                     int tokenBudget, boolean truncated) { }
}
