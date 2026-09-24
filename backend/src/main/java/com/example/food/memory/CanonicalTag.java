package com.example.food.memory;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("canonical_tags")
public class CanonicalTag {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String canonicalId;
    private String canonicalName;
    private String category;
    private String parentCanonicalId;
    private String mergeCanonicalId;
    private String aliasesJson;
    private Boolean enabled;
    private Integer version;
    private LocalDateTime deletedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCanonicalId() { return canonicalId; }
    public void setCanonicalId(String canonicalId) { this.canonicalId = canonicalId; }
    public String getCanonicalName() { return canonicalName; }
    public void setCanonicalName(String canonicalName) { this.canonicalName = canonicalName; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getParentCanonicalId() { return parentCanonicalId; }
    public void setParentCanonicalId(String parentCanonicalId) { this.parentCanonicalId = parentCanonicalId; }
    public String getMergeCanonicalId() { return mergeCanonicalId; }
    public void setMergeCanonicalId(String mergeCanonicalId) { this.mergeCanonicalId = mergeCanonicalId; }
    public String getAliasesJson() { return aliasesJson; }
    public void setAliasesJson(String aliasesJson) { this.aliasesJson = aliasesJson; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public LocalDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(LocalDateTime deletedAt) { this.deletedAt = deletedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
