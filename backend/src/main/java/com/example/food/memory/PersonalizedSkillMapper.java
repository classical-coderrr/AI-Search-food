package com.example.food.memory;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface PersonalizedSkillMapper extends BaseMapper<PersonalizedSkill> {

    @Select("""
            SELECT * FROM personalized_skill
            WHERE user_id = #{userId} AND skill_name = #{skillName}
            """)
    PersonalizedSkill findOwnedForRebuild(@Param("userId") Long userId,
                                          @Param("skillName") String skillName);

    @Select("""
            SELECT * FROM personalized_skill
            WHERE user_id = #{userId} AND skill_name = #{skillName} AND deleted_at IS NULL
            """)
    PersonalizedSkill findActiveOwned(@Param("userId") Long userId,
                                      @Param("skillName") String skillName);

    @Update("""
            UPDATE personalized_skill
            SET strategy_json = #{strategyJson}, confidence = #{confidence},
                evidence_count = #{evidenceCount}, source_memory_ids_json = #{sourceMemoryIdsJson},
                prompt_version = #{promptVersion}, source_profile_version = #{sourceProfileVersion},
                version = version + 1, deleted_at = NULL, updated_at = #{updatedAt}
            WHERE id = #{id} AND user_id = #{userId} AND version = #{version}
              AND source_profile_version <= #{sourceProfileVersion}
            """)
    int updateProjection(@Param("id") Long id,
                         @Param("userId") Long userId,
                         @Param("version") Integer version,
                         @Param("strategyJson") String strategyJson,
                         @Param("confidence") BigDecimal confidence,
                         @Param("evidenceCount") Integer evidenceCount,
                         @Param("sourceMemoryIdsJson") String sourceMemoryIdsJson,
                         @Param("promptVersion") String promptVersion,
                         @Param("sourceProfileVersion") Integer sourceProfileVersion,
                         @Param("updatedAt") LocalDateTime updatedAt);

    @Update("""
            UPDATE personalized_skill
            SET deleted_at = #{deletedAt}, version = version + 1, updated_at = #{deletedAt}
            WHERE user_id = #{userId} AND skill_name = #{skillName}
              AND deleted_at IS NULL AND source_profile_version <= #{sourceProfileVersion}
            """)
    int softDeleteStale(@Param("userId") Long userId,
                        @Param("skillName") String skillName,
                        @Param("sourceProfileVersion") Integer sourceProfileVersion,
                        @Param("deletedAt") LocalDateTime deletedAt);

    @Select("""
            SELECT * FROM personalized_skill
            WHERE user_id = #{userId} AND deleted_at IS NULL
            ORDER BY skill_name
            """)
    List<PersonalizedSkill> listActiveOwned(@Param("userId") Long userId);
}
