package com.example.food.agent;

import com.example.food.memory.ContextBuilder;
import com.example.food.memory.MemorySearchHit;
import com.example.food.memory.MemoryRetrievalResult;
import com.example.food.memory.MemoryRetriever;
import com.example.food.memory.MemorySearchCommand;
import com.example.food.memory.MemorySession;
import com.example.food.memory.MemorySessionOpenCommand;
import com.example.food.memory.MemorySessionService;
import com.example.food.memory.MemorySessionUpdate;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Bridges the user-scoped memory pipeline into one Agent run. */
@Service
public class AgentMemoryContextProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgentMemoryContextProvider.class);

    private final MemoryRetriever memoryRetriever;
    private final ContextBuilder contextBuilder;
    private final MemorySessionService sessionService;
    private final ObjectMapper objectMapper;

    public AgentMemoryContextProvider(MemoryRetriever memoryRetriever,
                                      ContextBuilder contextBuilder,
                                      MemorySessionService sessionService,
                                      ObjectMapper objectMapper) {
        this.memoryRetriever = memoryRetriever;
        this.contextBuilder = contextBuilder;
        this.sessionService = sessionService;
        this.objectMapper = objectMapper;
    }

    public PreparedContext prepare(Long userId, Long conversationId, String runId, String query) {
        MemorySession session = null;
        try {
            LocalDateTime expiresAt = LocalDateTime.now().plusHours(2);
            session = sessionService.open(userId, new MemorySessionOpenCommand(
                    conversationId,
                    "agent-run:" + runId,
                    "AGENT_REQUEST",
                    "检索与本轮任务相关的历史记忆",
                    "{}",
                    "[]",
                    "[]",
                    null,
                    expiresAt
            ));

            MemoryRetrievalResult retrieval = memoryRetriever.search(
                    userId, MemorySearchCommand.query(query, session.getId()));
            Map<String, Object> sessionContext = new LinkedHashMap<>();
            sessionContext.put("intent", retrieval.queryPlan().intent());
            sessionContext.put("scenes", retrieval.queryPlan().scenes());
            sessionContext.put("mealTypes", retrieval.queryPlan().mealTypes());
            sessionContext.put("ingredients", retrieval.queryPlan().ingredients());
            sessionContext.put("dietGoals", retrieval.queryPlan().dietGoals());

            sessionService.touch(userId, session.getId(), new MemorySessionUpdate(
                    conversationId,
                    retrieval.queryPlan().intent(),
                    "检索与本轮任务相关的历史记忆",
                    writeJson(sessionContext),
                    writeJson(Map.of(
                            "memoryItemIds", retrieval.trace().selectedMemoryItemIds(),
                            "episodeIds", retrieval.trace().selectedEpisodeIds()
                    )),
                    "[]",
                    writeJson(Map.of("runId", runId, "status", "RETRIEVED")),
                    expiresAt
            ));
            ContextBuilder.ContextBuildResult context = contextBuilder.build(
                    userId, session.getId(), retrieval, List.of(), List.of(), null);

            sessionService.touch(userId, session.getId(), new MemorySessionUpdate(
                    conversationId,
                    retrieval.queryPlan().intent(),
                    "检索与本轮任务相关的历史记忆",
                    writeJson(sessionContext),
                    writeJson(Map.of(
                            "memoryItemIds", context.usedMemoryItemIds(),
                            "episodeIds", context.usedEpisodeIds()
                    )),
                    writeJson(context.knowledgeIds()),
                    writeJson(Map.of("runId", runId, "status", "CONTEXT_BUILT")),
                    expiresAt
            ));
            try {
                sessionService.close(userId, session.getId());
            } catch (RuntimeException closeFailure) {
                LOGGER.warn("Agent memory session close deferred, runId={}, userId={}, errorType={}",
                        runId, userId, closeFailure.getClass().getSimpleName());
            }

            MemoryRetrievalResult.RetrievalTrace trace = retrieval.trace();
            return new PreparedContext(
                    session.getId(),
                    context.promptContext(),
                    "SUCCESS",
                    null,
                    retrieval.queryPlan().intent(),
                    trace.memoryItemCandidates(),
                    trace.episodeCandidates(),
                    trace.selectedMemoryItemIds(),
                    trace.selectedEpisodeIds(),
                    context.usedMemoryItemIds(),
                    context.usedEpisodeIds(),
                    context.knowledgeIds(),
                    context.sections().keySet().stream().toList(),
                    traceSummaries(retrieval, context),
                    context.estimatedTokens(),
                    context.tokenBudget(),
                    context.truncated()
            );
        } catch (RuntimeException exception) {
            LOGGER.warn("Agent memory context degraded, runId={}, userId={}, errorType={}",
                    runId, userId, exception.getClass().getSimpleName());
            return PreparedContext.degraded(session == null ? null : session.getId(),
                    exception.getClass().getSimpleName());
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("记忆上下文序列化失败", exception);
        }
    }

    private List<String> traceSummaries(MemoryRetrievalResult retrieval,
                                        ContextBuilder.ContextBuildResult context) {
        Set<Long> usedMemoryItemIds = Set.copyOf(context.usedMemoryItemIds());
        Set<Long> usedEpisodeIds = Set.copyOf(context.usedEpisodeIds());
        LinkedHashSet<String> summaries = new LinkedHashSet<>();

        for (MemorySearchHit hit : retrieval.hits()) {
            if ("MEMORY_ITEM".equals(hit.sourceKind()) && usedMemoryItemIds.contains(hit.id())) {
                String entity = safeTraceText(hit.title());
                if (entity != null) {
                    summaries.add(preferenceTrace(hit.preference(), hit.temporalType()) + entity);
                }
            } else if ("EPISODE".equals(hit.sourceKind()) && usedEpisodeIds.contains(hit.id())) {
                String summary = safeTraceText(hit.title());
                if (summary != null) summaries.add("历史行为：" + summary);
            }
        }

        List<String> profileSummaries = profileTraceSummaries(
                context.sections().get("STRUCTURED_PROFILE"));
        summaries.addAll(profileSummaries);
        if (profileSummaries.isEmpty() && context.sections().containsKey("STRUCTURED_PROFILE")) {
            summaries.add("已参考与你本轮任务相关的结构化用户画像");
        }

        return summaries.stream().limit(6).toList();
    }

    private List<String> profileTraceSummaries(List<String> profileSections) {
        if (profileSections == null || profileSections.isEmpty()) return List.of();
        LinkedHashSet<String> summaries = new LinkedHashSet<>();
        for (String profileSection : profileSections) {
            try {
                JsonNode profile = objectMapper.readTree(profileSection);
                collectProfileGroup(profile, "ingredientPreferences", "食材偏好", summaries);
                collectProfileGroup(profile, "recipePreferences", "菜谱偏好", summaries);
                collectProfileGroup(profile, "behaviorPatterns", "行为习惯", summaries);
            } catch (JsonProcessingException ignored) {
                // The user-facing trace intentionally omits profile content it cannot safely parse.
            }
        }
        return summaries.stream().limit(4).toList();
    }

    private void collectProfileGroup(JsonNode profile, String key, String label, Set<String> summaries) {
        JsonNode groups = profile.path(key);
        collectProfileEntities(groups.path("liked"), label, "LIKE", summaries);
        collectProfileEntities(groups.path("disliked"), label, "DISLIKE", summaries);
    }

    private void collectProfileEntities(JsonNode entities, String label, String groupPreference,
                                        Set<String> summaries) {
        if (!entities.isArray()) return;
        for (JsonNode entity : entities) {
            String name = safeTraceText(entity.path("entity").asText(null));
            if (name != null) {
                String temporalType = entity.path("temporalType").asText();
                String period = "RECENT".equalsIgnoreCase(temporalType)
                        ? "（近期行为推断）" : "LONG_TERM".equalsIgnoreCase(temporalType)
                        ? "（长期）" : "";
                String relation = "DISLIKE".equals(groupPreference) ? "不喜欢" : "喜欢";
                summaries.add(label + period + "：" + relation + name);
            }
        }
    }

    private String preferenceTrace(String preference, String temporalType) {
        String temporalLabel = "RECENT".equalsIgnoreCase(temporalType)
                ? "近期行为推断："
                : "LONG_TERM".equalsIgnoreCase(temporalType) ? "长期偏好：" : "相关记忆：";
        if (preference == null) return temporalLabel;
        return temporalLabel + switch (preference.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "LIKE" -> "喜欢";
            case "DISLIKE" -> "不喜欢";
            default -> "";
        };
    }

    private String safeTraceText(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.replaceAll("\\s+", " ").trim();
        if (normalized.startsWith("{") || normalized.startsWith("[")) return null;
        int maxLength = 96;
        return normalized.length() <= maxLength
                ? normalized
                : normalized.substring(0, maxLength) + "…";
    }

    public record PreparedContext(
            Long sessionId,
            String promptContext,
            String status,
            String errorType,
            String intent,
            int memoryItemCandidates,
            int episodeCandidates,
            List<Long> retrievedMemoryItemIds,
            List<Long> retrievedEpisodeIds,
            List<Long> usedMemoryItemIds,
            List<Long> usedEpisodeIds,
            List<Long> knowledgeIds,
            List<String> contextSections,
            List<String> traceSummaries,
            int estimatedTokens,
            int tokenBudget,
            boolean truncated
    ) {
        public static PreparedContext degraded(Long sessionId, String errorType) {
            return new PreparedContext(sessionId, "", "DEGRADED", errorType, null,
                    0, 0, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                    List.of(), 0, 0, false);
        }
    }
}
