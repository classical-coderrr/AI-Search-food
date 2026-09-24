package com.example.food.memory;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("memory_items")
public class MemoryItem {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String memoryType;
    private String canonicalEntity;
    private Long canonicalTagId;
    private String canonicalId;
    private String canonicalCategory;
    private String canonicalGroupId;
    private String preference;
    private String scope;
    private String temporalType;
    private BigDecimal strength;
    private BigDecimal confidence;
    private BigDecimal importance;
    private Integer evidenceCount;
    private Integer occurrenceCount;
    private Integer sourceCount;
    private String sourceCandidateIdsJson;
    private String sourceEpisodeIdsJson;
    private LocalDateTime firstSeenAt;
    private LocalDateTime lastSeenAt;
    private String consolidationKey;
    private String status;
    private Integer version;
    private LocalDateTime deletedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getMemoryType() { return memoryType; }
    public void setMemoryType(String memoryType) { this.memoryType = memoryType; }
    public String getCanonicalEntity() { return canonicalEntity; }
    public void setCanonicalEntity(String canonicalEntity) { this.canonicalEntity = canonicalEntity; }
    public Long getCanonicalTagId() { return canonicalTagId; }
    public void setCanonicalTagId(Long canonicalTagId) { this.canonicalTagId = canonicalTagId; }
    public String getCanonicalId() { return canonicalId; }
    public void setCanonicalId(String canonicalId) { this.canonicalId = canonicalId; }
    public String getCanonicalCategory() { return canonicalCategory; }
    public void setCanonicalCategory(String canonicalCategory) { this.canonicalCategory = canonicalCategory; }
    public String getCanonicalGroupId() { return canonicalGroupId; }
    public void setCanonicalGroupId(String canonicalGroupId) { this.canonicalGroupId = canonicalGroupId; }
    public String getPreference() { return preference; }
    public void setPreference(String preference) { this.preference = preference; }
    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }
    public String getTemporalType() { return temporalType; }
    public void setTemporalType(String temporalType) { this.temporalType = temporalType; }
    public BigDecimal getStrength() { return strength; }
    public void setStrength(BigDecimal strength) { this.strength = strength; }
    public BigDecimal getConfidence() { return confidence; }
    public void setConfidence(BigDecimal confidence) { this.confidence = confidence; }
    public BigDecimal getImportance() { return importance; }
    public void setImportance(BigDecimal importance) { this.importance = importance; }
    public Integer getEvidenceCount() { return evidenceCount; }
    public void setEvidenceCount(Integer evidenceCount) { this.evidenceCount = evidenceCount; }
    public Integer getOccurrenceCount() { return occurrenceCount; }
    public void setOccurrenceCount(Integer occurrenceCount) { this.occurrenceCount = occurrenceCount; }
    public Integer getSourceCount() { return sourceCount; }
    public void setSourceCount(Integer sourceCount) { this.sourceCount = sourceCount; }
    public String getSourceCandidateIdsJson() { return sourceCandidateIdsJson; }
    public void setSourceCandidateIdsJson(String sourceCandidateIdsJson) { this.sourceCandidateIdsJson = sourceCandidateIdsJson; }
    public String getSourceEpisodeIdsJson() { return sourceEpisodeIdsJson; }
    public void setSourceEpisodeIdsJson(String sourceEpisodeIdsJson) { this.sourceEpisodeIdsJson = sourceEpisodeIdsJson; }
    public LocalDateTime getFirstSeenAt() { return firstSeenAt; }
    public void setFirstSeenAt(LocalDateTime firstSeenAt) { this.firstSeenAt = firstSeenAt; }
    public LocalDateTime getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(LocalDateTime lastSeenAt) { this.lastSeenAt = lastSeenAt; }
    public String getConsolidationKey() { return consolidationKey; }
    public void setConsolidationKey(String consolidationKey) { this.consolidationKey = consolidationKey; }
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
