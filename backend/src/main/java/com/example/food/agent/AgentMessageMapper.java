package com.example.food.agent;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AgentMessageMapper extends BaseMapper<AgentMessage> {

    @Select("SELECT * FROM agent_messages WHERE conversation_id = #{conversationId} AND user_id = #{userId} AND block_type = #{blockType} ORDER BY id DESC LIMIT #{limit}")
    List<AgentMessage> findRecentByBlockType(
            @Param("userId") Long userId,
            @Param("conversationId") Long conversationId,
            @Param("blockType") String blockType,
            @Param("limit") int limit
    );

    @Select("""
            SELECT * FROM (
                SELECT * FROM agent_messages
                WHERE conversation_id = #{conversationId}
                  AND user_id = #{userId}
                  AND block_type = 'text'
                ORDER BY id DESC
                LIMIT #{limit}
            ) recent_messages
            ORDER BY id ASC
            """)
    List<AgentMessage> findRecentTextMessages(
            @Param("userId") Long userId,
            @Param("conversationId") Long conversationId,
            @Param("limit") int limit
    );

    @Select("""
            SELECT * FROM (
                SELECT * FROM agent_messages
                WHERE conversation_id = #{conversationId}
                  AND user_id = #{userId}
                ORDER BY id DESC
                LIMIT #{limit}
            ) recent_messages
            ORDER BY id ASC
            """)
    List<AgentMessage> findRecentMessages(
            @Param("userId") Long userId,
            @Param("conversationId") Long conversationId,
            @Param("limit") int limit
    );
}
