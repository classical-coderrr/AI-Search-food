package com.example.food.memory;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("memory_retrieval_traces")
public class MemoryRetrievalTrace {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String traceId;
    private Long userId;
    private Long sessionId;
    private String intent;
    private String queryHash;
    private Integer memoryItemCandidates;
    private Integer episodeCandidates;
    private String retrievedMemoryItemIdsJson;
    private String retrievedEpisodeIdsJson;
    private String usedMemoryItemIdsJson;
    private String usedEpisodeIdsJson;
    private String knowledgeIdsJson;
    private String rankingJson;
    private String contextSectionsJson;
    private Integer estimatedTokens;
    private Integer tokenBudget;
    private Boolean truncated;
    private String status;
    private String errorType;
    private Long latencyMs;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getSessionId() { return sessionId; }
    public void setSessionId(Long sessionId) { this.sessionId = sessionId; }
    public String getIntent() { return intent; }
    public void setIntent(String intent) { this.intent = intent; }
    public String getQueryHash() { return queryHash; }
    public void setQueryHash(String queryHash) { this.queryHash = queryHash; }
    public Integer getMemoryItemCandidates() { return memoryItemCandidates; }
    public void setMemoryItemCandidates(Integer memoryItemCandidates) { this.memoryItemCandidates = memoryItemCandidates; }
    public Integer getEpisodeCandidates() { return episodeCandidates; }
    public void setEpisodeCandidates(Integer episodeCandidates) { this.episodeCandidates = episodeCandidates; }
    public String getRetrievedMemoryItemIdsJson() { return retrievedMemoryItemIdsJson; }
    public void setRetrievedMemoryItemIdsJson(String retrievedMemoryItemIdsJson) { this.retrievedMemoryItemIdsJson = retrievedMemoryItemIdsJson; }
    public String getRetrievedEpisodeIdsJson() { return retrievedEpisodeIdsJson; }
    public void setRetrievedEpisodeIdsJson(String retrievedEpisodeIdsJson) { this.retrievedEpisodeIdsJson = retrievedEpisodeIdsJson; }
    public String getUsedMemoryItemIdsJson() { return usedMemoryItemIdsJson; }
    public void setUsedMemoryItemIdsJson(String usedMemoryItemIdsJson) { this.usedMemoryItemIdsJson = usedMemoryItemIdsJson; }
    public String getUsedEpisodeIdsJson() { return usedEpisodeIdsJson; }
    public void setUsedEpisodeIdsJson(String usedEpisodeIdsJson) { this.usedEpisodeIdsJson = usedEpisodeIdsJson; }
    public String getKnowledgeIdsJson() { return knowledgeIdsJson; }
    public void setKnowledgeIdsJson(String knowledgeIdsJson) { this.knowledgeIdsJson = knowledgeIdsJson; }
    public String getRankingJson() { return rankingJson; }
    public void setRankingJson(String rankingJson) { this.rankingJson = rankingJson; }
    public String getContextSectionsJson() { return contextSectionsJson; }
    public void setContextSectionsJson(String contextSectionsJson) { this.contextSectionsJson = contextSectionsJson; }
    public Integer getEstimatedTokens() { return estimatedTokens; }
    public void setEstimatedTokens(Integer estimatedTokens) { this.estimatedTokens = estimatedTokens; }
    public Integer getTokenBudget() { return tokenBudget; }
    public void setTokenBudget(Integer tokenBudget) { this.tokenBudget = tokenBudget; }
    public Boolean getTruncated() { return truncated; }
    public void setTruncated(Boolean truncated) { this.truncated = truncated; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getErrorType() { return errorType; }
    public void setErrorType(String errorType) { this.errorType = errorType; }
    public Long getLatencyMs() { return latencyMs; }
    public void setLatencyMs(Long latencyMs) { this.latencyMs = latencyMs; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
