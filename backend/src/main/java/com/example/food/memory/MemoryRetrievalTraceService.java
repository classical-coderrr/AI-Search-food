package com.example.food.memory;

import com.example.food.memory.ContextBuilder.ContextBuildResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Persists privacy-minimized, user-owned traces for retrieval and context decisions. */
@Service
public class MemoryRetrievalTraceService {
    private final MemoryRetrievalTraceMapper mapper;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public MemoryRetrievalTraceService(MemoryRetrievalTraceMapper mapper, ObjectMapper objectMapper, Clock clock) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public boolean record(Long userId, String traceId, String query, MemoryRetrievalResult retrieval,
                          ContextBuildResult context, String status, String errorType, long latencyMs) {
        if (userId == null || userId <= 0 || traceId == null || traceId.isBlank()) return false;

        MemoryRetrievalTrace trace = new MemoryRetrievalTrace();
        trace.setTraceId(traceId);
        trace.setUserId(userId);
        trace.setSessionId(retrieval == null ? null : retrieval.queryPlan().sessionId());
        trace.setIntent(retrieval == null ? null : retrieval.queryPlan().intent());
        trace.setQueryHash(sha256(query == null ? "" : query));
        trace.setMemoryItemCandidates(retrieval == null ? 0 : retrieval.trace().memoryItemCandidates());
        trace.setEpisodeCandidates(retrieval == null ? 0 : retrieval.trace().episodeCandidates());
        trace.setRetrievedMemoryItemIdsJson(json(retrieval == null
                ? List.of() : retrieval.trace().selectedMemoryItemIds()));
        trace.setRetrievedEpisodeIdsJson(json(retrieval == null
                ? List.of() : retrieval.trace().selectedEpisodeIds()));
        trace.setUsedMemoryItemIdsJson(json(context == null ? List.of() : context.usedMemoryItemIds()));
        trace.setUsedEpisodeIdsJson(json(context == null ? List.of() : context.usedEpisodeIds()));
        trace.setKnowledgeIdsJson(json(context == null ? List.of() : context.knowledgeIds()));
        trace.setRankingJson(json(ranking(retrieval, context)));
        trace.setContextSectionsJson(json(context == null ? List.of() : context.sections().keySet()));
        trace.setEstimatedTokens(context == null ? 0 : context.estimatedTokens());
        trace.setTokenBudget(context == null ? 0 : context.tokenBudget());
        trace.setTruncated(context != null && context.truncated());
        trace.setStatus(status);
        trace.setErrorType(limit(errorType, 128));
        trace.setLatencyMs(Math.max(0L, latencyMs));
        trace.setCreatedAt(LocalDateTime.now(clock));
        try {
            return mapper.insert(trace) == 1;
        } catch (DuplicateKeyException duplicate) {
            return false;
        }
    }

    public int deleteAllOwned(Long userId) {
        return mapper.deleteAllOwned(userId);
    }

    public int deleteBefore(LocalDateTime before) {
        return mapper.deleteBefore(before);
    }

    public int deleteExpired(Duration retention) {
        Duration safeRetention = retention == null || retention.isNegative() || retention.isZero()
                ? Duration.ofDays(30) : retention;
        return deleteBefore(LocalDateTime.now(clock).minus(safeRetention));
    }

    public Map<String, Object> summarizeSince(LocalDateTime fromTime) {
        Map<String, Object> summary = mapper.summarizeSince(fromTime);
        return summary == null ? Map.of() : summary;
    }

    private List<Map<String, Object>> ranking(MemoryRetrievalResult retrieval, ContextBuildResult context) {
        if (retrieval == null) return List.of();
        List<Long> usedItems = context == null ? List.of() : context.usedMemoryItemIds();
        List<Long> usedEpisodes = context == null ? List.of() : context.usedEpisodeIds();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (MemorySearchHit hit : retrieval.hits()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("sourceKind", hit.sourceKind());
            row.put("id", hit.id());
            row.put("memoryType", hit.memoryType());
            row.put("used", "MEMORY_ITEM".equals(hit.sourceKind())
                    ? usedItems.contains(hit.id()) : usedEpisodes.contains(hit.id()));
            row.put("score", hit.scores());
            rows.add(row);
        }
        return rows;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? List.of() : value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Memory trace serialization failed", exception);
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String limit(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) return value;
        return value.substring(0, maxLength);
    }
}
