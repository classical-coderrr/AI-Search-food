package com.example.food.agent.state;

import com.example.food.agent.AgentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Finds runs whose worker disappeared and asks AgentService to continue from
 * the last durable checkpoint. The original SSE connection is not resumed;
 * the run itself is resumed and can be queried/reconnected by runId.
 */
@Component
@ConditionalOnProperty(name = "app.agent.recovery.enabled", havingValue = "true")
@EnableConfigurationProperties(AgentRecoveryProperties.class)
public class AgentRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(AgentRecoveryService.class);

    private final AgentRunStore runStore;
    private final AgentService agentService;
    private final Duration staleAfter;
    private final Duration leaseDuration;
    private static final Duration RECOVERY_INSTANCE_TTL = Duration.ofSeconds(90);
    private final String instanceId = UUID.randomUUID().toString();

    public AgentRecoveryService(
            AgentRunStore runStore,
            AgentService agentService,
            AgentRecoveryProperties properties
    ) {
        this.runStore = runStore;
        this.agentService = agentService;
        this.staleAfter = properties.staleAfter();
        this.leaseDuration = properties.leaseDuration();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverAfterStartup() {
        recoverStaleRuns();
    }

    @Scheduled(fixedDelayString = "${app.agent.recovery.scan-delay:PT30S}")
    public void recoverStaleRuns() {
        Instant staleBefore = Instant.now().minus(staleAfter);
        String owner = "recovery:" + instanceId + ":" + UUID.randomUUID();
        try {
            runStore.registerRecoveryInstance(instanceId, RECOVERY_INSTANCE_TTL);
            for (AgentRun run : runStore.findRecoverable(staleBefore)) {
                boolean acquired = runStore.tryAcquireLease(run.runId(), owner, leaseDuration);
                if (!acquired) {
                    // A SIGKILL cannot release the worker lease. Once the run
                    // is stale, atomically take over a non-recovery lease so
                    // recovery is not delayed until the original TTL expires.
                    acquired = runStore.tryTakeoverLease(run.runId(), owner, leaseDuration);
                }
                if (!acquired) {
                    continue;
                }
                try {
                    log.info("Recovering agent run {}, status={}, nextNode={}",
                            run.runId(), run.status(), run.nextNode());
                    agentService.resume(run.runId(), owner);
                } catch (RuntimeException exception) {
                    log.warn("Agent run recovery failed, runId={}", run.runId(), exception);
                } finally {
                    runStore.releaseLease(run.runId(), owner);
                }
            }
        } catch (RuntimeException exception) {
            // Redis may be temporarily unavailable while the application is starting.
            // The next scheduled scan will retry without preventing the app from booting.
            log.warn("Agent recovery scan unavailable", exception);
        }
    }
}
