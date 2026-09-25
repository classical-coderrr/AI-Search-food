package com.example.food.admin.dashboard;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface MemoryEvaluationRunMapper extends BaseMapper<MemoryEvaluationRun> {
    @Select("SELECT * FROM memory_evaluation_runs ORDER BY started_at DESC, id DESC LIMIT 1")
    MemoryEvaluationRun findLatest();
}
