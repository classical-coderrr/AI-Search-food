package com.example.food.admin.dashboard;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface AgentMetricSnapshotMapper extends BaseMapper<AgentMetricSnapshot> {

    @Select("""
            SELECT * FROM agent_metric_snapshots
            WHERE captured_at >= #{fromTime}
            ORDER BY captured_at ASC, id ASC
            LIMIT #{limit}
            """)
    List<AgentMetricSnapshot> findHistory(
            @Param("fromTime") LocalDateTime fromTime,
            @Param("limit") int limit
    );

    @Delete("DELETE FROM agent_metric_snapshots WHERE captured_at < #{before}")
    int deleteBefore(@Param("before") LocalDateTime before);
}
