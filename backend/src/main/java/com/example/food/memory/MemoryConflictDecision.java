package com.example.food.memory;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("memory_conflicts")
public class MemoryConflictDecision {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String traceId;
    private Long sessionId;
    private String conflictKey;
    private String conflictDomain;
    private String canonicalEntity;
    private Long likeMemoryItemId;
    private Long dislikeMemoryItemId;
    private Long selectedMemoryItemId;
    private String selectedPreference;
    private String resolutionType;
    private String reason;
    private String contextJson;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
    public Long getSessionId() { return sessionId; }
    public void setSessionId(Long sessionId) { this.sessionId = sessionId; }
    public String getConflictKey() { return conflictKey; }
    public void setConflictKey(String conflictKey) { this.conflictKey = conflictKey; }
    public String getConflictDomain() { return conflictDomain; }
    public void setConflictDomain(String conflictDomain) { this.conflictDomain = conflictDomain; }
    public String getCanonicalEntity() { return canonicalEntity; }
    public void setCanonicalEntity(String canonicalEntity) { this.canonicalEntity = canonicalEntity; }
    public Long getLikeMemoryItemId() { return likeMemoryItemId; }
    public void setLikeMemoryItemId(Long likeMemoryItemId) { this.likeMemoryItemId = likeMemoryItemId; }
    public Long getDislikeMemoryItemId() { return dislikeMemoryItemId; }
    public void setDislikeMemoryItemId(Long dislikeMemoryItemId) { this.dislikeMemoryItemId = dislikeMemoryItemId; }
    public Long getSelectedMemoryItemId() { return selectedMemoryItemId; }
    public void setSelectedMemoryItemId(Long selectedMemoryItemId) { this.selectedMemoryItemId = selectedMemoryItemId; }
    public String getSelectedPreference() { return selectedPreference; }
    public void setSelectedPreference(String selectedPreference) { this.selectedPreference = selectedPreference; }
    public String getResolutionType() { return resolutionType; }
    public void setResolutionType(String resolutionType) { this.resolutionType = resolutionType; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getContextJson() { return contextJson; }
    public void setContextJson(String contextJson) { this.contextJson = contextJson; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
