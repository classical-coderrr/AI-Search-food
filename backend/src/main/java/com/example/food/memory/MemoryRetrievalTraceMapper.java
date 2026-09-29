package com.example.food.memory;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.Map;

@Mapper
public interface MemoryRetrievalTraceMapper extends BaseMapper<MemoryRetrievalTrace> {

    @Select("SELECT * FROM memory_retrieval_traces WHERE user_id = #{userId} AND trace_id = #{traceId} LIMIT 1")
    MemoryRetrievalTrace findOwnedByTraceId(@Param("userId") Long userId,
                                            @Param("traceId") String traceId);

    @Select("""
            SELECT COUNT(*) AS totalCount,
                   COALESCE(SUM(CASE WHEN status = 'SUCCESS' THEN 1 ELSE 0 END), 0) AS successCount,
                   COALESCE(SUM(CASE WHEN status = 'DEGRADED' THEN 1 ELSE 0 END), 0) AS degradedCount,
                   COALESCE(SUM(CASE WHEN truncated = TRUE THEN 1 ELSE 0 END), 0) AS truncatedCount,
                   COALESCE(SUM(used_memory_item_count + used_episode_count), 0) AS usedMemoryTargetCount,
                   COALESCE(AVG(memory_item_candidates + episode_candidates), 0) AS averageCandidates,
                   COALESCE(AVG(estimated_tokens), 0) AS averageEstimatedTokens,
                   COALESCE(SUM(llm_input_tokens), 0) AS llmInputTokens,
                   COALESCE(SUM(llm_output_tokens), 0) AS llmOutputTokens,
                   COALESCE(SUM(llm_total_tokens), 0) AS llmTotalTokens,
                   COALESCE(SUM(llm_usage_call_count), 0) AS llmUsageCallCount,
                   COALESCE(AVG(latency_ms), 0) AS averageLatencyMs
            FROM memory_retrieval_traces
            WHERE created_at >= #{fromTime}
            """)
    Map<String, Object> summarizeSince(@Param("fromTime") LocalDateTime fromTime);

    @Update("""
            <script>
            UPDATE memory_retrieval_traces
            <set>
              <if test='inputTokens != null'>
                llm_input_tokens = COALESCE(llm_input_tokens, 0) + #{inputTokens},
              </if>
              <if test='outputTokens != null'>
                llm_output_tokens = COALESCE(llm_output_tokens, 0) + #{outputTokens},
              </if>
              <if test='totalTokens != null'>
                llm_total_tokens = COALESCE(llm_total_tokens, 0) + #{totalTokens},
              </if>
              llm_usage_call_count = llm_usage_call_count + 1
            </set>
            WHERE user_id = #{userId} AND trace_id = #{traceId}
            </script>
            """)
    int addLlmUsage(@Param("userId") Long userId, @Param("traceId") String traceId,
                    @Param("inputTokens") Long inputTokens, @Param("outputTokens") Long outputTokens,
                    @Param("totalTokens") Long totalTokens);

    @Delete("DELETE FROM memory_retrieval_traces WHERE user_id = #{userId}")
    int deleteAllOwned(@Param("userId") Long userId);

    @Delete("DELETE FROM memory_retrieval_traces WHERE created_at < #{before}")
    int deleteBefore(@Param("before") LocalDateTime before);
}
