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

    /**
     * Take over a lease left by a stale worker. Recovery owners are protected
     * from replacing one another so only one recovery attempt can proceed.
     */
    boolean tryTakeoverLease(String runId, String owner, Duration leaseDuration);

    /**
     * Publish a short-lived marker for the current recovery process. A new
     * process can distinguish a live recovery owner from one left behind by a
     * crashed process.
     */
    void registerRecoveryInstance(String instanceId, Duration ttl);

    void releaseLease(String runId, String owner);
}
