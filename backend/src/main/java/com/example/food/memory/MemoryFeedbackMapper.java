package com.example.food.memory;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.Map;

@Mapper
public interface MemoryFeedbackMapper extends BaseMapper<MemoryFeedback> {

    @Select("SELECT * FROM memory_feedback WHERE trace_id = #{traceId} LIMIT 1")
    MemoryFeedback findByTraceId(@Param("traceId") String traceId);

    @Select("SELECT * FROM memory_feedback WHERE trace_id = #{traceId} LIMIT 1 FOR UPDATE")
    MemoryFeedback findByTraceIdForUpdate(@Param("traceId") String traceId);

    @Update("""
            UPDATE memory_feedback
            SET feedback_type = #{feedbackType}, updated_at = #{updatedAt}
            WHERE id = #{id} AND user_id = #{userId}
            """)
    int updateOwned(@Param("id") Long id,
                    @Param("userId") Long userId,
                    @Param("feedbackType") String feedbackType,
                    @Param("updatedAt") LocalDateTime updatedAt);

    @Select("""
            SELECT COUNT(*) AS feedbackCount,
                   COALESCE(SUM(CASE WHEN feedback_type = 'HELPFUL' THEN 1 ELSE 0 END), 0) AS helpfulCount,
                   COALESCE(SUM(CASE WHEN feedback_type = 'NOT_RELEVANT' THEN 1 ELSE 0 END), 0) AS notRelevantCount,
                   COALESCE(SUM(CASE WHEN feedback_type = 'INCORRECT' THEN 1 ELSE 0 END), 0) AS incorrectCount,
                   COALESCE(SUM(CASE WHEN feedback_type = 'OUTDATED' THEN 1 ELSE 0 END), 0) AS outdatedCount
            FROM memory_feedback
            WHERE updated_at >= #{fromTime}
            """)
    Map<String, Object> summarizeSince(@Param("fromTime") LocalDateTime fromTime);
}
