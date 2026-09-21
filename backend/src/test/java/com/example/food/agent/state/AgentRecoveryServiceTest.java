package com.example.food.agent.state;

import com.example.food.agent.AgentService;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AgentRecoveryServiceTest {

    private static final Duration STALE_AFTER = Duration.ofMinutes(5);
    private static final Duration LEASE_DURATION = Duration.ofMinutes(10);

    @Test
    void claimsLeaseResumesRunAndReleasesLease() {
        AgentRunStore runStore = mock(AgentRunStore.class);
        AgentService agentService = mock(AgentService.class);
        AgentRun staleRun = run("stale-run", AgentStatus.RUNNING);
        when(runStore.findRecoverable(any(Instant.class))).thenReturn(List.of(staleRun));
        when(runStore.tryAcquireLease(eq("stale-run"), anyString(), eq(LEASE_DURATION))).thenReturn(true);

        AgentRecoveryService service = service(runStore, agentService);
        service.recoverStaleRuns();

        verify(agentService).resume(eq("stale-run"), anyString());
        verify(runStore).releaseLease(eq("stale-run"), anyString());
    }

    @Test
    void skipsRunWhenAnotherWorkerOwnsLease() {
        AgentRunStore runStore = mock(AgentRunStore.class);
        AgentService agentService = mock(AgentService.class);
        AgentRun staleRun = run("leased-run", AgentStatus.RECOVERING);
        when(runStore.findRecoverable(any(Instant.class))).thenReturn(List.of(staleRun));
        when(runStore.tryAcquireLease(eq("leased-run"), anyString(), eq(LEASE_DURATION))).thenReturn(false);

        service(runStore, agentService).recoverStaleRuns();

        verifyNoInteractions(agentService);
    }

    @Test
    void takesOverStaleWorkerLeaseAfterProcessCrash() {
        AgentRunStore runStore = mock(AgentRunStore.class);
        AgentService agentService = mock(AgentService.class);
        AgentRun staleRun = run("crashed-worker-run", AgentStatus.RUNNING);
        when(runStore.findRecoverable(any(Instant.class))).thenReturn(List.of(staleRun));
        when(runStore.tryAcquireLease(eq("crashed-worker-run"), anyString(), eq(LEASE_DURATION)))
                .thenReturn(false);
        when(runStore.tryTakeoverLease(eq("crashed-worker-run"), anyString(), eq(LEASE_DURATION)))
                .thenReturn(true);

        service(runStore, agentService).recoverStaleRuns();

        verify(agentService).resume(eq("crashed-worker-run"), anyString());
        verify(runStore).releaseLease(eq("crashed-worker-run"), anyString());
    }

    @Test
    void releasesLeaseAndContinuesWhenOneRunFails() {
        AgentRunStore runStore = mock(AgentRunStore.class);
        AgentService agentService = mock(AgentService.class);
        AgentRun failedRun = run("failed-run", AgentStatus.RUNNING);
        AgentRun nextRun = run("next-run", AgentStatus.RUNNING);
        when(runStore.findRecoverable(any(Instant.class))).thenReturn(List.of(failedRun, nextRun));
        when(runStore.tryAcquireLease(anyString(), anyString(), eq(LEASE_DURATION))).thenReturn(true);
        doThrow(new IllegalStateException("simulated recovery failure"))
                .when(agentService).resume(eq("failed-run"), anyString());

        service(runStore, agentService).recoverStaleRuns();

        verify(runStore).releaseLease(eq("failed-run"), anyString());
        verify(agentService).resume(eq("next-run"), anyString());
        verify(runStore).releaseLease(eq("next-run"), anyString());
    }

    @Test
    void toleratesStoreOutageSoScheduledScanCanRetry() {
        AgentRunStore runStore = mock(AgentRunStore.class);
        AgentService agentService = mock(AgentService.class);
        when(runStore.findRecoverable(any(Instant.class)))
                .thenThrow(new IllegalStateException("simulated redis outage"));

        service(runStore, agentService).recoverStaleRuns();

        verifyNoInteractions(agentService);
    }

    @Test
    void startupRecoveryUsesTheSameScanPath() {
        AgentRunStore runStore = mock(AgentRunStore.class);
        AgentService agentService = mock(AgentService.class);
        when(runStore.findRecoverable(any(Instant.class))).thenReturn(List.of());

        service(runStore, agentService).recoverAfterStartup();

        verify(runStore).findRecoverable(any(Instant.class));
        verifyNoInteractions(agentService);
    }

    private AgentRecoveryService service(AgentRunStore runStore, AgentService agentService) {
        return new AgentRecoveryService(
                runStore,
                agentService,
                new AgentRecoveryProperties(true, STALE_AFTER, LEASE_DURATION)
        );
    }

    private AgentRun run(String runId, AgentStatus status) {
        Instant now = Instant.now().minus(STALE_AFTER.plusSeconds(1));
        return new AgentRun(
                runId,
                1L,
                2L,
                status,
                AgentNode.MODEL_DECISION,
                AgentNode.MODEL_DECISION,
                true,
                now,
                now,
                now,
                null,
                null
        );
    }
}
