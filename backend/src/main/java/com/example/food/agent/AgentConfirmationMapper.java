package com.example.food.agent;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AgentConfirmationMapper extends BaseMapper<AgentConfirmation> {

    @Select("SELECT * FROM agent_confirmations WHERE id = #{confirmationId} AND user_id = #{userId}")
    AgentConfirmation findOwned(@Param("userId") Long userId, @Param("confirmationId") Long confirmationId);

    @Update("UPDATE agent_confirmations SET status = 'PROCESSING', processing_at = CURRENT_TIMESTAMP, error_code = NULL, error_message = NULL WHERE id = #{confirmationId} AND user_id = #{userId} AND status = 'PENDING'")
    int claim(@Param("userId") Long userId, @Param("confirmationId") Long confirmationId);

    @Update("UPDATE agent_confirmations SET status = 'CONFIRMED', confirmed_at = CURRENT_TIMESTAMP, result_message = #{resultMessage}, error_code = NULL, error_message = NULL WHERE id = #{confirmationId} AND user_id = #{userId} AND status = 'PROCESSING'")
    int markConfirmed(
            @Param("userId") Long userId,
            @Param("confirmationId") Long confirmationId,
            @Param("resultMessage") String resultMessage
    );

    @Update("UPDATE agent_confirmations SET status = 'UNKNOWN_REVIEW', error_code = 'PROCESSING_TIMEOUT', error_message = '操作长时间处于处理中，无法确认是否已完成，请人工复核' WHERE id = #{confirmationId} AND user_id = #{userId} AND status = 'PROCESSING' AND processing_at IS NOT NULL AND processing_at < #{staleBefore}")
    int markUnknownIfStale(
            @Param("userId") Long userId,
            @Param("confirmationId") Long confirmationId,
            @Param("staleBefore") java.time.LocalDateTime staleBefore
    );
}
