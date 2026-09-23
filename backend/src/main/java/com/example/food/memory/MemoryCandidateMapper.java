package com.example.food.memory;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

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
}
