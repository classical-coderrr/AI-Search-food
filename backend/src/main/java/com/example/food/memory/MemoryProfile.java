package com.example.food.memory;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("memory_profiles")
public class MemoryProfile {

    @TableId
    private Long userId;
    private String profileJson;
    private Integer profileVersion;
    private Long sourceRevision;
    private Integer version;
    private LocalDateTime deletedAt;
    private LocalDateTime rebuiltAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getProfileJson() { return profileJson; }
    public void setProfileJson(String profileJson) { this.profileJson = profileJson; }
    public Integer getProfileVersion() { return profileVersion; }
    public void setProfileVersion(Integer profileVersion) { this.profileVersion = profileVersion; }
    public Long getSourceRevision() { return sourceRevision; }
    public void setSourceRevision(Long sourceRevision) { this.sourceRevision = sourceRevision; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public LocalDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(LocalDateTime deletedAt) { this.deletedAt = deletedAt; }
    public LocalDateTime getRebuiltAt() { return rebuiltAt; }
    public void setRebuiltAt(LocalDateTime rebuiltAt) { this.rebuiltAt = rebuiltAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
