package com.example.food.memory;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.Map;

@Mapper
public interface MemoryRetrievalTraceMapper extends BaseMapper<MemoryRetrievalTrace> {

    @Select("""
            SELECT COUNT(*) AS totalCount,
                   COALESCE(SUM(CASE WHEN status = 'SUCCESS' THEN 1 ELSE 0 END), 0) AS successCount,
                   COALESCE(SUM(CASE WHEN status = 'DEGRADED' THEN 1 ELSE 0 END), 0) AS degradedCount,
                   COALESCE(SUM(CASE WHEN truncated = TRUE THEN 1 ELSE 0 END), 0) AS truncatedCount,
                   COALESCE(AVG(memory_item_candidates + episode_candidates), 0) AS averageCandidates,
                   COALESCE(AVG(estimated_tokens), 0) AS averageEstimatedTokens,
                   COALESCE(AVG(latency_ms), 0) AS averageLatencyMs
            FROM memory_retrieval_traces
            WHERE created_at >= #{fromTime}
            """)
    Map<String, Object> summarizeSince(@Param("fromTime") LocalDateTime fromTime);

    @Delete("DELETE FROM memory_retrieval_traces WHERE user_id = #{userId}")
    int deleteAllOwned(@Param("userId") Long userId);

    @Delete("DELETE FROM memory_retrieval_traces WHERE created_at < #{before}")
    int deleteBefore(@Param("before") LocalDateTime before);
}
