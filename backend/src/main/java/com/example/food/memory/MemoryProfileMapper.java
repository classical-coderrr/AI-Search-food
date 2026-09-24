package com.example.food.memory;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface MemoryProfileMapper extends BaseMapper<MemoryProfile> {

    @Select("""
            SELECT * FROM memory_profiles
            WHERE user_id = #{userId}
              AND deleted_at IS NULL
            """)
    MemoryProfile findOwned(@Param("userId") Long userId);

    @Update("""
            UPDATE memory_profiles
            SET profile_json = #{profile.profileJson},
                profile_version = #{profile.profileVersion},
                source_revision = #{profile.sourceRevision},
                rebuilt_at = #{profile.rebuiltAt},
                version = version + 1,
                updated_at = CURRENT_TIMESTAMP
            WHERE user_id = #{userId}
              AND version = #{version}
              AND deleted_at IS NULL
            """)
    int updateOwned(
            @Param("userId") Long userId,
            @Param("profile") MemoryProfile profile,
            @Param("version") Integer version
    );
}
