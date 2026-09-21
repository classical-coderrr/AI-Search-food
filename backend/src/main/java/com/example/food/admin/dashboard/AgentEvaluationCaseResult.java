package com.example.food.admin.dashboard;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("agent_evaluation_case_results")
public class AgentEvaluationCaseResult {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long runId;
    private String caseKey;
    private String description;
    private String inputMessage;
    private Boolean passed;
    private String expectedTools;
    private String actualTools;
    private String failureReason;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getRunId() { return runId; }
    public void setRunId(Long runId) { this.runId = runId; }
    public String getCaseKey() { return caseKey; }
    public void setCaseKey(String caseKey) { this.caseKey = caseKey; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getInputMessage() { return inputMessage; }
    public void setInputMessage(String inputMessage) { this.inputMessage = inputMessage; }
    public Boolean getPassed() { return passed; }
    public void setPassed(Boolean passed) { this.passed = passed; }
    public String getExpectedTools() { return expectedTools; }
    public void setExpectedTools(String expectedTools) { this.expectedTools = expectedTools; }
    public String getActualTools() { return actualTools; }
    public void setActualTools(String actualTools) { this.actualTools = actualTools; }
    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
