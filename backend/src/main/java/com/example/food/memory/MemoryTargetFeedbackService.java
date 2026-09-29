package com.example.food.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class MemoryTargetFeedbackService {
    private static final TypeReference<List<Long>> ID_LIST = new TypeReference<>() { };

    private final MemoryTargetFeedbackMapper feedbackMapper;
    private final MemoryRetrievalTraceMapper traceMapper;
    private final MemoryItemMapper itemMapper;
    private final MemoryEpisodeMapper episodeMapper;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public MemoryTargetFeedbackService(MemoryTargetFeedbackMapper feedbackMapper,
                                       MemoryRetrievalTraceMapper traceMapper,
                                       MemoryItemMapper itemMapper,
                                       MemoryEpisodeMapper episodeMapper,
                                       ObjectMapper objectMapper,
                                       Clock clock) {
        this.feedbackMapper = feedbackMapper;
        this.traceMapper = traceMapper;
        this.itemMapper = itemMapper;
        this.episodeMapper = episodeMapper;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public List<MemoryTargetFeedbackTargetResponse> targets(Long userId, String requestedTraceId) {
        String traceId = normalizeTraceId(userId, requestedTraceId, "查看");
        MemoryRetrievalTrace trace = ownedTrace(userId, traceId);
        List<Long> usedItemIds = readIds(trace.getUsedMemoryItemIdsJson());
        List<Long> usedEpisodeIds = readIds(trace.getUsedEpisodeIdsJson());
        Map<String, MemoryFeedbackType> feedbackByTarget = new HashMap<>();
        for (MemoryTargetFeedback feedback : feedbackMapper.findOwnedByTrace(userId, traceId)) {
            Long sourceId = MemoryTargetFeedbackSource.MEMORY_ITEM.name().equals(feedback.getSourceKind())
                    ? feedback.getMemoryItemId() : feedback.getEpisodeId();
            if (sourceId != null) {
                feedbackByTarget.put(key(feedback.getSourceKind(), sourceId),
                        MemoryFeedbackType.valueOf(feedback.getFeedbackType()));
            }
        }

        Map<Long, MemoryItem> itemsById = new LinkedHashMap<>();
        if (!usedItemIds.isEmpty()) {
            for (MemoryItem item : itemMapper.findActiveOwnedByIds(userId, usedItemIds)) {
                itemsById.put(item.getId(), item);
            }
        }
        Map<Long, MemoryEpisode> episodesById = new LinkedHashMap<>();
        if (!usedEpisodeIds.isEmpty()) {
            for (MemoryEpisode episode : episodeMapper.findOwnedByIds(userId, usedEpisodeIds)) {
                episodesById.put(episode.getId(), episode);
            }
        }

        List<MemoryTargetFeedbackTargetResponse> result = new ArrayList<>();
        for (Long id : usedItemIds) {
            MemoryItem item = itemsById.get(id);
            if (item == null) continue;
            result.add(new MemoryTargetFeedbackTargetResponse(MemoryTargetFeedbackSource.MEMORY_ITEM,
                    id, item.getCanonicalEntity(), itemDetail(item),
                    feedbackByTarget.get(key(MemoryTargetFeedbackSource.MEMORY_ITEM.name(), id))));
        }
        for (Long id : usedEpisodeIds) {
            MemoryEpisode episode = episodesById.get(id);
            if (episode == null) continue;
            String title = StringUtils.hasText(episode.getSummary())
                    ? episode.getSummary() : episode.getEpisodeType();
            result.add(new MemoryTargetFeedbackTargetResponse(MemoryTargetFeedbackSource.EPISODE,
                    id, title, episode.getEpisodeType(),
                    feedbackByTarget.get(key(MemoryTargetFeedbackSource.EPISODE.name(), id))));
        }
        return List.copyOf(result);
    }

    @Transactional
    public MemoryTargetFeedbackResponse submit(Long userId, MemoryTargetFeedbackRequest request) {
        if (userId == null || userId <= 0 || request == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "需要登录后提交记忆反馈");
        }
        String traceId = normalizeTraceId(userId, request.traceId(), "提交");
        MemoryRetrievalTrace trace = ownedTrace(userId, traceId);
        Long sourceId = request.sourceId();
        if (sourceId == null || sourceId <= 0 || request.sourceKind() == null || request.feedbackType() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "记忆反馈内容不完整");
        }
        verifyWasUsed(trace, request.sourceKind(), sourceId);
        verifyOwnedTarget(userId, request.sourceKind(), sourceId);

        LocalDateTime now = LocalDateTime.now(clock);
        MemoryTargetFeedback feedback = findForUpdate(userId, traceId, request.sourceKind(), sourceId);
        boolean updated = false;
        if (feedback == null) {
            feedback = new MemoryTargetFeedback();
            feedback.setUserId(userId);
            feedback.setTraceId(traceId);
            feedback.setIntent(StringUtils.hasText(trace.getIntent()) ? trace.getIntent() : "UNKNOWN");
            feedback.setSourceKind(request.sourceKind().name());
            feedback.setMemoryItemId(request.sourceKind() == MemoryTargetFeedbackSource.MEMORY_ITEM ? sourceId : null);
            feedback.setEpisodeId(request.sourceKind() == MemoryTargetFeedbackSource.EPISODE ? sourceId : null);
            feedback.setFeedbackType(request.feedbackType().name());
            feedback.setCreatedAt(now);
            feedback.setUpdatedAt(now);
            try {
                feedbackMapper.insert(feedback);
            } catch (DuplicateKeyException concurrentSubmission) {
                feedback = findForUpdate(userId, traceId, request.sourceKind(), sourceId);
                if (feedback == null) throw concurrentSubmission;
                updated = updateIfChanged(feedback, userId, request.feedbackType(), now);
            }
        } else {
            updated = updateIfChanged(feedback, userId, request.feedbackType(), now);
        }
        return response(feedback, request.sourceKind(), sourceId, updated);
    }

    public Map<MemoryVectorKey, MemoryFeedbackType> recentSignals(Long userId,
                                                                  String intent,
                                                                  List<MemorySearchHit> candidates,
                                                                  LocalDateTime since) {
        if (userId == null || userId <= 0 || !StringUtils.hasText(intent) || candidates == null
                || candidates.isEmpty() || since == null) return Map.of();
        List<Long> itemIds = candidates.stream()
                .filter(hit -> "MEMORY_ITEM".equals(hit.sourceKind()))
                .map(MemorySearchHit::id).filter(id -> id != null).distinct().toList();
        List<Long> episodeIds = candidates.stream()
                .filter(hit -> "EPISODE".equals(hit.sourceKind()))
                .map(MemorySearchHit::id).filter(id -> id != null).distinct().toList();
        Map<MemoryVectorKey, MemoryFeedbackType> result = new LinkedHashMap<>();
        if (!itemIds.isEmpty()) {
            for (MemoryTargetFeedback feedback : feedbackMapper.findRecentMemoryItemSignals(
                    userId, intent, itemIds, since)) {
                if (feedback.getMemoryItemId() != null) {
                    result.putIfAbsent(new MemoryVectorKey("MEMORY_ITEM", feedback.getMemoryItemId()),
                            MemoryFeedbackType.valueOf(feedback.getFeedbackType()));
                }
            }
        }
        if (!episodeIds.isEmpty()) {
            for (MemoryTargetFeedback feedback : feedbackMapper.findRecentEpisodeSignals(
                    userId, intent, episodeIds, since)) {
                if (feedback.getEpisodeId() != null) {
                    result.putIfAbsent(new MemoryVectorKey("EPISODE", feedback.getEpisodeId()),
                            MemoryFeedbackType.valueOf(feedback.getFeedbackType()));
                }
            }
        }
        return Map.copyOf(result);
    }

    public Map<String, Object> summarizeUsageFeedbackSince(LocalDateTime fromTime) {
        if (fromTime == null) return Map.of();
        Map<String, Object> summary = feedbackMapper.summarizeUsageFeedbackSince(fromTime);
        return summary == null ? Map.of() : summary;
    }

    private String normalizeTraceId(Long userId, String requestedTraceId, String action) {
        if (userId == null || userId <= 0) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "需要登录后" + action + "记忆反馈");
        }
        if (!StringUtils.hasText(requestedTraceId) || requestedTraceId.trim().length() > 64) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "记忆追踪编号无效");
        }
        return requestedTraceId.trim();
    }

    private MemoryRetrievalTrace ownedTrace(Long userId, String traceId) {
        MemoryRetrievalTrace trace = traceMapper.findOwnedByTraceId(userId, traceId);
        if (trace == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "记忆追踪记录不存在");
        }
        return trace;
    }

    private void verifyWasUsed(MemoryRetrievalTrace trace, MemoryTargetFeedbackSource source, Long sourceId) {
        List<Long> usedIds = source == MemoryTargetFeedbackSource.MEMORY_ITEM
                ? readIds(trace.getUsedMemoryItemIdsJson()) : readIds(trace.getUsedEpisodeIdsJson());
        if (!usedIds.contains(sourceId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "本次回答没有使用这条记忆");
        }
    }

    private void verifyOwnedTarget(Long userId, MemoryTargetFeedbackSource source, Long sourceId) {
        boolean owned = source == MemoryTargetFeedbackSource.MEMORY_ITEM
                ? itemMapper.findActiveOwned(userId, sourceId) != null
                : episodeMapper.findOwned(userId, sourceId) != null;
        if (!owned) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "记忆记录不存在");
    }

    private MemoryTargetFeedback findForUpdate(Long userId, String traceId,
                                               MemoryTargetFeedbackSource source, Long sourceId) {
        return source == MemoryTargetFeedbackSource.MEMORY_ITEM
                ? feedbackMapper.findMemoryItemForUpdate(userId, traceId, sourceId)
                : feedbackMapper.findEpisodeForUpdate(userId, traceId, sourceId);
    }

    private boolean updateIfChanged(MemoryTargetFeedback feedback, Long userId,
                                    MemoryFeedbackType type, LocalDateTime now) {
        if (type.name().equals(feedback.getFeedbackType())) return false;
        if (feedbackMapper.updateOwned(feedback.getId(), userId, type.name(), now) != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "反馈状态已变化，请刷新后重试");
        }
        feedback.setFeedbackType(type.name());
        feedback.setUpdatedAt(now);
        return true;
    }

    private List<Long> readIds(String json) {
        if (!StringUtils.hasText(json)) return List.of();
        try {
            return objectMapper.readValue(json, ID_LIST);
        } catch (JsonProcessingException ignored) {
            return List.of();
        }
    }

    private String itemDetail(MemoryItem item) {
        List<String> details = new ArrayList<>();
        if (StringUtils.hasText(item.getPreference())) details.add(item.getPreference());
        if (StringUtils.hasText(item.getMemoryType())) details.add(item.getMemoryType());
        return String.join(" · ", details);
    }

    private String key(String sourceKind, Long sourceId) {
        return sourceKind + ":" + sourceId;
    }

    private MemoryTargetFeedbackResponse response(MemoryTargetFeedback feedback,
                                                  MemoryTargetFeedbackSource source,
                                                  Long sourceId,
                                                  boolean updated) {
        return new MemoryTargetFeedbackResponse(feedback.getId(), feedback.getTraceId(), source, sourceId,
                MemoryFeedbackType.valueOf(feedback.getFeedbackType()), updated,
                feedback.getCreatedAt(), feedback.getUpdatedAt());
    }
}
