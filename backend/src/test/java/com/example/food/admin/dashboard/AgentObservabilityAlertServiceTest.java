package com.example.food.admin.dashboard;

import com.example.food.admin.dashboard.dto.AdminAgentObservabilityResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentObservabilityAlertServiceTest {

    @Test
    void opensAlertWhenFailureRateExceedsThresholdAndResolvesItAfterRecovery() {
        AgentObservabilityAlertMapper mapper = mock(AgentObservabilityAlertMapper.class);
        MockEnvironment environment = new MockEnvironment()
                .withProperty("app.agent.observability.alerts.enabled", "true")
                .withProperty("app.agent.observability.alerts.minimum-samples", "5")
                .withProperty("app.agent.observability.alerts.failure-rate-threshold", "0.2")
                .withProperty("app.agent.observability.alerts.recovery-rate-threshold", "0.8")
                .withProperty("app.agent.observability.alerts.duplicate-write-rate-threshold", "0.9");
        AgentObservabilityAlertService service = new AgentObservabilityAlertService(
                mapper,
                environment,
                Clock.fixed(Instant.parse("2026-09-21T08:00:00Z"), ZoneOffset.UTC)
        );

        when(mapper.findByDedupeKey("AGENT_OBSERVABILITY|FAILURE_RATE")).thenReturn(null);
        service.evaluate(response(10, 6, 4, 0, 10, 0));

        var inserted = org.mockito.ArgumentCaptor.forClass(AgentObservabilityAlert.class);
        verify(mapper).insert(inserted.capture());
        assertThat(inserted.getValue().getAlertType()).isEqualTo(AgentObservabilityAlertService.FAILURE_RATE);
        assertThat(inserted.getValue().getStatus()).isEqualTo(AgentObservabilityAlertService.OPEN);

        AgentObservabilityAlert existing = inserted.getValue();
        existing.setId(1L);
        existing.setStatus(AgentObservabilityAlertService.OPEN);
        when(mapper.findByDedupeKey("AGENT_OBSERVABILITY|FAILURE_RATE")).thenReturn(existing);
        service.evaluate(response(10, 10, 0, 0, 10, 0));

        verify(mapper).updateById(existing);
        assertThat(existing.getStatus()).isEqualTo(AgentObservabilityAlertService.RESOLVED);
        verify(mapper, org.mockito.Mockito.times(1)).insert(any(AgentObservabilityAlert.class));
    }

    private AdminAgentObservabilityResponse response(
            long started,
            long completed,
            long failed,
            long recovered,
            long eventsPersisted,
            long duplicateWrites
    ) {
        return new AdminAgentObservabilityResponse(
                Instant.parse("2026-09-21T08:00:00Z"),
                new AdminAgentObservabilityResponse.Metrics(
                        started, completed, failed, recovered,
                        eventsPersisted, 0, duplicateWrites, 0,
                        0D, (double) completed / Math.max(1, started),
                        (double) recovered / Math.max(1, started),
                        (double) duplicateWrites / Math.max(1, eventsPersisted)
                ),
                new AdminAgentObservabilityResponse.RecoveryConfig(false, "PT5M", "PT5M", "PT10M", "redis"),
                new AdminAgentObservabilityResponse.PersistenceConfig(true, "PT5M", "P30D", 5, 0.2D, 0.8D, 0.9D),
                java.util.List.of(),
                java.util.List.of()
        );
    }
}
