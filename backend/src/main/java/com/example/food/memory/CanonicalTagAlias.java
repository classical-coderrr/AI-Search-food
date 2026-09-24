package com.example.food.memory;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("canonical_tag_aliases")
public class CanonicalTagAlias {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long canonicalTagId;
    private String alias;
    private String normalizedAlias;
    private String locale;
    private String aliasType;
    private String source;
    private BigDecimal confidence;
    private Boolean enabled;
    private Integer version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getCanonicalTagId() { return canonicalTagId; }
    public void setCanonicalTagId(Long canonicalTagId) { this.canonicalTagId = canonicalTagId; }
    public String getAlias() { return alias; }
    public void setAlias(String alias) { this.alias = alias; }
    public String getNormalizedAlias() { return normalizedAlias; }
    public void setNormalizedAlias(String normalizedAlias) { this.normalizedAlias = normalizedAlias; }
    public String getLocale() { return locale; }
    public void setLocale(String locale) { this.locale = locale; }
    public String getAliasType() { return aliasType; }
    public void setAliasType(String aliasType) { this.aliasType = aliasType; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public BigDecimal getConfidence() { return confidence; }
    public void setConfidence(BigDecimal confidence) { this.confidence = confidence; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
