package com.example.food.agent;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AgentWriteOperationMapper extends BaseMapper<AgentWriteOperation> {

    @Select("SELECT * FROM agent_write_operations WHERE user_id = #{userId} AND idempotency_key = #{idempotencyKey}")
    AgentWriteOperation findOwnedByKey(
            @Param("userId") Long userId,
            @Param("idempotencyKey") String idempotencyKey
    );

    @Update("""
            UPDATE agent_write_operations
            SET status = 'COMPLETED', result_json = #{resultJson}, result_message = #{resultMessage},
                completed_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP,
                error_code = NULL, error_message = NULL
            WHERE user_id = #{userId} AND idempotency_key = #{idempotencyKey} AND status = 'PROCESSING'
            """)
    int markCompleted(
            @Param("userId") Long userId,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("resultJson") String resultJson,
            @Param("resultMessage") String resultMessage
    );

    @Update("""
            UPDATE agent_write_operations
            SET status = 'FAILED', error_code = #{errorCode}, error_message = #{errorMessage},
                updated_at = CURRENT_TIMESTAMP
            WHERE user_id = #{userId} AND idempotency_key = #{idempotencyKey} AND status = 'PROCESSING'
            """)
    int markFailed(
            @Param("userId") Long userId,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("errorCode") String errorCode,
            @Param("errorMessage") String errorMessage
    );
}
