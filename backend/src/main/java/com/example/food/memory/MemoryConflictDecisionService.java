package com.example.food.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Stores privacy-minimized, user-owned records of task-specific conflict decisions. */
@Service
public class MemoryConflictDecisionService {
    private final MemoryConflictDecisionMapper mapper;
    private final MemorySessionMapper sessionMapper;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public MemoryConflictDecisionService(MemoryConflictDecisionMapper mapper, MemorySessionMapper sessionMapper,
                                        ObjectMapper objectMapper, Clock clock) {
        this.mapper = mapper;
        this.sessionMapper = sessionMapper;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public int record(Long userId, String traceId, Long sessionId, List<MemoryConflictResolution> resolutions) {
        if (userId == null || userId <= 0 || traceId == null || traceId.isBlank()
                || resolutions == null || resolutions.isEmpty()) return 0;
        if (sessionId != null && (sessionId <= 0 || sessionMapper.findOwned(userId, sessionId) == null)) return 0;

        int inserted = 0;
        for (MemoryConflictResolution resolution : resolutions) {
            if (!valid(resolution)) continue;
            if (mapper.countOwnedActivePair(userId, resolution.likeMemoryItemId(),
                    resolution.dislikeMemoryItemId()) != 2) continue;
            MemoryConflictDecision decision = new MemoryConflictDecision();
            decision.setUserId(userId);
            decision.setTraceId(traceId);
            decision.setSessionId(sessionId);
            decision.setConflictKey(resolution.conflictKey());
            decision.setConflictDomain(resolution.domain());
            decision.setCanonicalEntity(resolution.canonicalEntity());
            decision.setLikeMemoryItemId(resolution.likeMemoryItemId());
            decision.setDislikeMemoryItemId(resolution.dislikeMemoryItemId());
            decision.setSelectedMemoryItemId(resolution.selectedMemoryItemId());
            decision.setSelectedPreference(resolution.selectedPreference());
            decision.setResolutionType(resolution.resolutionType());
            decision.setReason(resolution.reason());
            decision.setContextJson(contextJson(resolution.contextSignals()));
            decision.setCreatedAt(LocalDateTime.now(clock));
            inserted += mapper.insertIdempotent(decision) == 1 ? 1 : 0;
        }
        return inserted;
    }

    public int deleteOwnedForMemory(Long userId, Long memoryItemId) {
        if (userId == null || userId <= 0 || memoryItemId == null || memoryItemId <= 0) return 0;
        return mapper.deleteOwnedForMemory(userId, memoryItemId);
    }

    public int deleteAllOwned(Long userId) {
        if (userId == null || userId <= 0) return 0;
        return mapper.deleteAllOwned(userId);
    }

    private boolean valid(MemoryConflictResolution resolution) {
        if (resolution == null || resolution.conflictKey() == null || resolution.conflictKey().isBlank()
                || resolution.likeMemoryItemId() == null || resolution.likeMemoryItemId() <= 0
                || resolution.dislikeMemoryItemId() == null || resolution.dislikeMemoryItemId() <= 0
                || resolution.likeMemoryItemId().equals(resolution.dislikeMemoryItemId())) return false;

        Long selectedId = resolution.selectedMemoryItemId();
        String preference = resolution.selectedPreference();
        if ("UNRESOLVED".equals(preference)) return selectedId == null;
        return "LIKE".equals(preference) && resolution.likeMemoryItemId().equals(selectedId)
                || "DISLIKE".equals(preference) && resolution.dislikeMemoryItemId().equals(selectedId);
    }

    private String contextJson(List<String> signals) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("signals", signals == null ? List.of() : signals);
        try {
            return objectMapper.writeValueAsString(context);
        } catch (JsonProcessingException ignored) {
            return "{\"signals\":[]}";
        }
    }
}
