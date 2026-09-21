package com.example.food.admin.dashboard;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AgentObservabilityAlertMapper extends BaseMapper<AgentObservabilityAlert> {

    @Select("SELECT * FROM agent_observability_alerts WHERE dedupe_key = #{dedupeKey}")
    AgentObservabilityAlert findByDedupeKey(@Param("dedupeKey") String dedupeKey);

    @Select("""
            SELECT * FROM agent_observability_alerts
            ORDER BY CASE WHEN status = 'OPEN' THEN 0 ELSE 1 END,
                     last_seen_at DESC, id DESC
            LIMIT #{limit}
            """)
    List<AgentObservabilityAlert> findRecent(@Param("limit") int limit);
}
