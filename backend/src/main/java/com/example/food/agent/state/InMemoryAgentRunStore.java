package com.example.food.agent.state;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Development/test fallback. Production should use Redis so state survives a
 * Spring Boot process restart.
 */
@Component
@ConditionalOnProperty(name = "app.agent.state-store", havingValue = "memory")
public class InMemoryAgentRunStore implements AgentRunStore {

    private final Map<String, AgentRun> runs = new ConcurrentHashMap<>();
    private final Map<String, AgentCheckpoint> checkpoints = new ConcurrentHashMap<>();
    private final Map<String, Map<Long, AgentStep>> steps = new ConcurrentHashMap<>();
    private final Map<String, Lease> leases = new ConcurrentHashMap<>();

    @Override
    public void create(AgentRun run, AgentCheckpoint checkpoint) {
        runs.put(run.runId(), run);
        checkpoints.put(checkpoint.runId(), checkpoint);
    }

    @Override
    public Optional<AgentRun> findRun(String runId) {
        return Optional.ofNullable(runs.get(runId));
    }

    @Override
    public Optional<AgentCheckpoint> findCheckpoint(String runId) {
        return Optional.ofNullable(checkpoints.get(runId));
    }

    @Override
    public void saveRun(AgentRun run) {
        runs.put(run.runId(), run);
    }

    @Override
    public void saveCheckpoint(AgentCheckpoint checkpoint) {
        checkpoints.put(checkpoint.runId(), checkpoint);
    }

    @Override
    public void appendStep(AgentStep step) {
        steps.computeIfAbsent(step.runId(), ignored -> new ConcurrentHashMap<>())
                .put(step.stepNo(), step);
    }

    @Override
    public List<AgentRun> findRecoverable(Instant staleBefore) {
        List<AgentRun> result = new ArrayList<>();
        for (AgentRun run : runs.values()) {
            if (run.recoverable()
                    && (run.status() == AgentStatus.RUNNING
                    || run.status() == AgentStatus.RECOVERING)
                    && run.updatedAt() != null
                    && run.updatedAt().isBefore(staleBefore)) {
                result.add(run);
            }
        }
        return result.stream()
                .sorted(Comparator.comparing(AgentRun::updatedAt))
                .toList();
    }

    @Override
    public Optional<AgentRun> findActiveForConversation(Long userId, Long conversationId) {
        return runs.values().stream()
                .filter(run -> java.util.Objects.equals(run.userId(), userId)
                        && java.util.Objects.equals(run.conversationId(), conversationId)
                        && (run.status() == AgentStatus.RUNNING
                        || run.status() == AgentStatus.RECOVERING
                        || run.status() == AgentStatus.WAITING_CONFIRMATION))
                .max(Comparator.comparing(AgentRun::updatedAt,
                        Comparator.nullsFirst(Comparator.naturalOrder())));
    }

    @Override
    public synchronized boolean tryAcquireLease(String runId, String owner, Duration leaseDuration) {
        Instant now = Instant.now();
        Lease current = leases.get(runId);
        if (current != null && current.expiresAt().isAfter(now) && !current.owner().equals(owner)) {
            return false;
        }
        leases.put(runId, new Lease(owner, now.plus(leaseDuration)));
        return true;
    }

    @Override
    public synchronized void releaseLease(String runId, String owner) {
        Lease current = leases.get(runId);
        if (current != null && current.owner().equals(owner)) {
            leases.remove(runId);
        }
    }

    private record Lease(String owner, Instant expiresAt) {
    }
}
