package com.example.food.agent.state;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Development/test event store used when Redis is intentionally disabled. */
@Component
@ConditionalOnProperty(name = "app.agent.state-store", havingValue = "memory")
public class InMemoryAgentEventStore implements AgentEventStore {

    private final ObjectMapper objectMapper;
    private final Map<String, AtomicLong> sequences = new ConcurrentHashMap<>();
    private final Map<String, List<AgentEvent>> events = new ConcurrentHashMap<>();

    public InMemoryAgentEventStore(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public AgentEvent append(String runId, String event, Object data) {
        long sequence = sequences.computeIfAbsent(runId, ignored -> new AtomicLong()).incrementAndGet();
        AgentEvent envelope = new AgentEvent(runId, sequence, event, writeJson(data), Instant.now());
        List<AgentEvent> stored = events.computeIfAbsent(runId, ignored -> new ArrayList<>());
        synchronized (stored) {
            stored.add(envelope);
        }
        return envelope;
    }

    @Override
    public List<AgentEvent> findAfter(String runId, long afterSequence, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 1000));
        List<AgentEvent> stored = events.getOrDefault(runId, List.of());
        synchronized (stored) {
            return stored.stream()
                    .filter(event -> event.sequence() > afterSequence)
                    .sorted(Comparator.comparingLong(AgentEvent::sequence))
                    .limit(safeLimit)
                    .toList();
        }
    }

    @Override
    public long latestSequence(String runId) {
        return sequences.getOrDefault(runId, new AtomicLong()).get();
    }

    private String writeJson(Object data) {
        try {
            return objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Agent SSE 事件数据序列化失败", exception);
        }
    }
}
