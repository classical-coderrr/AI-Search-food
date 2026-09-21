package com.example.food.admin.dashboard;

import com.example.food.admin.dashboard.dto.AdminAgentObservabilityResponse;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

@Service
public class AdminAgentObservabilityService {

    private static final String RUNS_STARTED = "agent.runs.started";
    private static final String RUNS_COMPLETED = "agent.runs.completed";
    private static final String RUNS_FAILED = "agent.runs.failed";
    private static final String RUNS_RECOVERED = "agent.runs.recovered";
    private static final String EVENTS_PERSISTED = "agent.events.persisted";
    private static final String EVENTS_REPLAYED = "agent.events.replayed";
    private static final String DUPLICATE_WRITES = "agent.writes.duplicate";
    private static final String RUN_DURATION = "agent.runs.duration";

    private final MeterRegistry meterRegistry;
    private final Environment environment;
    private final Clock clock;

    public AdminAgentObservabilityService(
            MeterRegistry meterRegistry,
            Environment environment,
            Clock clock
    ) {
        this.meterRegistry = Objects.requireNonNull(meterRegistry);
        this.environment = Objects.requireNonNull(environment);
        this.clock = Objects.requireNonNull(clock);
    }

    public AdminAgentObservabilityResponse snapshot() {
        long runsStarted = count(RUNS_STARTED);
        long runsCompleted = count(RUNS_COMPLETED);
        long runsFailed = count(RUNS_FAILED);
        long runsRecovered = count(RUNS_RECOVERED);
        long eventsPersisted = count(EVENTS_PERSISTED);
        long eventsReplayed = count(EVENTS_REPLAYED);
        long duplicateWrites = count(DUPLICATE_WRITES);
        Timer duration = meterRegistry.find(RUN_DURATION).timer();
        long durationSamples = duration == null ? 0L : duration.count();
        double averageRunDurationMs = durationSamples == 0
                ? 0D
                : duration.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS) / durationSamples;

        return new AdminAgentObservabilityResponse(
                Instant.now(clock),
                new AdminAgentObservabilityResponse.Metrics(
                        runsStarted,
                        runsCompleted,
                        runsFailed,
                        runsRecovered,
                        eventsPersisted,
                        eventsReplayed,
                        duplicateWrites,
                        durationSamples,
                        averageRunDurationMs,
                        ratio(runsCompleted, runsStarted),
                        ratio(runsRecovered, runsStarted),
                        ratio(duplicateWrites, eventsPersisted)
                ),
                new AdminAgentObservabilityResponse.RecoveryConfig(
                        property("app.agent.recovery.enabled", Boolean.class, false),
                        property("app.agent.recovery.scan-delay", "PT30S"),
                        property("app.agent.recovery.stale-after", "PT5M"),
                        property("app.agent.recovery.lease-duration", "PT10M"),
                        property("app.agent.state-store", "redis")
                )
        );
    }

    private long count(String name) {
        Counter counter = meterRegistry.find(name).counter();
        return counter == null ? 0L : Math.round(counter.count());
    }

    private double ratio(long numerator, long denominator) {
        return denominator <= 0 ? 0D : (double) numerator / denominator;
    }

    private String property(String key, String fallback) {
        return environment.getProperty(key, fallback);
    }

    private <T> T property(String key, Class<T> type, T fallback) {
        return environment.getProperty(key, type, fallback);
    }
}
