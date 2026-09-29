package com.example.food.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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
    private final PersonalizedSkillProjectionService skillProjectionService;
    private final MemoryConflictResolver conflictResolver;
    private final MemoryEpisodeService episodeService;

    public ContextBuilder(MemoryConsolidationService consolidationService,
                          MemorySessionService sessionService,
                          MemoryRankingProperties properties,
                          ContextBudgetManager budgetManager,
                          ObjectMapper objectMapper,
                          PersonalizedSkillService personalizedSkillService,
                          MemoryConflictResolver conflictResolver,
                          MemoryEpisodeService episodeService) {
        this(consolidationService, sessionService, properties, budgetManager, objectMapper,
                personalizedSkillService, conflictResolver, episodeService, null);
    }

    @Autowired
    public ContextBuilder(MemoryConsolidationService consolidationService,
                          MemorySessionService sessionService,
                          MemoryRankingProperties properties,
                          ContextBudgetManager budgetManager,
                          ObjectMapper objectMapper,
                          PersonalizedSkillService personalizedSkillService,
                          MemoryConflictResolver conflictResolver,
                          MemoryEpisodeService episodeService,
                          PersonalizedSkillProjectionService skillProjectionService) {
        this.consolidationService = consolidationService;
        this.sessionService = sessionService;
        this.properties = properties;
        this.budgetManager = budgetManager;
        this.objectMapper = objectMapper;
        this.personalizedSkillService = personalizedSkillService;
        this.skillProjectionService = skillProjectionService;
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
        Map<Long, MemoryConflictResolution> conflictEntries = new LinkedHashMap<>();
        long conflictEntrySequence = 0;
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
        PersonalizedSkillService.SkillContext skill = skillProjectionService == null
                ? personalizedSkillService.resolve(query, profile == null ? null : profile.getProfileJson())
                : skillProjectionService.resolve(userId, query, profile);
        if (skill != null) {
            entries.add(new ContextBudgetManager.ContextEntry("PERSONALIZED_SKILL",
                    "SKILL:" + skill.skillName(), null, skill.promptContext(), 88, order++));
        }
        if (profile != null && StringUtils.hasText(profile.getProfileJson())) {
            String relevantProfile = relevantProfile(profile.getProfileJson(),
                    retrieval == null ? null : retrieval.queryPlan(),
                    retrieval == null ? List.of() : retrieval.hits());
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
                    List<MemoryConflictResolution> resolutions = conflictResolver.resolve(relevantProfile, plan,
                            conflictEpisodes, conflictingItems, sourceCandidates);
                    for (MemoryConflictResolution resolution : resolutions) {
                        long conflictEntryId = conflictEntrySequence++;
                        conflictEntries.put(conflictEntryId, resolution);
                        entries.add(new ContextBudgetManager.ContextEntry("MEMORY_CONFLICTS",
                                "MEMORY_CONFLICT_DECISION", conflictEntryId,
                                resolution.explanation(), 94, order++));
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
        List<MemoryConflictResolution> includedConflictResolutions = allocation.entries().stream()
                .filter(entry -> "MEMORY_CONFLICT_DECISION".equals(entry.sourceKind()))
                .map(entry -> {
                    MemoryConflictResolution resolution = conflictEntries.get(entry.id());
                    return resolution != null && resolution.explanation().strip().equals(entry.text().strip())
                            ? resolution : null;
                })
                .filter(java.util.Objects::nonNull)
                .toList();
        String promptContext = sections.entrySet().stream()
                .map(entry -> "[" + entry.getKey() + "]\n" + String.join("\n", entry.getValue()))
                .collect(java.util.stream.Collectors.joining("\n\n"));
        return new ContextBuildResult(promptContext, Map.copyOf(sections),
                List.copyOf(usedMemoryItemIds), List.copyOf(usedEpisodeIds), List.copyOf(knowledgeIds),
                allocation.estimatedTokens(), allocation.tokenBudget(), allocation.truncated(),
                includedConflictResolutions);
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

    private String relevantProfile(String profileJson, MemoryQueryPlan plan, List<MemorySearchHit> hits) {
        try {
            JsonNode profile = objectMapper.readTree(profileJson);
            if (!(profile instanceof ObjectNode source)) return null;
            String query = plan == null || plan.originalQuery() == null
                    ? "" : plan.originalQuery().toLowerCase(Locale.ROOT);
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
                ProfileSelection matches = matchingProfileEntries(source, query, plan, hits);
                if (matches.hasMatches()) {
                    for (String field : fieldsForFocusedQuestion(query, matches.fields())) {
                        JsonNode filtered = filterMatchedEntries(source.get(field), matches);
                        if (filtered != null && !filtered.isEmpty()) selected.set(field, filtered);
                    }
                } else if (isProfileOverview(query)) {
                    copyAllProfileGroups(source, selected);
                } else {
                    copyProfileGroupsForTopic(query, source, selected);
                }
            } else if (recommendation || plan != null
                    && "POST_WORKOUT_RECIPE_RECALL".equals(plan.intent())) {
                copyIfPresent(source, selected, "ingredientPreferences");
                copyIfPresent(source, selected, "recipePreferences");
                copyIfPresent(source, selected, "behaviorPatterns");
                copyIfPresent(source, selected, "dietGoals");
                copyIfPresent(source, selected, "skillPreferences");
            }
            boolean hasRelevantProfileData = selected.has("ingredientPreferences")
                    || selected.has("recipePreferences")
                    || selected.has("behaviorPatterns")
                    || selected.has("dietGoals")
                    || selected.has("skillPreferences")
                    || selected.has("otherMemories");
            return hasRelevantProfileData ? objectMapper.writeValueAsString(selected) : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private ProfileSelection matchingProfileEntries(
            ObjectNode profile,
            String query,
            MemoryQueryPlan plan,
            List<MemorySearchHit> hits
    ) {
        Set<Long> ids = new LinkedHashSet<>();
        Set<String> entities = new LinkedHashSet<>();
        Set<String> canonicalGroups = new LinkedHashSet<>();
        Set<String> fields = new LinkedHashSet<>();
        Set<String> selectors = new LinkedHashSet<>();
        selectors.add(normalizeMatchText(query));
        if (plan != null) {
            addSelectors(selectors, plan.ingredients());
            addSelectors(selectors, plan.dietGoals());
        }
        Set<Long> queryMatchedHitIds = new LinkedHashSet<>();
        if (hits != null) {
            for (MemorySearchHit hit : hits) {
                if (hit == null || hit.id() == null || !"MEMORY_ITEM".equalsIgnoreCase(hit.sourceKind())) continue;
                String title = normalizeMatchText(hit.title());
                if (!title.isEmpty() && selectors.stream().anyMatch(selector -> selector.contains(title))) {
                    queryMatchedHitIds.add(hit.id());
                }
            }
        }
        for (String field : profileGroupFields()) {
            collectProfileMatches(profile.get(field), field, selectors, queryMatchedHitIds,
                    ids, entities, canonicalGroups, fields);
        }
        return new ProfileSelection(ids, entities, canonicalGroups, fields);
    }

    private void collectProfileMatches(
            JsonNode node,
            String field,
            Set<String> selectors,
            Set<Long> queryMatchedHitIds,
            Set<Long> ids,
            Set<String> entities,
            Set<String> canonicalGroups,
            Set<String> fields
    ) {
        if (node == null || node.isNull()) return;
        if (node.isObject()) {
            String entity = normalizeMatchText(node.path("entity").asText(null));
            Long id = node.path("id").canConvertToLong() ? node.path("id").asLong() : null;
            String group = normalizeMatchText(node.path("canonicalGroupId").asText(null));
            boolean directMatch = !entity.isEmpty()
                    && selectors.stream().anyMatch(selector -> selector.contains(entity));
            boolean retrievedMatch = id != null && queryMatchedHitIds.contains(id);
            if (directMatch || retrievedMatch) {
                if (id != null && id > 0) ids.add(id);
                if (!entity.isEmpty()) entities.add(entity);
                if (!group.isEmpty()) canonicalGroups.add(group);
                fields.add(field);
            }
            node.elements().forEachRemaining(child -> collectProfileMatches(child, field, selectors,
                    queryMatchedHitIds, ids, entities, canonicalGroups, fields));
        } else if (node.isArray()) {
            node.elements().forEachRemaining(child -> collectProfileMatches(child, field, selectors,
                    queryMatchedHitIds, ids, entities, canonicalGroups, fields));
        }
    }

    private JsonNode filterMatchedEntries(JsonNode node, ProfileSelection matches) {
        if (node == null || node.isNull()) return null;
        if (node.isArray()) {
            ArrayNode filtered = objectMapper.createArrayNode();
            for (JsonNode child : node) {
                JsonNode value = filterMatchedEntries(child, matches);
                if (value != null && !value.isEmpty()) filtered.add(value);
            }
            return filtered.isEmpty() ? null : filtered;
        }
        if (node.isObject()) {
            if (node.hasNonNull("entity")) return matches.matches(node) ? node.deepCopy() : null;
            ObjectNode filtered = objectMapper.createObjectNode();
            node.fields().forEachRemaining(entry -> {
                JsonNode value = filterMatchedEntries(entry.getValue(), matches);
                if (value != null && !value.isEmpty()) filtered.set(entry.getKey(), value);
            });
            return filtered.isEmpty() ? null : filtered;
        }
        if (node.isTextual()) {
            return matches.entities().contains(normalizeMatchText(node.asText())) ? node.deepCopy() : null;
        }
        return null;
    }

    private Set<String> fieldsForFocusedQuestion(String query, Set<String> matchedFields) {
        if (containsAny(query, "菜谱", "食谱", "哪道菜", "这道菜", "什么菜")) {
            return matchedFields.contains("recipePreferences") ? Set.of("recipePreferences") : matchedFields;
        }
        if (containsAny(query, "饮食目标", "增肌", "减脂", "控糖", "高蛋白", "低脂")) {
            return matchedFields.contains("dietGoals") ? Set.of("dietGoals") : matchedFields;
        }
        if (containsAny(query, "习惯", "回答方式", "解释", "候选", "克数", "烹饪时间")) {
            LinkedHashSet<String> behavior = new LinkedHashSet<>();
            if (matchedFields.contains("behaviorPatterns")) behavior.add("behaviorPatterns");
            if (matchedFields.contains("skillPreferences")) behavior.add("skillPreferences");
            return behavior.isEmpty() ? matchedFields : Set.copyOf(behavior);
        }
        if (matchedFields.contains("ingredientPreferences")) return Set.of("ingredientPreferences");
        return matchedFields;
    }

    private void copyAllProfileGroups(ObjectNode source, ObjectNode target) {
        for (String field : profileGroupFields()) copyIfPresent(source, target, field);
    }

    private void copyProfileGroupsForTopic(String query, ObjectNode source, ObjectNode target) {
        if (containsAny(query, "食材", "忌口", "过敏", "口味", "不吃", "不喜欢")) {
            copyIfPresent(source, target, "ingredientPreferences");
        }
        if (containsAny(query, "菜谱", "食谱", "哪道菜", "做过", "收藏过")) {
            copyIfPresent(source, target, "recipePreferences");
        }
        if (containsAny(query, "饮食目标", "增肌", "减脂", "控糖", "高蛋白", "低脂")) {
            copyIfPresent(source, target, "dietGoals");
        }
        if (containsAny(query, "习惯", "回答方式", "解释", "候选", "克数", "烹饪时间")) {
            copyIfPresent(source, target, "behaviorPatterns");
            copyIfPresent(source, target, "skillPreferences");
        }
    }

    private boolean isProfileOverview(String query) {
        return containsAny(query, "我的偏好", "有哪些偏好", "全部偏好", "整体画像", "个人画像",
                "你记得我什么", "我有哪些长期偏好", "我的饮食习惯", "我喜欢什么", "我不喜欢什么",
                "我不吃什么", "我有哪些忌口");
    }

    private List<String> profileGroupFields() {
        return List.of("ingredientPreferences", "recipePreferences", "behaviorPatterns",
                "skillPreferences", "dietGoals", "otherMemories");
    }

    private void addSelectors(Set<String> selectors, List<String> values) {
        if (values == null) return;
        values.stream().map(this::normalizeMatchText).filter(value -> !value.isEmpty()).forEach(selectors::add);
    }

    private String normalizeMatchText(String value) {
        if (!StringUtils.hasText(value)) return "";
        return java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{Punct}\\p{P}\\p{Z}\\s]+", "");
    }

    private record ProfileSelection(Set<Long> ids, Set<String> entities,
                                    Set<String> canonicalGroups, Set<String> fields) {
        private boolean hasMatches() {
            return !ids.isEmpty() || !entities.isEmpty() || !canonicalGroups.isEmpty();
        }

        private boolean matches(JsonNode entry) {
            Long id = entry.path("id").canConvertToLong() ? entry.path("id").asLong() : null;
            String entity = normalizeValue(entry.path("entity").asText(null));
            String group = normalizeValue(entry.path("canonicalGroupId").asText(null));
            return id != null && ids.contains(id)
                    || !entity.isEmpty() && entities.contains(entity)
                    || !group.isEmpty() && canonicalGroups.contains(group);
        }

        private static String normalizeValue(String value) {
            return value == null ? "" : java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKC)
                    .toLowerCase(Locale.ROOT).replaceAll("[\\p{Punct}\\p{P}\\p{Z}\\s]+", "");
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
                                     int tokenBudget, boolean truncated,
                                     List<MemoryConflictResolution> conflictResolutions) {
        public ContextBuildResult(String promptContext, Map<String, List<String>> sections,
                                  List<Long> usedMemoryItemIds, List<Long> usedEpisodeIds,
                                  List<Long> knowledgeIds, int estimatedTokens,
                                  int tokenBudget, boolean truncated) {
            this(promptContext, sections, usedMemoryItemIds, usedEpisodeIds, knowledgeIds,
                    estimatedTokens, tokenBudget, truncated, List.of());
        }

        public ContextBuildResult {
            conflictResolutions = conflictResolutions == null ? List.of() : List.copyOf(conflictResolutions);
        }
    }
}
