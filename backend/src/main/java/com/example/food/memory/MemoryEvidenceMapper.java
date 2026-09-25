package com.example.food.memory;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Delete;

@Mapper
public interface MemoryEvidenceMapper extends BaseMapper<MemoryEvidence> {

    @Select("""
            SELECT * FROM memory_evidence
            WHERE candidate_id = #{candidateId}
              AND evidence_key = #{evidenceKey}
              AND deleted_at IS NULL
            """)
    MemoryEvidence findByCandidateAndKey(
            @Param("candidateId") Long candidateId,
            @Param("evidenceKey") String evidenceKey
    );

    @Delete("DELETE FROM memory_evidence WHERE user_id = #{userId}")
    int deleteAllOwned(@Param("userId") Long userId);
}
