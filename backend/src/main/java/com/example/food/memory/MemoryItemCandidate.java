package com.example.food.memory;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("memory_item_candidates")
public class MemoryItemCandidate {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long memoryItemId;
    private Long candidateId;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getMemoryItemId() { return memoryItemId; }
    public void setMemoryItemId(Long memoryItemId) { this.memoryItemId = memoryItemId; }
    public Long getCandidateId() { return candidateId; }
    public void setCandidateId(Long candidateId) { this.candidateId = candidateId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
