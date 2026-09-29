package com.example.food.memory;

public class MemoryEmbeddingCandidate {
    private Long userId;
    private String sourceKind;
    private Long sourceId;
    private Integer sourceVersion;
    private String memoryType;
    private String content;

    public Long getUserId() { return userId; }
    public void setUserId(Long value) { userId = value; }
    public String getSourceKind() { return sourceKind; }
    public void setSourceKind(String value) { sourceKind = value; }
    public Long getSourceId() { return sourceId; }
    public void setSourceId(Long value) { sourceId = value; }
    public Integer getSourceVersion() { return sourceVersion; }
    public void setSourceVersion(Integer value) { sourceVersion = value; }
    public String getMemoryType() { return memoryType; }
    public void setMemoryType(String value) { memoryType = value; }
    public String getContent() { return content; }
    public void setContent(String value) { content = value; }
}
