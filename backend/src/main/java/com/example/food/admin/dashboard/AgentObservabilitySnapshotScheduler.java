package com.example.food.admin.dashboard;

import com.example.food.admin.dashboard.dto.AdminAgentObservabilityResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;

@Component
@ConditionalOnProperty(
        name = "app.agent.observability.persistence.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class AgentObservabilitySnapshotScheduler {

    private static final Logger log = LoggerFactory.getLogger(AgentObservabilitySnapshotScheduler.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final AdminAgentObservabilityService observabilityService;
    private final AgentMetricSnapshotMapper snapshotMapper;
    private final AgentObservabilityAlertService alertService;
    private final org.springframework.core.env.Environment environment;
    private final Clock clock;
    private final String instanceId = UUID.randomUUID().toString();
    private CounterSnapshot previous;

    public AgentObservabilitySnapshotScheduler(
            AdminAgentObservabilityService observabilityService,
            AgentMetricSnapshotMapper snapshotMapper,
            AgentObservabilityAlertService alertService,
            org.springframework.core.env.Environment environment,
            Clock clock
    ) {
        this.observabilityService = observabilityService;
        this.snapshotMapper = snapshotMapper;
        this.alertService = alertService;
        this.environment = environment;
        this.clock = clock;
    }

    @Scheduled(
            fixedDelayString = "${app.agent.observability.persistence.snapshot-interval:PT5M}",
            initialDelayString = "${app.agent.observability.persistence.initial-delay:PT30S}",
            zone = "Asia/Shanghai"
    )
    public synchronized void persist() {
        AdminAgentObservabilityResponse current = observabilityService.currentSnapshot();
        CounterSnapshot counters = CounterSnapshot.from(current.metrics());
        AgentMetricSnapshot record = toRecord(counters.delta(previous), current);
        previous = counters;

        try {
            snapshotMapper.insert(record);
        } catch (RuntimeException exception) {
            log.warn("Unable to persist Agent observability snapshot", exception);
        }

        try {
            alertService.evaluate(current);
        } catch (RuntimeException exception) {
            log.warn("Unable to evaluate Agent observability alerts", exception);
        }

        try {
            snapshotMapper.deleteBefore(LocalDateTime.now(clock).minus(retention()));
        } catch (RuntimeException exception) {
            log.warn("Unable to clean up old Agent observability snapshots", exception);
        }
    }

    private AgentMetricSnapshot toRecord(CounterSnapshot delta, AdminAgentObservabilityResponse current) {
        AgentMetricSnapshot record = new AgentMetricSnapshot();
        record.setInstanceId(instanceId);
        record.setCapturedAt(LocalDateTime.ofInstant(current.generatedAt(), ZONE));
        record.setRunsStarted(delta.runsStarted());
        record.setRunsCompleted(delta.runsCompleted());
        record.setRunsFailed(delta.runsFailed());
        record.setRunsRecovered(delta.runsRecovered());
        record.setEventsPersisted(delta.eventsPersisted());
        record.setEventsReplayed(delta.eventsReplayed());
        record.setDuplicateWrites(delta.duplicateWrites());
        record.setDurationSamples(delta.durationSamples());
        record.setAverageRunDurationMs(BigDecimal.valueOf(current.metrics().averageRunDurationMs()));
        return record;
    }

    private Duration retention() {
        String raw = environment.getProperty("app.agent.observability.persistence.retention", "P30D");
        try {
            return Duration.parse(raw);
        } catch (RuntimeException exception) {
            return Duration.ofDays(30);
        }
    }

    private record CounterSnapshot(
            long runsStarted,
            long runsCompleted,
            long runsFailed,
            long runsRecovered,
            long eventsPersisted,
            long eventsReplayed,
            long duplicateWrites,
            long durationSamples
    ) {
        static CounterSnapshot from(AdminAgentObservabilityResponse.Metrics metrics) {
            return new CounterSnapshot(
                    metrics.runsStarted(),
                    metrics.runsCompleted(),
                    metrics.runsFailed(),
                    metrics.runsRecovered(),
                    metrics.eventsPersisted(),
                    metrics.eventsReplayed(),
                    metrics.duplicateWrites(),
                    metrics.durationSamples()
            );
        }

        CounterSnapshot delta(CounterSnapshot previous) {
            if (previous == null) {
                return this;
            }
            return new CounterSnapshot(
                    difference(runsStarted, previous.runsStarted),
                    difference(runsCompleted, previous.runsCompleted),
                    difference(runsFailed, previous.runsFailed),
                    difference(runsRecovered, previous.runsRecovered),
                    difference(eventsPersisted, previous.eventsPersisted),
                    difference(eventsReplayed, previous.eventsReplayed),
                    difference(duplicateWrites, previous.duplicateWrites),
                    difference(durationSamples, previous.durationSamples)
            );
        }

        private long difference(long current, long previous) {
            return current >= previous ? current - previous : current;
        }
    }
}
