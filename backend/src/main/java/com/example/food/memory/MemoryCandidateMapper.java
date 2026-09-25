package com.example.food.memory;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Delete;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface MemoryCandidateMapper extends BaseMapper<MemoryCandidate> {

    @Select("""
            SELECT * FROM memory_candidates
            WHERE user_id = #{userId}
              AND extraction_key = #{extractionKey}
              AND deleted_at IS NULL
            """)
    MemoryCandidate findOwnedByExtractionKey(
            @Param("userId") Long userId,
            @Param("extractionKey") String extractionKey
    );

    @Select("""
            SELECT * FROM memory_candidates
            WHERE id = #{candidateId}
              AND user_id = #{userId}
              AND deleted_at IS NULL
            """)
    MemoryCandidate findOwned(@Param("userId") Long userId, @Param("candidateId") Long candidateId);

    @Select("""
            <script>
            SELECT * FROM memory_candidates
            WHERE user_id = #{userId} AND deleted_at IS NULL
            <if test='episodeId != null'>AND episode_id = #{episodeId}</if>
            <if test='candidateType != null and candidateType != ""'>AND candidate_type = #{candidateType}</if>
            ORDER BY extracted_at DESC, id DESC
            LIMIT #{limit}
            </script>
            """)
    List<MemoryCandidate> listOwned(
            @Param("userId") Long userId,
            @Param("episodeId") Long episodeId,
            @Param("candidateType") String candidateType,
            @Param("limit") int limit
    );

    @Select("""
            SELECT * FROM memory_candidates
            WHERE user_id = #{userId}
              AND deleted_at IS NULL
              AND status IN ('PENDING', 'ACCEPTED')
            ORDER BY extracted_at ASC, id ASC
            LIMIT #{limit}
            """)
    List<MemoryCandidate> listForConsolidation(
            @Param("userId") Long userId,
            @Param("limit") int limit
    );

    @Select("""
            SELECT * FROM memory_candidates
            WHERE user_id = #{userId}
              AND status = 'AWAITING_CONFIRMATION'
              AND deleted_at IS NULL
            ORDER BY extracted_at ASC, id ASC
            LIMIT #{limit}
            """)
    List<MemoryCandidate> listPendingConfirmations(
            @Param("userId") Long userId,
            @Param("limit") int limit
    );

    @Select("""
            SELECT COUNT(*) FROM memory_candidates
            WHERE user_id = #{userId}
              AND candidate_type = 'DIET_GOAL'
              AND preference = 'PURSUE'
              AND source_type = 'IMPLICIT_BEHAVIOR'
              AND extracted_at >= #{since}
              AND status != 'REJECTED'
              AND deleted_at IS NULL
              AND ((#{canonicalGroupId} IS NOT NULL AND canonical_group_id = #{canonicalGroupId})
                   OR LOWER(COALESCE(canonical_entity, entity)) = LOWER(#{canonicalEntity}))
            """)
    int countRecentSimilarDietGoals(
            @Param("userId") Long userId,
            @Param("canonicalGroupId") String canonicalGroupId,
            @Param("canonicalEntity") String canonicalEntity,
            @Param("since") LocalDateTime since
    );

    @Select("""
            SELECT COUNT(*) FROM memory_candidates
            WHERE user_id = #{userId}
              AND candidate_type = 'DIET_GOAL'
              AND preference = 'PURSUE'
              AND user_decision IS NOT NULL
              AND (user_decision = 'CONFIRM' OR decided_at >= #{since})
              AND deleted_at IS NULL
              AND ((#{canonicalGroupId} IS NOT NULL AND canonical_group_id = #{canonicalGroupId})
                   OR LOWER(COALESCE(canonical_entity, entity)) = LOWER(#{canonicalEntity}))
            """)
    int countRecentDecisionsForSimilarDietGoal(
            @Param("userId") Long userId,
            @Param("canonicalGroupId") String canonicalGroupId,
            @Param("canonicalEntity") String canonicalEntity,
            @Param("since") LocalDateTime since
    );

    @Select("""
            <script>
            SELECT * FROM memory_candidates
            WHERE user_id = #{userId}
              AND candidate_type = 'DIET_GOAL'
              AND preference = 'PURSUE'
              AND source_type = 'IMPLICIT_BEHAVIOR'
              AND extracted_at >= #{since}
              AND status &lt;&gt; 'REJECTED'
              AND deleted_at IS NULL
              AND ((#{canonicalGroupId} IS NOT NULL AND canonical_group_id = #{canonicalGroupId})
                   OR LOWER(COALESCE(canonical_entity, entity)) = LOWER(#{canonicalEntity}))
            ORDER BY extracted_at DESC, id DESC
            LIMIT #{limit}
            </script>
            """)
    List<MemoryCandidate> listRecentSimilarDietGoals(
            @Param("userId") Long userId,
            @Param("canonicalGroupId") String canonicalGroupId,
            @Param("canonicalEntity") String canonicalEntity,
            @Param("since") LocalDateTime since,
            @Param("limit") int limit
    );

    @Update("""
            UPDATE memory_candidates
            SET status = #{status},
                user_decision = #{decision},
                decided_at = #{decidedAt},
                source_type = #{sourceType},
                temporal_type = #{temporalType},
                confidence = #{confidence},
                strength = #{strength},
                evidence_count = #{evidenceCount},
                version = version + 1,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{candidateId}
              AND user_id = #{userId}
              AND version = #{version}
              AND status = 'AWAITING_CONFIRMATION'
              AND deleted_at IS NULL
            """)
    int decidePendingConfirmation(
            @Param("userId") Long userId,
            @Param("candidateId") Long candidateId,
            @Param("version") Integer version,
            @Param("status") String status,
            @Param("decision") String decision,
            @Param("decidedAt") LocalDateTime decidedAt,
            @Param("sourceType") String sourceType,
            @Param("temporalType") String temporalType,
            @Param("confidence") java.math.BigDecimal confidence,
            @Param("strength") java.math.BigDecimal strength,
            @Param("evidenceCount") Integer evidenceCount
    );

    @Update("""
            UPDATE memory_candidates
            SET status = 'CONSOLIDATED',
                version = version + 1,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{candidateId}
              AND user_id = #{userId}
              AND version = #{version}
              AND deleted_at IS NULL
              AND status IN ('PENDING', 'ACCEPTED')
            """)
    int markConsolidated(
            @Param("userId") Long userId,
            @Param("candidateId") Long candidateId,
            @Param("version") Integer version
    );

    @Update("""
            UPDATE memory_candidates
            SET status = 'REJECTED',
                user_decision = 'REJECT',
                decided_at = CURRENT_TIMESTAMP,
                version = version + 1,
                updated_at = CURRENT_TIMESTAMP
            WHERE user_id = #{userId}
              AND status != 'REJECTED'
              AND id IN (
                  SELECT candidate_id FROM memory_item_candidates
                  WHERE user_id = #{userId} AND memory_item_id = #{memoryItemId}
              )
            """)
    int rejectCandidatesForMemory(
            @Param("userId") Long userId,
            @Param("memoryItemId") Long memoryItemId
    );

    @Delete("DELETE FROM memory_candidates WHERE user_id = #{userId}")
    int deleteAllOwned(@Param("userId") Long userId);
}
