package com.example.food.agent.state;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Component
@ConditionalOnProperty(name = "app.agent.state-store", havingValue = "redis", matchIfMissing = true)
public class RedisAgentRunStore implements AgentRunStore {

    private static final String ACTIVE_RUNS_KEY = "agent:runs:active";
    private static final String RUN_KEY_PREFIX = "agent:run:";
    private static final String CHECKPOINT_KEY_PREFIX = "agent:checkpoint:";
    private static final String STEPS_KEY_PREFIX = "agent:steps:";
    private static final String LEASE_KEY_PREFIX = "agent:lease:";
    private static final String RECOVERY_INSTANCE_KEY_PREFIX = "agent:recovery:instance:";
    private static final Duration RUN_TTL = Duration.ofDays(7);
    private static final Duration CHECKPOINT_TTL = Duration.ofDays(7);
    private static final RedisScript<Long> TAKEOVER_LEASE_SCRIPT = RedisScript.of("""
            local current = redis.call('get', KEYS[1])
            if current and string.sub(current, 1, 9) == 'recovery:' then
                local instanceId = string.match(current, '^recovery:([^:]+):')
                if instanceId and redis.call('exists', ARGV[3] .. instanceId) == 1 then
                    return 0
                end
            end
            redis.call('psetex', KEYS[1], ARGV[2], ARGV[1])
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public RedisAgentRunStore(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    @Override
    public void create(AgentRun run, AgentCheckpoint checkpoint) {
        saveRun(run);
        saveCheckpoint(checkpoint);
    }

    @Override
    public Optional<AgentRun> findRun(String runId) {
        return read(RUN_KEY_PREFIX + runId, AgentRun.class);
    }

    @Override
    public Optional<AgentCheckpoint> findCheckpoint(String runId) {
        return read(CHECKPOINT_KEY_PREFIX + runId, AgentCheckpoint.class);
    }

    @Override
    public void saveRun(AgentRun run) {
        write(RUN_KEY_PREFIX + run.runId(), run, RUN_TTL);
        if (isActive(run.status())) {
            redis.opsForSet().add(ACTIVE_RUNS_KEY, run.runId());
        } else {
            redis.opsForSet().remove(ACTIVE_RUNS_KEY, run.runId());
        }
    }

    @Override
    public void saveCheckpoint(AgentCheckpoint checkpoint) {
        write(CHECKPOINT_KEY_PREFIX + checkpoint.runId(), checkpoint, CHECKPOINT_TTL);
    }

    @Override
    public void appendStep(AgentStep step) {
        write(STEPS_KEY_PREFIX + step.runId() + ":" + step.stepNo(), step, RUN_TTL);
    }

    @Override
    public List<AgentRun> findRecoverable(Instant staleBefore) {
        Set<String> ids = redis.opsForSet().members(ACTIVE_RUNS_KEY);
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<AgentRun> result = new ArrayList<>();
        for (String id : ids) {
            findRun(id).filter(run -> run.recoverable()
                            && (run.status() == AgentStatus.RUNNING || run.status() == AgentStatus.RECOVERING)
                            && run.updatedAt() != null
                            && run.updatedAt().isBefore(staleBefore))
                    .ifPresent(result::add);
        }
        return result.stream()
                .sorted(Comparator.comparing(AgentRun::updatedAt))
                .toList();
    }

    @Override
    public Optional<AgentRun> findActiveForConversation(Long userId, Long conversationId) {
        Set<String> ids = redis.opsForSet().members(ACTIVE_RUNS_KEY);
        if (ids == null || ids.isEmpty()) {
            return Optional.empty();
        }
        return ids.stream()
                .map(this::findRun)
                .flatMap(Optional::stream)
                .filter(run -> java.util.Objects.equals(run.userId(), userId)
                        && java.util.Objects.equals(run.conversationId(), conversationId)
                        && isActive(run.status()))
                .max(Comparator.comparing(AgentRun::updatedAt,
                        Comparator.nullsFirst(Comparator.naturalOrder())));
    }

    @Override
    public boolean tryAcquireLease(String runId, String owner, Duration leaseDuration) {
        Boolean acquired = redis.opsForValue().setIfAbsent(
                LEASE_KEY_PREFIX + runId,
                owner,
                leaseDuration
        );
        return Boolean.TRUE.equals(acquired);
    }

    @Override
    public boolean tryTakeoverLease(String runId, String owner, Duration leaseDuration) {
        Long acquired = redis.execute(
                TAKEOVER_LEASE_SCRIPT,
                List.of(LEASE_KEY_PREFIX + runId),
                owner,
                Long.toString(Math.max(1L, leaseDuration.toMillis())),
                RECOVERY_INSTANCE_KEY_PREFIX
        );
        return Long.valueOf(1L).equals(acquired);
    }

    @Override
    public void registerRecoveryInstance(String instanceId, Duration ttl) {
        redis.opsForValue().set(RECOVERY_INSTANCE_KEY_PREFIX + instanceId, "alive", ttl);
    }

    @Override
    public void releaseLease(String runId, String owner) {
        String key = LEASE_KEY_PREFIX + runId;
        String current = redis.opsForValue().get(key);
        if (owner.equals(current)) {
            redis.delete(key);
        }
    }

    private boolean isActive(AgentStatus status) {
        return status == AgentStatus.RUNNING
                || status == AgentStatus.RECOVERING
                || status == AgentStatus.WAITING_CONFIRMATION;
    }

    private <T> Optional<T> read(String key, Class<T> type) {
        String value = redis.opsForValue().get(key);
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(value, type));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Agent Redis 状态解析失败: " + key, exception);
        }
    }

    private void write(String key, Object value, Duration ttl) {
        try {
            redis.opsForValue().set(key, objectMapper.writeValueAsString(value), ttl);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Agent Redis 状态序列化失败: " + key, exception);
        }
    }
}
