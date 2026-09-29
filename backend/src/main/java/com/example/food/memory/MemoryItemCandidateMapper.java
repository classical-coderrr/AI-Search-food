package com.example.food.memory;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface MemoryItemCandidateMapper extends BaseMapper<MemoryItemCandidate> {

    @Select("""
            SELECT * FROM memory_item_candidates
            WHERE user_id = #{userId}
              AND memory_item_id = #{memoryItemId}
              AND candidate_id = #{candidateId}
            """)
    MemoryItemCandidate findByPair(
            @Param("userId") Long userId,
            @Param("memoryItemId") Long memoryItemId,
            @Param("candidateId") Long candidateId
    );

    @Select("""
            SELECT DISTINCT i.* FROM memory_items i
            JOIN memory_item_candidates r ON r.memory_item_id = i.id AND r.user_id = i.user_id
            JOIN memory_candidates c ON c.id = r.candidate_id AND c.user_id = r.user_id
            WHERE i.user_id = #{userId}
              AND i.status = 'ACTIVE'
              AND i.deleted_at IS NULL
              AND (i.user_modified IS NULL OR i.user_modified = FALSE)
              AND c.status = 'SUPERSEDED'
              AND c.deleted_at IS NULL
            ORDER BY i.id
            """)
    List<MemoryItem> listUnmodifiedItemsWithSupersededCandidates(@Param("userId") Long userId);

    @Delete("""
            DELETE FROM memory_item_candidates
            WHERE user_id = #{userId}
              AND memory_item_id = #{memoryItemId}
              AND candidate_id IN (
                  SELECT id FROM memory_candidates
                  WHERE user_id = #{userId}
                    AND status = 'SUPERSEDED'
                    AND deleted_at IS NULL
              )
            """)
    int deleteSupersededRelations(
            @Param("userId") Long userId,
            @Param("memoryItemId") Long memoryItemId
    );

    @Delete("DELETE FROM memory_item_candidates WHERE user_id = #{userId}")
    int deleteAllOwned(@Param("userId") Long userId);
}
