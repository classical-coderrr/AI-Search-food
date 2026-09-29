package com.example.food.memory;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface MemoryTargetFeedbackMapper extends BaseMapper<MemoryTargetFeedback> {

    @Select("SELECT * FROM memory_target_feedback WHERE user_id = #{userId} AND trace_id = #{traceId} ORDER BY id")
    List<MemoryTargetFeedback> findOwnedByTrace(@Param("userId") Long userId,
                                                @Param("traceId") String traceId);

    @Select("""
            SELECT * FROM memory_target_feedback
            WHERE user_id = #{userId} AND trace_id = #{traceId}
              AND source_kind = 'MEMORY_ITEM' AND memory_item_id = #{sourceId}
            LIMIT 1 FOR UPDATE
            """)
    MemoryTargetFeedback findMemoryItemForUpdate(@Param("userId") Long userId,
                                                 @Param("traceId") String traceId,
                                                 @Param("sourceId") Long sourceId);

    @Select("""
            SELECT * FROM memory_target_feedback
            WHERE user_id = #{userId} AND trace_id = #{traceId}
              AND source_kind = 'EPISODE' AND episode_id = #{sourceId}
            LIMIT 1 FOR UPDATE
            """)
    MemoryTargetFeedback findEpisodeForUpdate(@Param("userId") Long userId,
                                              @Param("traceId") String traceId,
                                              @Param("sourceId") Long sourceId);

    @Update("""
            UPDATE memory_target_feedback
            SET feedback_type = #{feedbackType}, updated_at = #{updatedAt}
            WHERE id = #{id} AND user_id = #{userId}
            """)
    int updateOwned(@Param("id") Long id,
                    @Param("userId") Long userId,
                    @Param("feedbackType") String feedbackType,
                    @Param("updatedAt") LocalDateTime updatedAt);

    @Select("""
            <script>
            SELECT * FROM memory_target_feedback
            WHERE user_id = #{userId} AND intent = #{intent}
              AND updated_at &gt;= #{since}
              AND memory_item_id IN
              <foreach collection='sourceIds' item='sourceId' open='(' separator=',' close=')'>#{sourceId}</foreach>
            ORDER BY updated_at DESC, id DESC
            </script>
            """)
    List<MemoryTargetFeedback> findRecentMemoryItemSignals(@Param("userId") Long userId,
                                                           @Param("intent") String intent,
                                                           @Param("sourceIds") List<Long> sourceIds,
                                                           @Param("since") LocalDateTime since);

    @Select("""
            <script>
            SELECT * FROM memory_target_feedback
            WHERE user_id = #{userId} AND intent = #{intent}
              AND updated_at &gt;= #{since}
              AND episode_id IN
              <foreach collection='sourceIds' item='sourceId' open='(' separator=',' close=')'>#{sourceId}</foreach>
            ORDER BY updated_at DESC, id DESC
            </script>
            """)
    List<MemoryTargetFeedback> findRecentEpisodeSignals(@Param("userId") Long userId,
                                                        @Param("intent") String intent,
                                                        @Param("sourceIds") List<Long> sourceIds,
                                                        @Param("since") LocalDateTime since);

    @Select("""
            SELECT COUNT(*) AS labeledTargetCount,
                   COALESCE(SUM(CASE WHEN feedback.feedback_type = 'HELPFUL' THEN 1 ELSE 0 END), 0) AS helpfulCount,
                   COALESCE(SUM(CASE WHEN feedback.feedback_type = 'NOT_RELEVANT' THEN 1 ELSE 0 END), 0) AS notRelevantCount,
                   COALESCE(SUM(CASE WHEN feedback.feedback_type = 'INCORRECT' THEN 1 ELSE 0 END), 0) AS incorrectCount,
                   COALESCE(SUM(CASE WHEN feedback.feedback_type = 'OUTDATED' THEN 1 ELSE 0 END), 0) AS outdatedCount
            FROM memory_target_feedback feedback
            INNER JOIN memory_retrieval_traces trace
              ON trace.user_id = feedback.user_id AND trace.trace_id = feedback.trace_id
            WHERE trace.created_at >= #{fromTime}
            """)
    Map<String, Object> summarizeUsageFeedbackSince(@Param("fromTime") LocalDateTime fromTime);
}
