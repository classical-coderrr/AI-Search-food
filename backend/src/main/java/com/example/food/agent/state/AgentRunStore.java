package com.example.food.agent.state;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface AgentRunStore {

    void create(AgentRun run, AgentCheckpoint checkpoint);

    Optional<AgentRun> findRun(String runId);

    Optional<AgentCheckpoint> findCheckpoint(String runId);

    void saveRun(AgentRun run);

    void saveCheckpoint(AgentCheckpoint checkpoint);

    void appendStep(AgentStep step);

    List<AgentRun> findRecoverable(Instant staleBefore);

    Optional<AgentRun> findActiveForConversation(Long userId, Long conversationId);

    boolean tryAcquireLease(String runId, String owner, Duration leaseDuration);

    void releaseLease(String runId, String owner);
}
