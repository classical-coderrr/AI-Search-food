package com.example.food.memory;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface MemoryConflictDecisionMapper {

    @Select("""
            SELECT COUNT(*)
            FROM memory_items
            WHERE user_id = #{userId}
              AND id IN (#{likeMemoryItemId}, #{dislikeMemoryItemId})
              AND status = 'ACTIVE'
              AND deleted_at IS NULL
            """)
    int countOwnedActivePair(@Param("userId") Long userId,
                             @Param("likeMemoryItemId") Long likeMemoryItemId,
                             @Param("dislikeMemoryItemId") Long dislikeMemoryItemId);

    @Insert("""
            INSERT INTO memory_conflicts (
                user_id, trace_id, session_id, conflict_key, conflict_domain, canonical_entity,
                like_memory_item_id, dislike_memory_item_id, selected_memory_item_id,
                selected_preference, resolution_type, reason, context_json, created_at
            ) VALUES (
                #{decision.userId}, #{decision.traceId}, #{decision.sessionId}, #{decision.conflictKey},
                #{decision.conflictDomain}, #{decision.canonicalEntity}, #{decision.likeMemoryItemId},
                #{decision.dislikeMemoryItemId}, #{decision.selectedMemoryItemId},
                #{decision.selectedPreference}, #{decision.resolutionType}, #{decision.reason},
                #{decision.contextJson}, #{decision.createdAt}
            )
            ON DUPLICATE KEY UPDATE id = id
            """)
    int insertIdempotent(@Param("decision") MemoryConflictDecision decision);

    @Delete("""
            DELETE FROM memory_conflicts
            WHERE user_id = #{userId}
              AND (like_memory_item_id = #{memoryItemId} OR dislike_memory_item_id = #{memoryItemId})
            """)
    int deleteOwnedForMemory(@Param("userId") Long userId, @Param("memoryItemId") Long memoryItemId);

    @Delete("DELETE FROM memory_conflicts WHERE user_id = #{userId}")
    int deleteAllOwned(@Param("userId") Long userId);
}
