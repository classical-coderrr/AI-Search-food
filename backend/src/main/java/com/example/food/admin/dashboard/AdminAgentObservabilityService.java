package com.example.food.admin.dashboard;

import com.example.food.admin.dashboard.dto.AdminAgentObservabilityResponse;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
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
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final int MAX_HISTORY_POINTS = 2500;

    private final MeterRegistry meterRegistry;
    private final Environment environment;
    private final Clock clock;
    private final AgentMetricSnapshotMapper snapshotMapper;
    private final AgentObservabilityAlertMapper alertMapper;

    @Autowired
    public AdminAgentObservabilityService(
            MeterRegistry meterRegistry,
            Environment environment,
            Clock clock,
            AgentMetricSnapshotMapper snapshotMapper,
            AgentObservabilityAlertMapper alertMapper
    ) {
        this.meterRegistry = Objects.requireNonNull(meterRegistry);
        this.environment = Objects.requireNonNull(environment);
        this.clock = Objects.requireNonNull(clock);
        this.snapshotMapper = snapshotMapper;
        this.alertMapper = alertMapper;
    }

    public AdminAgentObservabilityService(
            MeterRegistry meterRegistry,
            Environment environment,
            Clock clock
    ) {
        this(meterRegistry, environment, clock, null, null);
    }

    public AdminAgentObservabilityResponse snapshot() {
        return snapshot("24h");
    }

    public AdminAgentObservabilityResponse snapshot(String range) {
        AdminAgentObservabilityResponse current = currentSnapshot();
        List<AdminAgentObservabilityResponse.HistoryPoint> history = history(range);
        return withCollections(current, history, recentAlerts());
    }

    public AdminAgentObservabilityResponse currentSnapshot() {
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
                ),
                persistenceConfig(),
                List.of(),
                recentAlerts()
        );
    }

    private AdminAgentObservabilityResponse withCollections(
            AdminAgentObservabilityResponse current,
            List<AdminAgentObservabilityResponse.HistoryPoint> history,
            List<AdminAgentObservabilityResponse.Alert> alerts
    ) {
        return new AdminAgentObservabilityResponse(
                current.generatedAt(),
                current.metrics(),
                current.recovery(),
                current.persistence(),
                history,
                alerts
        );
    }

    private List<AdminAgentObservabilityResponse.HistoryPoint> history(String range) {
        if (snapshotMapper == null) {
            return List.of();
        }
        LocalDateTime from = now().minus(rangeDuration(range));
        return snapshotList(snapshotMapper.findHistory(from, MAX_HISTORY_POINTS)).stream()
                .map(this::historyPoint)
                .toList();
    }

    private List<AdminAgentObservabilityResponse.Alert> recentAlerts() {
        if (alertMapper == null) {
            return List.of();
        }
        return alertList(alertMapper.findRecent(20)).stream()
                .map(this::alert)
                .toList();
    }

    private AdminAgentObservabilityResponse.HistoryPoint historyPoint(AgentMetricSnapshot snapshot) {
        return new AdminAgentObservabilityResponse.HistoryPoint(
                snapshot.getCapturedAt() == null ? null : snapshot.getCapturedAt().atZone(ZONE).toInstant(),
                snapshot.getInstanceId(),
                nonNegative(snapshot.getRunsStarted()),
                nonNegative(snapshot.getRunsCompleted()),
                nonNegative(snapshot.getRunsFailed()),
                nonNegative(snapshot.getRunsRecovered()),
                nonNegative(snapshot.getEventsPersisted()),
                nonNegative(snapshot.getEventsReplayed()),
                nonNegative(snapshot.getDuplicateWrites()),
                nonNegative(snapshot.getDurationSamples()),
                snapshot.getAverageRunDurationMs() == null ? 0D : Math.max(0D, snapshot.getAverageRunDurationMs().doubleValue())
        );
    }

    private AdminAgentObservabilityResponse.Alert alert(AgentObservabilityAlert alert) {
        return new AdminAgentObservabilityResponse.Alert(
                alert.getId(),
                alert.getAlertType(),
                alert.getSeverity(),
                alert.getStatus(),
                alert.getTitle(),
                alert.getMessage(),
                decimalValue(alert.getMetricValue()),
                decimalValue(alert.getThresholdValue()),
                toInstant(alert.getFirstSeenAt()),
                toInstant(alert.getLastSeenAt()),
                toInstant(alert.getResolvedAt())
        );
    }

    private AdminAgentObservabilityResponse.PersistenceConfig persistenceConfig() {
        return new AdminAgentObservabilityResponse.PersistenceConfig(
                property("app.agent.observability.persistence.enabled", Boolean.class, true),
                property("app.agent.observability.persistence.snapshot-interval", "PT5M"),
                property("app.agent.observability.persistence.retention", "P30D"),
                property("app.agent.observability.alerts.minimum-samples", Integer.class, 5),
                property("app.agent.observability.alerts.failure-rate-threshold", Double.class, 0.2D),
                property("app.agent.observability.alerts.recovery-rate-threshold", Double.class, 0.2D),
                property("app.agent.observability.alerts.duplicate-write-rate-threshold", Double.class, 0.1D)
        );
    }

    private Duration rangeDuration(String range) {
        return switch (range == null ? "24h" : range.trim().toLowerCase()) {
            case "7d" -> Duration.ofDays(7);
            case "30d" -> Duration.ofDays(30);
            default -> Duration.ofHours(24);
        };
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(Instant.now(clock), ZONE);
    }

    private Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.atZone(ZONE).toInstant();
    }

    private List<AgentMetricSnapshot> snapshotList(List<AgentMetricSnapshot> values) {
        return values == null ? List.of() : values;
    }

    private List<AgentObservabilityAlert> alertList(List<AgentObservabilityAlert> values) {
        return values == null ? List.of() : values;
    }

    private long nonNegative(Long value) {
        return value == null ? 0L : Math.max(0L, value);
    }

    private double decimalValue(java.math.BigDecimal value) {
        return value == null ? 0D : Math.max(0D, value.doubleValue());
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
