package com.example.food.admin.dashboard;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AgentEvaluationCaseResultMapper extends BaseMapper<AgentEvaluationCaseResult> {

    @Select("SELECT * FROM agent_evaluation_case_results WHERE run_id = #{runId} ORDER BY id ASC")
    List<AgentEvaluationCaseResult> findByRunId(@Param("runId") Long runId);
}
