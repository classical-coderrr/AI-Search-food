package com.example.food.memory;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("memory_candidates")
public class MemoryCandidate {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long episodeId;
    private Long sessionId;
    private String candidateType;
    private String entity;
    private String preference;
    private BigDecimal strength;
    private BigDecimal confidence;
    private String sourceType;
    private String scope;
    private String temporalType;
    private Integer evidenceCount;
    private String extractionKey;
    private String extractionModel;
    private String promptVersion;
    private LocalDateTime extractedAt;
    private String status;
    private Integer version;
    private LocalDateTime deletedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getEpisodeId() { return episodeId; }
    public void setEpisodeId(Long episodeId) { this.episodeId = episodeId; }
    public Long getSessionId() { return sessionId; }
    public void setSessionId(Long sessionId) { this.sessionId = sessionId; }
    public String getCandidateType() { return candidateType; }
    public void setCandidateType(String candidateType) { this.candidateType = candidateType; }
    public String getEntity() { return entity; }
    public void setEntity(String entity) { this.entity = entity; }
    public String getPreference() { return preference; }
    public void setPreference(String preference) { this.preference = preference; }
    public BigDecimal getStrength() { return strength; }
    public void setStrength(BigDecimal strength) { this.strength = strength; }
    public BigDecimal getConfidence() { return confidence; }
    public void setConfidence(BigDecimal confidence) { this.confidence = confidence; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }
    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }
    public String getTemporalType() { return temporalType; }
    public void setTemporalType(String temporalType) { this.temporalType = temporalType; }
    public Integer getEvidenceCount() { return evidenceCount; }
    public void setEvidenceCount(Integer evidenceCount) { this.evidenceCount = evidenceCount; }
    public String getExtractionKey() { return extractionKey; }
    public void setExtractionKey(String extractionKey) { this.extractionKey = extractionKey; }
    public String getExtractionModel() { return extractionModel; }
    public void setExtractionModel(String extractionModel) { this.extractionModel = extractionModel; }
    public String getPromptVersion() { return promptVersion; }
    public void setPromptVersion(String promptVersion) { this.promptVersion = promptVersion; }
    public LocalDateTime getExtractedAt() { return extractedAt; }
    public void setExtractedAt(LocalDateTime extractedAt) { this.extractedAt = extractedAt; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public LocalDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(LocalDateTime deletedAt) { this.deletedAt = deletedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
