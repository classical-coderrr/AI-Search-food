package com.example.food.memory;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("memory_embeddings")
public class MemoryEmbedding {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String sourceKind;
    private Long sourceId;
    private String memoryType;
    private Integer sourceVersion;
    private String embeddingModel;
    private Integer dimensions;
    private String embeddingJson;
    private LocalDateTime annIndexedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public Long getUserId() { return userId; }
    public void setUserId(Long value) { userId = value; }
    public String getSourceKind() { return sourceKind; }
    public void setSourceKind(String value) { sourceKind = value; }
    public Long getSourceId() { return sourceId; }
    public void setSourceId(Long value) { sourceId = value; }
    public String getMemoryType() { return memoryType; }
    public void setMemoryType(String value) { memoryType = value; }
    public Integer getSourceVersion() { return sourceVersion; }
    public void setSourceVersion(Integer value) { sourceVersion = value; }
    public String getEmbeddingModel() { return embeddingModel; }
    public void setEmbeddingModel(String value) { embeddingModel = value; }
    public Integer getDimensions() { return dimensions; }
    public void setDimensions(Integer value) { dimensions = value; }
    public String getEmbeddingJson() { return embeddingJson; }
    public void setEmbeddingJson(String value) { embeddingJson = value; }
    public LocalDateTime getAnnIndexedAt() { return annIndexedAt; }
    public void setAnnIndexedAt(LocalDateTime value) { annIndexedAt = value; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime value) { createdAt = value; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime value) { updatedAt = value; }
}
