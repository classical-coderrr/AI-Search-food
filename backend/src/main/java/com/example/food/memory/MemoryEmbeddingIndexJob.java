package com.example.food.memory;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("memory_embedding_index_jobs")
public class MemoryEmbeddingIndexJob {
    @TableId
    private Long id;
    private Long userId;
    private String sourceKind;
    private Long sourceId;
    private Integer sourceVersion;
    private String embeddingModel;
    private Integer dimensions;
    private String status;
    private Integer attempts;
    private String leaseToken;
    private String lastError;

    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public Long getUserId() { return userId; }
    public void setUserId(Long value) { userId = value; }
    public String getSourceKind() { return sourceKind; }
    public void setSourceKind(String value) { sourceKind = value; }
    public Long getSourceId() { return sourceId; }
    public void setSourceId(Long value) { sourceId = value; }
    public Integer getSourceVersion() { return sourceVersion; }
    public void setSourceVersion(Integer value) { sourceVersion = value; }
    public String getEmbeddingModel() { return embeddingModel; }
    public void setEmbeddingModel(String value) { embeddingModel = value; }
    public Integer getDimensions() { return dimensions; }
    public void setDimensions(Integer value) { dimensions = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { status = value; }
    public Integer getAttempts() { return attempts; }
    public void setAttempts(Integer value) { attempts = value; }
    public String getLeaseToken() { return leaseToken; }
    public void setLeaseToken(String value) { leaseToken = value; }
    public String getLastError() { return lastError; }
    public void setLastError(String value) { lastError = value; }
}
