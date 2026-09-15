package com.example.food.agent.state;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryAgentRunStoreTest {

    @Test
    void findsOnlyStaleRecoverableRunsAndHonoursLeases() {
        InMemoryAgentRunStore store = new InMemoryAgentRunStore();
        Instant now = Instant.now();
        AgentRun stale = run("stale", AgentStatus.RUNNING, true, now.minus(Duration.ofMinutes(10)));
        AgentRun active = run("active", AgentStatus.RUNNING, true, now);
        AgentRun attachment = run("attachment", AgentStatus.RUNNING, false, now.minus(Duration.ofMinutes(10)));
        AgentRun completed = run("completed", AgentStatus.COMPLETED, true, now.minus(Duration.ofMinutes(10)));
        store.create(stale, checkpoint(stale, now.minus(Duration.ofMinutes(10))));
        store.create(active, checkpoint(active, now));
        store.create(attachment, checkpoint(attachment, now.minus(Duration.ofMinutes(10))));
        store.create(completed, checkpoint(completed, now.minus(Duration.ofMinutes(10))));

        assertThat(store.findRecoverable(now.minus(Duration.ofMinutes(5))))
                .extracting(AgentRun::runId)
                .containsExactly("stale");
        assertThat(store.findActiveForConversation(99L, 2L))
                .isEmpty();
        store.saveRun(new AgentRun(
                "active-conversation", 1L, 2L, AgentStatus.RUNNING,
                AgentNode.MODEL_DECISION, AgentNode.MODEL_DECISION, true,
                now, now, now, null, null
        ));
        assertThat(store.findActiveForConversation(1L, 2L))
                .get()
                .extracting(AgentRun::runId)
                .isEqualTo("active-conversation");
        assertThat(store.tryAcquireLease("stale", "worker-a", Duration.ofMinutes(1))).isTrue();
        assertThat(store.tryAcquireLease("stale", "worker-b", Duration.ofMinutes(1))).isFalse();
        store.releaseLease("stale", "worker-b");
        assertThat(store.tryAcquireLease("stale", "worker-b", Duration.ofMinutes(1))).isFalse();
        store.releaseLease("stale", "worker-a");
        assertThat(store.tryAcquireLease("stale", "worker-b", Duration.ofMinutes(1))).isTrue();
    }

    private AgentRun run(String runId, AgentStatus status, boolean recoverable, Instant updatedAt) {
        return new AgentRun(
                runId, 1L, 2L, status, AgentNode.MODEL_DECISION, AgentNode.MODEL_DECISION,
                recoverable, updatedAt, updatedAt, updatedAt, null, null
        );
    }

    private AgentCheckpoint checkpoint(AgentRun run, Instant savedAt) {
        return new AgentCheckpoint(run.runId(), 0L, new AgentState(
                run.runId(), run.userId(), run.conversationId(), "生成晚餐", false,
                run.status(), run.currentNode(), run.nextNode(), 0, 0, 0,
                false, List.of(), List.of(), 0, null, savedAt
        ), savedAt);
    }
}
