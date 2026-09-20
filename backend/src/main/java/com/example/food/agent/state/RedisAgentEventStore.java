package com.example.food.agent.state;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Component
@ConditionalOnProperty(name = "app.agent.state-store", havingValue = "redis", matchIfMissing = true)
public class RedisAgentEventStore implements AgentEventStore {

    private static final String EVENT_SEQUENCE_PREFIX = "agent:event-seq:";
    private static final String EVENT_LIST_PREFIX = "agent:events:";
    private static final Duration EVENT_TTL = Duration.ofDays(7);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public RedisAgentEventStore(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    @Override
    public AgentEvent append(String runId, String event, Object data) {
        long sequence = requireSequence(redis.opsForValue().increment(EVENT_SEQUENCE_PREFIX + runId));
        Instant createdAt = Instant.now();
        AgentEvent envelope = new AgentEvent(runId, sequence, event, writeJson(data), createdAt);
        try {
            redis.opsForList().rightPush(EVENT_LIST_PREFIX + runId, objectMapper.writeValueAsString(envelope));
            redis.expire(EVENT_SEQUENCE_PREFIX + runId, EVENT_TTL);
            redis.expire(EVENT_LIST_PREFIX + runId, EVENT_TTL);
            return envelope;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Agent SSE 事件序列化失败", exception);
        }
    }

    @Override
    public List<AgentEvent> findAfter(String runId, long afterSequence, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 1000));
        List<String> values = redis.opsForList().range(EVENT_LIST_PREFIX + runId, 0, -1);
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<AgentEvent> result = new ArrayList<>();
        for (String value : values) {
            try {
                AgentEvent event = objectMapper.readValue(value, AgentEvent.class);
                if (event.sequence() > afterSequence) {
                    result.add(event);
                    if (result.size() >= safeLimit) {
                        break;
                    }
                }
            } catch (JsonProcessingException exception) {
                throw new IllegalStateException("Agent SSE 事件解析失败", exception);
            }
        }
        return result.stream()
                .sorted(Comparator.comparingLong(AgentEvent::sequence))
                .toList();
    }

    @Override
    public long latestSequence(String runId) {
        return requireSequence(redis.opsForValue().get(EVENT_SEQUENCE_PREFIX + runId));
    }

    private long requireSequence(Long value) {
        return value == null ? 0L : value;
    }

    private long requireSequence(String value) {
        if (value == null || value.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("Agent SSE 事件序号损坏", exception);
        }
    }

    private String writeJson(Object data) {
        try {
            return objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Agent SSE 事件数据序列化失败", exception);
        }
    }
}
