package com.example.food.admin.dashboard;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("memory_evaluation_runs")
public class MemoryEvaluationRun {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String suiteVersion;
    private String status;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private Integer totalCases;
    private Integer passedCases;
    private Integer failedCases;
    private Long durationMs;
    private String resultJson;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getSuiteVersion() { return suiteVersion; }
    public void setSuiteVersion(String suiteVersion) { this.suiteVersion = suiteVersion; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
    public LocalDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(LocalDateTime finishedAt) { this.finishedAt = finishedAt; }
    public Integer getTotalCases() { return totalCases; }
    public void setTotalCases(Integer totalCases) { this.totalCases = totalCases; }
    public Integer getPassedCases() { return passedCases; }
    public void setPassedCases(Integer passedCases) { this.passedCases = passedCases; }
    public Integer getFailedCases() { return failedCases; }
    public void setFailedCases(Integer failedCases) { this.failedCases = failedCases; }
    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }
    public String getResultJson() { return resultJson; }
    public void setResultJson(String resultJson) { this.resultJson = resultJson; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
