package com.example.food.memory;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Delete;

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
            WHERE user_id = #{userId}
              AND deleted_at IS NULL
              AND status != 'REJECTED'
              AND id IN
              <foreach collection='episodeIds' item='episodeId' open='(' separator=',' close=')'>
                #{episodeId}
              </foreach>
            ORDER BY occurred_at DESC, id DESC
            </script>
            """)
    List<MemoryEpisode> findOwnedByIds(
            @Param("userId") Long userId,
            @Param("episodeIds") List<Long> episodeIds
    );

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

    @Select("""
            <script>
            SELECT * FROM memory_episodes
            WHERE user_id = #{userId}
              AND deleted_at IS NULL
              AND status != 'REJECTED'
            <if test='episodeTypes != null and !episodeTypes.isEmpty()'>
              AND episode_type IN
              <foreach collection='episodeTypes' item='type' open='(' separator=',' close=')'>#{type}</foreach>
            </if>
            <if test='scenes != null and !scenes.isEmpty()'>
              AND (<foreach collection='scenes' item='scene' separator=' OR '>
                   LOWER(payload_json) LIKE LOWER(CONCAT('%', #{scene}, '%'))
              </foreach>)
            </if>
            <if test='mealTypes != null and !mealTypes.isEmpty()'>
              AND (<foreach collection='mealTypes' item='mealType' separator=' OR '>
                   LOWER(payload_json) LIKE LOWER(CONCAT('%', #{mealType}, '%'))
              </foreach>)
            </if>
            <if test='ingredients != null and !ingredients.isEmpty()'>
              AND (<foreach collection='ingredients' item='ingredient' separator=' OR '>
                   LOWER(payload_json) LIKE LOWER(CONCAT('%', #{ingredient}, '%'))
              </foreach>)
            </if>
            <if test='dietGoals != null and !dietGoals.isEmpty()'>
              AND (<foreach collection='dietGoals' item='goal' separator=' OR '>
                   LOWER(payload_json) LIKE LOWER(CONCAT('%', #{goal}, '%'))
              </foreach>)
            </if>
            <if test='timeFrom != null'>AND occurred_at &gt;= #{timeFrom}</if>
            <if test='timeTo != null'>AND occurred_at &lt;= #{timeTo}</if>
            <if test='minImportance != null'>AND importance &gt;= #{minImportance}</if>
            ORDER BY occurred_at DESC, id DESC
            LIMIT #{limit}
            </script>
            """)
    List<MemoryEpisode> searchOwnedForRetrieval(
            @Param("userId") Long userId,
            @Param("episodeTypes") List<String> episodeTypes,
            @Param("scenes") List<String> scenes,
            @Param("mealTypes") List<String> mealTypes,
            @Param("ingredients") List<String> ingredients,
            @Param("dietGoals") List<String> dietGoals,
            @Param("timeFrom") java.time.LocalDateTime timeFrom,
            @Param("timeTo") java.time.LocalDateTime timeTo,
            @Param("minImportance") java.math.BigDecimal minImportance,
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

    @Delete("DELETE FROM memory_episodes WHERE user_id = #{userId}")
    int deleteAllOwned(@Param("userId") Long userId);
}
