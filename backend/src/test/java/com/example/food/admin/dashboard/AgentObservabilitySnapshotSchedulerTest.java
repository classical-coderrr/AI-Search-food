package com.example.food.admin.dashboard;

import com.example.food.admin.dashboard.dto.AdminAgentObservabilityResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentObservabilitySnapshotSchedulerTest {

    @Test
    void persistsInitialSampleThenOnlyPersistsCounterDelta() {
        AdminAgentObservabilityService service = mock(AdminAgentObservabilityService.class);
        AgentMetricSnapshotMapper snapshotMapper = mock(AgentMetricSnapshotMapper.class);
        AgentObservabilityAlertService alertService = mock(AgentObservabilityAlertService.class);
        MockEnvironment environment = new MockEnvironment()
                .withProperty("app.agent.observability.persistence.retention", "P30D");
        AgentObservabilitySnapshotScheduler scheduler = new AgentObservabilitySnapshotScheduler(
                service,
                snapshotMapper,
                alertService,
                environment,
                Clock.fixed(Instant.parse("2026-09-21T08:00:00Z"), ZoneOffset.UTC)
        );

        when(service.currentSnapshot())
                .thenReturn(response(5, 3, 2, 1, 10, 2, 0))
                .thenReturn(response(7, 4, 2, 1, 15, 3, 1));

        scheduler.persist();
        scheduler.persist();

        var records = org.mockito.ArgumentCaptor.forClass(AgentMetricSnapshot.class);
        verify(snapshotMapper, org.mockito.Mockito.times(2)).insert(records.capture());
        assertThat(records.getAllValues().get(0).getRunsStarted()).isEqualTo(5L);
        assertThat(records.getAllValues().get(1).getRunsStarted()).isEqualTo(2L);
        assertThat(records.getAllValues().get(1).getEventsPersisted()).isEqualTo(5L);
        assertThat(records.getAllValues().get(1).getDuplicateWrites()).isEqualTo(1L);
        verify(alertService, org.mockito.Mockito.times(2)).evaluate(org.mockito.ArgumentMatchers.any());
        verify(snapshotMapper, org.mockito.Mockito.times(2)).deleteBefore(org.mockito.ArgumentMatchers.any());
    }

    private AdminAgentObservabilityResponse response(
            long started,
            long completed,
            long failed,
            long recovered,
            long eventsPersisted,
            long duplicateWrites,
            long durationSamples
    ) {
        return new AdminAgentObservabilityResponse(
                Instant.parse("2026-09-21T08:00:00Z"),
                new AdminAgentObservabilityResponse.Metrics(
                        started, completed, failed, recovered, eventsPersisted, 0,
                        duplicateWrites, durationSamples, 120D,
                        (double) completed / Math.max(1, started),
                        (double) recovered / Math.max(1, started),
                        (double) duplicateWrites / Math.max(1, eventsPersisted)
                ),
                new AdminAgentObservabilityResponse.RecoveryConfig(false, "PT5M", "PT5M", "PT10M", "redis"),
                new AdminAgentObservabilityResponse.PersistenceConfig(true, "PT5M", "P30D", 5, 0.2D, 0.2D, 0.1D),
                java.util.List.of(),
                java.util.List.of()
        );
    }
}
