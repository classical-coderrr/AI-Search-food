package com.example.food.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds task-scoped context while keeping personal memory and external knowledge separate. */
@Service
public class ContextBuilder {
    private final MemoryConsolidationService consolidationService;
    private final MemorySessionService sessionService;
    private final MemoryRankingProperties properties;
    private final ContextBudgetManager budgetManager;
    private final ObjectMapper objectMapper;

    public ContextBuilder(MemoryConsolidationService consolidationService,
                          MemorySessionService sessionService,
                          MemoryRankingProperties properties,
                          ContextBudgetManager budgetManager,
                          ObjectMapper objectMapper) {
        this.consolidationService = consolidationService;
        this.sessionService = sessionService;
        this.properties = properties;
        this.budgetManager = budgetManager;
        this.objectMapper = objectMapper;
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
            for (MemorySearchHit hit : retrieval.hits()) {
                String text = join(hit.title(), hit.content(), hit.payload());
                entries.add(new ContextBudgetManager.ContextEntry("PERSONAL_MEMORY", hit.sourceKind(),
                        hit.id(), text, 90, order++));
            }
        }

        MemoryProfile profile = consolidationService.getOwnedProfile(userId);
        if (profile != null && StringUtils.hasText(profile.getProfileJson())) {
            String relevantProfile = relevantProfile(profile.getProfileJson(),
                    retrieval == null ? null : retrieval.queryPlan());
            if (StringUtils.hasText(relevantProfile)) {
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

    private String join(String... values) {
        return java.util.Arrays.stream(values).filter(StringUtils::hasText).collect(
                java.util.stream.Collectors.joining("\n"));
    }

    private String safe(String value) { return value == null ? "" : value; }

    private String relevantProfile(String profileJson, MemoryQueryPlan plan) {
        try {
            JsonNode profile = objectMapper.readTree(profileJson);
            if (!(profile instanceof ObjectNode source)) return null;
            String query = plan == null || plan.originalQuery() == null
                    ? "" : plan.originalQuery().toLowerCase(java.util.Locale.ROOT);
            boolean profileQuestion = containsAny(query, "偏好", "口味", "饮食习惯", "画像", "记得我",
                    "喜欢", "不喜欢", "不吃", "忌口", "不爱");
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
                copyIfPresent(source, selected, "otherMemories");
            } else if (recommendation || plan != null
                    && "POST_WORKOUT_RECIPE_RECALL".equals(plan.intent())) {
                copyIfPresent(source, selected, "ingredientPreferences");
                copyIfPresent(source, selected, "recipePreferences");
                copyIfPresent(source, selected, "behaviorPatterns");
            }
            boolean hasRelevantProfileData = selected.has("ingredientPreferences")
                    || selected.has("recipePreferences")
                    || selected.has("behaviorPatterns")
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
