package com.example.food.agent.state;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryAgentEventStoreTest {

    private final InMemoryAgentEventStore store = new InMemoryAgentEventStore(new ObjectMapper());

    @Test
    void assignsMonotonicSequenceAndReplaysOnlyEventsAfterTheCursor() {
        AgentEvent first = store.append("run-1", "conversation.ready", Map.of("runId", "run-1"));
        AgentEvent second = store.append("run-1", "message.delta", Map.of("content", "你好"));
        store.append("run-2", "message.delta", Map.of("content", "隔离"));

        assertThat(first.sequence()).isEqualTo(1L);
        assertThat(second.sequence()).isEqualTo(2L);
        assertThat(store.latestSequence("run-1")).isEqualTo(2L);
        assertThat(store.findAfter("run-1", 1L, 100))
                .extracting(AgentEvent::sequence)
                .containsExactly(2L);
        assertThat(store.findAfter("run-2", 0L, 100)).hasSize(1);
    }

    @Test
    void appliesReplayLimitWithoutChangingTheStoredCursor() {
        for (int index = 0; index < 3; index++) {
            store.append("run-limit", "status", Map.of("index", index));
        }

        List<AgentEvent> page = store.findAfter("run-limit", 0L, 2);

        assertThat(page).hasSize(2);
        assertThat(page.get(0).sequence()).isEqualTo(1L);
        assertThat(store.latestSequence("run-limit")).isEqualTo(3L);
    }
}
