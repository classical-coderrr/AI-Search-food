package com.example.food.memory;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Delete;

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

    @Delete("DELETE FROM memory_item_candidates WHERE user_id = #{userId}")
    int deleteAllOwned(@Param("userId") Long userId);
}
