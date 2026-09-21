package com.example.food.admin.dashboard;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("agent_metric_snapshots")
public class AgentMetricSnapshot {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String instanceId;
    private LocalDateTime capturedAt;
    private Long runsStarted;
    private Long runsCompleted;
    private Long runsFailed;
    private Long runsRecovered;
    private Long eventsPersisted;
    private Long eventsReplayed;
    private Long duplicateWrites;
    private Long durationSamples;
    private BigDecimal averageRunDurationMs;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getInstanceId() { return instanceId; }
    public void setInstanceId(String instanceId) { this.instanceId = instanceId; }
    public LocalDateTime getCapturedAt() { return capturedAt; }
    public void setCapturedAt(LocalDateTime capturedAt) { this.capturedAt = capturedAt; }
    public Long getRunsStarted() { return runsStarted; }
    public void setRunsStarted(Long runsStarted) { this.runsStarted = runsStarted; }
    public Long getRunsCompleted() { return runsCompleted; }
    public void setRunsCompleted(Long runsCompleted) { this.runsCompleted = runsCompleted; }
    public Long getRunsFailed() { return runsFailed; }
    public void setRunsFailed(Long runsFailed) { this.runsFailed = runsFailed; }
    public Long getRunsRecovered() { return runsRecovered; }
    public void setRunsRecovered(Long runsRecovered) { this.runsRecovered = runsRecovered; }
    public Long getEventsPersisted() { return eventsPersisted; }
    public void setEventsPersisted(Long eventsPersisted) { this.eventsPersisted = eventsPersisted; }
    public Long getEventsReplayed() { return eventsReplayed; }
    public void setEventsReplayed(Long eventsReplayed) { this.eventsReplayed = eventsReplayed; }
    public Long getDuplicateWrites() { return duplicateWrites; }
    public void setDuplicateWrites(Long duplicateWrites) { this.duplicateWrites = duplicateWrites; }
    public Long getDurationSamples() { return durationSamples; }
    public void setDurationSamples(Long durationSamples) { this.durationSamples = durationSamples; }
    public BigDecimal getAverageRunDurationMs() { return averageRunDurationMs; }
    public void setAverageRunDurationMs(BigDecimal averageRunDurationMs) { this.averageRunDurationMs = averageRunDurationMs; }
}
