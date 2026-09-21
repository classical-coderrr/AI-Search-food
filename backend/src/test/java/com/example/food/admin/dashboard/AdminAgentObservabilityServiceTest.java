package com.example.food.admin.dashboard;

import com.example.food.admin.dashboard.dto.AdminAgentObservabilityResponse;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class AdminAgentObservabilityServiceTest {

    @Test
    void aggregatesAgentCountersTimersAndSafeRecoveryConfiguration() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        registry.counter("agent.runs.started").increment(4);
        registry.counter("agent.runs.completed").increment(3);
        registry.counter("agent.runs.failed").increment(1);
        registry.counter("agent.runs.recovered").increment(1);
        registry.counter("agent.events.persisted").increment(20);
        registry.counter("agent.events.replayed").increment(5);
        registry.counter("agent.writes.duplicate").increment(2);
        registry.timer("agent.runs.duration").record(Duration.ofMillis(100));
        registry.timer("agent.runs.duration").record(Duration.ofMillis(300));

        MockEnvironment environment = new MockEnvironment()
                .withProperty("app.agent.recovery.enabled", "true")
                .withProperty("app.agent.recovery.scan-delay", "PT2S")
                .withProperty("app.agent.recovery.stale-after", "PT5S")
                .withProperty("app.agent.recovery.lease-duration", "PT10M")
                .withProperty("app.agent.state-store", "redis");
        Clock clock = Clock.fixed(Instant.parse("2026-09-21T08:00:00Z"), ZoneOffset.UTC);

        AdminAgentObservabilityResponse response = new AdminAgentObservabilityService(
                registry,
                environment,
                clock
        ).snapshot();

        assertThat(response.generatedAt()).isEqualTo(Instant.parse("2026-09-21T08:00:00Z"));
        assertThat(response.metrics().runsStarted()).isEqualTo(4);
        assertThat(response.metrics().runsCompleted()).isEqualTo(3);
        assertThat(response.metrics().runsRecovered()).isEqualTo(1);
        assertThat(response.metrics().durationSamples()).isEqualTo(2);
        assertThat(response.metrics().averageRunDurationMs()).isEqualTo(200D);
        assertThat(response.metrics().successRate()).isEqualTo(0.75D);
        assertThat(response.metrics().recoveryRate()).isEqualTo(0.25D);
        assertThat(response.metrics().duplicateWriteRate()).isEqualTo(0.1D);
        assertThat(response.recovery().enabled()).isTrue();
        assertThat(response.recovery().scanDelay()).isEqualTo("PT2S");
        assertThat(response.recovery().stateStore()).isEqualTo("redis");
        assertThat(response.persistence().enabled()).isTrue();
        assertThat(response.persistence().snapshotInterval()).isEqualTo("PT5M");
        assertThat(response.history()).isEmpty();
        assertThat(response.alerts()).isEmpty();
    }
}
