package com.example.food.memory;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface MemoryEpisodeMapper extends BaseMapper<MemoryEpisode> {

    @Select("""
            SELECT * FROM memory_episodes
            WHERE user_id = #{userId}
              AND idempotency_key = #{idempotencyKey}
              AND deleted_at IS NULL
            """)
    MemoryEpisode findOwnedByIdempotencyKey(
            @Param("userId") Long userId,
            @Param("idempotencyKey") String idempotencyKey
    );

    @Select("""
            SELECT * FROM memory_episodes
            WHERE id = #{episodeId} AND user_id = #{userId} AND deleted_at IS NULL
            """)
    MemoryEpisode findOwned(@Param("userId") Long userId, @Param("episodeId") Long episodeId);

    @Select("""
            <script>
            SELECT * FROM memory_episodes
            WHERE user_id = #{userId} AND deleted_at IS NULL
            <if test='sessionId != null'>AND session_id = #{sessionId}</if>
            <if test='episodeType != null and episodeType != ""'>AND episode_type = #{episodeType}</if>
            ORDER BY occurred_at DESC, id DESC
            LIMIT #{limit}
            </script>
            """)
    List<MemoryEpisode> listOwned(
            @Param("userId") Long userId,
            @Param("sessionId") Long sessionId,
            @Param("episodeType") String episodeType,
            @Param("limit") int limit
    );

    @Update("""
            UPDATE memory_episodes
            SET deleted_at = CURRENT_TIMESTAMP,
                updated_at = CURRENT_TIMESTAMP,
                version = version + 1
            WHERE id = #{episodeId}
              AND user_id = #{userId}
              AND version = #{version}
              AND deleted_at IS NULL
            """)
    int softDeleteOwned(
            @Param("userId") Long userId,
            @Param("episodeId") Long episodeId,
            @Param("version") Integer version
    );
}
