package com.example.food.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
public class MemoryFeedbackService {
    private static final TypeReference<List<Long>> ID_LIST = new TypeReference<>() { };

    private final MemoryFeedbackMapper feedbackMapper;
    private final MemoryRetrievalTraceMapper traceMapper;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public MemoryFeedbackService(MemoryFeedbackMapper feedbackMapper,
                                 MemoryRetrievalTraceMapper traceMapper,
                                 ObjectMapper objectMapper,
                                 Clock clock) {
        this.feedbackMapper = feedbackMapper;
        this.traceMapper = traceMapper;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public MemoryFeedbackResponse submit(Long userId, MemoryFeedbackRequest request) {
        if (userId == null || userId <= 0 || request == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "需要登录后提交记忆反馈");
        }
        String traceId = request.traceId().trim();
        MemoryRetrievalTrace trace = traceMapper.findOwnedByTraceId(userId, traceId);
        if (trace == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "记忆追踪记录不存在");
        }
        if (!containsPersonalContext(trace)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "本次回答未注入个人记忆，无法提交记忆反馈");
        }

        LocalDateTime now = LocalDateTime.now(clock);
        MemoryFeedback feedback = feedbackMapper.findByTraceId(traceId);
        boolean updated = false;
        if (feedback == null) {
            feedback = new MemoryFeedback();
            feedback.setTraceId(traceId);
            feedback.setUserId(userId);
            feedback.setFeedbackType(request.feedbackType().name());
            feedback.setCreatedAt(now);
            feedback.setUpdatedAt(now);
            try {
                feedbackMapper.insert(feedback);
            } catch (DuplicateKeyException concurrentSubmission) {
                feedback = feedbackMapper.findByTraceIdForUpdate(traceId);
                if (feedback == null || !userId.equals(feedback.getUserId())) {
                    throw concurrentSubmission;
                }
                updated = updateIfChanged(feedback, userId, request.feedbackType(), now);
            }
        } else {
            if (!userId.equals(feedback.getUserId())) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "记忆追踪记录不存在");
            }
            updated = updateIfChanged(feedback, userId, request.feedbackType(), now);
        }
        return toResponse(feedback, request.feedbackType(), updated);
    }

    public MemoryFeedbackStatusResponse status(Long userId, String requestedTraceId) {
        if (userId == null || userId <= 0) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "需要登录后查看记忆反馈");
        }
        if (requestedTraceId == null || requestedTraceId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "记忆追踪编号不能为空");
        }
        String traceId = requestedTraceId.trim();
        if (traceId.length() > 64) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "记忆追踪编号无效");
        }
        MemoryRetrievalTrace trace = traceMapper.findOwnedByTraceId(userId, traceId);
        if (trace == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "记忆追踪记录不存在");
        }
        if (!containsPersonalContext(trace)) {
            return new MemoryFeedbackStatusResponse(false, null, null);
        }
        MemoryFeedback feedback = feedbackMapper.findByTraceId(traceId);
        if (feedback == null) {
            return new MemoryFeedbackStatusResponse(true, null, null);
        }
        if (!userId.equals(feedback.getUserId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "记忆追踪记录不存在");
        }
        return new MemoryFeedbackStatusResponse(true,
                MemoryFeedbackType.valueOf(feedback.getFeedbackType()), feedback.getUpdatedAt());
    }

    public Map<String, Object> summarizeSince(LocalDateTime fromTime) {
        Map<String, Object> summary = feedbackMapper.summarizeSince(fromTime);
        return summary == null ? Map.of() : summary;
    }

    private boolean updateIfChanged(MemoryFeedback feedback,
                                    Long userId,
                                    MemoryFeedbackType type,
                                    LocalDateTime now) {
        if (type.name().equals(feedback.getFeedbackType())) return false;
        if (feedbackMapper.updateOwned(feedback.getId(), userId, type.name(), now) != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "反馈状态已变化，请刷新后重试");
        }
        feedback.setFeedbackType(type.name());
        feedback.setUpdatedAt(now);
        return true;
    }

    private boolean containsPersonalContext(MemoryRetrievalTrace trace) {
        return !readIds(trace.getUsedMemoryItemIdsJson()).isEmpty()
                || !readIds(trace.getUsedEpisodeIdsJson()).isEmpty()
                || containsSection(trace.getContextSectionsJson(), "STRUCTURED_PROFILE")
                || containsSection(trace.getContextSectionsJson(), "PERSONALIZED_SKILL");
    }

    private List<Long> readIds(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, ID_LIST);
        } catch (JsonProcessingException ignored) {
            return List.of();
        }
    }

    private boolean containsSection(String json, String expected) {
        if (json == null || json.isBlank()) return false;
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() { }).stream()
                    .anyMatch(expected::equals);
        } catch (JsonProcessingException ignored) {
            return false;
        }
    }

    private MemoryFeedbackResponse toResponse(MemoryFeedback feedback,
                                              MemoryFeedbackType type,
                                              boolean updated) {
        return new MemoryFeedbackResponse(feedback.getId(), feedback.getTraceId(), type,
                updated, feedback.getCreatedAt(), feedback.getUpdatedAt());
    }
}
