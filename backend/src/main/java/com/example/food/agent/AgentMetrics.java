package com.example.food.agent;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Small, vendor-neutral Agent metric facade. It stays a no-op in direct unit
 * tests while the application exports the same counters through Actuator.
 */
@Component
public class AgentMetrics {

    private final MeterRegistry registry;
    private final Counter runsStarted;
    private final Counter runsCompleted;
    private final Counter runsFailed;
    private final Counter runsRecovered;
    private final Counter eventsPersisted;
    private final Counter eventsReplayed;
    private final Counter duplicateWrites;
    private final Timer runDuration;

    @Autowired
    public AgentMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.runsStarted = counter("agent.runs.started");
        this.runsCompleted = counter("agent.runs.completed");
        this.runsFailed = counter("agent.runs.failed");
        this.runsRecovered = counter("agent.runs.recovered");
        this.eventsPersisted = counter("agent.events.persisted");
        this.eventsReplayed = counter("agent.events.replayed");
        this.duplicateWrites = counter("agent.writes.duplicate");
        this.runDuration = registry == null ? null : registry.timer("agent.runs.duration");
    }

    public static AgentMetrics disabled() {
        return new AgentMetrics(null);
    }

    public void runStarted() {
        increment(runsStarted);
    }

    public void runCompleted(Duration duration) {
        increment(runsCompleted);
        recordDuration(duration);
    }

    public void runFailed(Duration duration) {
        increment(runsFailed);
        recordDuration(duration);
    }

    public void runRecovered() {
        increment(runsRecovered);
    }

    public void eventPersisted() {
        increment(eventsPersisted);
    }

    public void eventReplayed() {
        increment(eventsReplayed);
    }

    public void duplicateWrite() {
        increment(duplicateWrites);
    }

    private Counter counter(String name) {
        return registry == null ? null : registry.counter(name);
    }

    private void increment(Counter counter) {
        if (counter != null) {
            counter.increment();
        }
    }

    private void recordDuration(Duration duration) {
        if (runDuration != null && duration != null && !duration.isNegative()) {
            runDuration.record(duration);
        }
    }
}
