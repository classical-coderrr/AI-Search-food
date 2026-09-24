package com.example.food.memory;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface MemoryItemMapper extends BaseMapper<MemoryItem> {

    @Select("""
            SELECT * FROM memory_items
            WHERE user_id = #{userId}
              AND consolidation_key = #{consolidationKey}
              AND deleted_at IS NULL
            """)
    MemoryItem findOwnedByKey(
            @Param("userId") Long userId,
            @Param("consolidationKey") String consolidationKey
    );

    @Select("""
            SELECT * FROM memory_items
            WHERE user_id = #{userId}
              AND status = 'ACTIVE'
              AND deleted_at IS NULL
            ORDER BY confidence DESC, last_seen_at DESC, id DESC
            LIMIT #{limit}
            """)
    List<MemoryItem> listActive(
            @Param("userId") Long userId,
            @Param("limit") int limit
    );

    @Select("""
            <script>
            SELECT * FROM memory_items
            WHERE user_id = #{userId}
              AND status = 'ACTIVE'
              AND deleted_at IS NULL
            <if test='memoryTypes != null and !memoryTypes.isEmpty()'>
              AND memory_type IN
              <foreach collection='memoryTypes' item='type' open='(' separator=',' close=')'>#{type}</foreach>
            </if>
            <if test='ingredients != null and !ingredients.isEmpty()'>
              AND (<foreach collection='ingredients' item='ingredient' separator=' OR '>
                   canonical_entity LIKE CONCAT('%', #{ingredient}, '%')
              </foreach>)
            </if>
            <if test='dietGoals != null and !dietGoals.isEmpty()'>
              AND (<foreach collection='dietGoals' item='goal' separator=' OR '>
                   canonical_entity LIKE CONCAT('%', #{goal}, '%')
                   OR canonical_id LIKE CONCAT('%', #{goal}, '%')
              </foreach>)
            </if>
            <if test='timeFrom != null'>AND last_seen_at &gt;= #{timeFrom}</if>
            <if test='timeTo != null'>AND last_seen_at &lt;= #{timeTo}</if>
            <if test='minConfidence != null'>AND confidence &gt;= #{minConfidence}</if>
            <if test='minImportance != null'>AND importance &gt;= #{minImportance}</if>
            ORDER BY last_seen_at DESC, id DESC
            LIMIT #{limit}
            </script>
            """)
    List<MemoryItem> searchActiveForRetrieval(
            @Param("userId") Long userId,
            @Param("memoryTypes") List<String> memoryTypes,
            @Param("ingredients") List<String> ingredients,
            @Param("dietGoals") List<String> dietGoals,
            @Param("timeFrom") java.time.LocalDateTime timeFrom,
            @Param("timeTo") java.time.LocalDateTime timeTo,
            @Param("minConfidence") java.math.BigDecimal minConfidence,
            @Param("minImportance") java.math.BigDecimal minImportance,
            @Param("limit") int limit
    );

    @Update("""
            UPDATE memory_items
            SET strength = #{item.strength},
                confidence = #{item.confidence},
                importance = #{item.importance},
                evidence_count = #{item.evidenceCount},
                occurrence_count = #{item.occurrenceCount},
                source_count = #{item.sourceCount},
                source_candidate_ids_json = #{item.sourceCandidateIdsJson},
                source_episode_ids_json = #{item.sourceEpisodeIdsJson},
                first_seen_at = #{item.firstSeenAt},
                last_seen_at = #{item.lastSeenAt},
                status = #{item.status},
                version = version + 1,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{item.id}
              AND user_id = #{userId}
              AND version = #{version}
              AND deleted_at IS NULL
            """)
    int updateConsolidated(
            @Param("userId") Long userId,
            @Param("item") MemoryItem item,
            @Param("version") Integer version
    );
}
