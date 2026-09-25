package com.example.food.memory;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface UserMemorySettingsMapper extends BaseMapper<UserMemorySettings> {

    @Select("""
            SELECT * FROM user_memory_settings
            WHERE user_id = #{userId}
            """)
    UserMemorySettings findOwned(@Param("userId") Long userId);

    @Update("""
            UPDATE user_memory_settings
            SET personalization_enabled = #{enabled},
                version = version + 1,
                updated_at = CURRENT_TIMESTAMP
            WHERE user_id = #{userId}
              AND version = #{version}
            """)
    int updateOwned(
            @Param("userId") Long userId,
            @Param("version") Integer version,
            @Param("enabled") boolean enabled
    );
}
