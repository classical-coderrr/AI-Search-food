package com.example.food.memory;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Delete;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface MemorySessionMapper extends BaseMapper<MemorySession> {

    @Select("""
            SELECT * FROM agent_sessions
            WHERE id = #{sessionId} AND user_id = #{userId} AND deleted_at IS NULL
              AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP)
            """)
    MemorySession findOwned(@Param("userId") Long userId, @Param("sessionId") Long sessionId);

    @Select("""
            SELECT * FROM agent_sessions
            WHERE user_id = #{userId} AND session_key = #{sessionKey} AND deleted_at IS NULL
            """)
    MemorySession findOwnedByKey(@Param("userId") Long userId, @Param("sessionKey") String sessionKey);

    @Update("""
            UPDATE agent_sessions
            SET conversation_id = #{conversationId},
                status = #{status},
                current_task = #{currentTask},
                current_goal = #{currentGoal},
                context_json = #{contextJson},
                selected_memory_ids_json = #{selectedMemoryIdsJson},
                retrieved_knowledge_ids_json = #{retrievedKnowledgeIdsJson},
                agent_state_json = #{agentStateJson},
                last_activity_at = CURRENT_TIMESTAMP,
                expires_at = #{expiresAt},
                ended_at = NULL,
                updated_at = CURRENT_TIMESTAMP,
                version = version + 1
            WHERE id = #{sessionId}
              AND user_id = #{userId}
              AND version = #{version}
              AND deleted_at IS NULL
            """)
    int updateOwned(
            @Param("userId") Long userId,
            @Param("sessionId") Long sessionId,
            @Param("version") Integer version,
            @Param("conversationId") Long conversationId,
            @Param("status") String status,
            @Param("currentTask") String currentTask,
            @Param("currentGoal") String currentGoal,
            @Param("contextJson") String contextJson,
            @Param("selectedMemoryIdsJson") String selectedMemoryIdsJson,
            @Param("retrievedKnowledgeIdsJson") String retrievedKnowledgeIdsJson,
            @Param("agentStateJson") String agentStateJson,
            @Param("expiresAt") java.time.LocalDateTime expiresAt
    );

    @Update("""
            UPDATE agent_sessions
            SET status = #{status},
                ended_at = CURRENT_TIMESTAMP,
                last_activity_at = CURRENT_TIMESTAMP,
                updated_at = CURRENT_TIMESTAMP,
                version = version + 1
            WHERE id = #{sessionId}
              AND user_id = #{userId}
              AND version = #{version}
              AND deleted_at IS NULL
            """)
    int closeOwned(
            @Param("userId") Long userId,
            @Param("sessionId") Long sessionId,
            @Param("version") Integer version,
            @Param("status") String status
    );

    @Update("""
            UPDATE agent_sessions
            SET deleted_at = CURRENT_TIMESTAMP,
                updated_at = CURRENT_TIMESTAMP,
                version = version + 1
            WHERE id = #{sessionId}
              AND user_id = #{userId}
              AND version = #{version}
              AND deleted_at IS NULL
            """)
    int softDeleteOwned(
            @Param("userId") Long userId,
            @Param("sessionId") Long sessionId,
            @Param("version") Integer version
    );

    @Select("""
            SELECT id
            FROM agent_sessions
            WHERE deleted_at IS NULL
              AND status != 'EXPIRED'
              AND expires_at IS NOT NULL
              AND NOT (expires_at > #{now})
            ORDER BY expires_at, id
            LIMIT #{limit}
            """)
    List<Long> findExpiredIds(@Param("now") LocalDateTime now, @Param("limit") int limit);

    @Update("""
            <script>
            UPDATE agent_sessions
            SET status = 'EXPIRED',
                current_task = NULL,
                current_goal = NULL,
                context_json = NULL,
                selected_memory_ids_json = NULL,
                retrieved_knowledge_ids_json = NULL,
                agent_state_json = NULL,
                ended_at = COALESCE(ended_at, expires_at),
                updated_at = CURRENT_TIMESTAMP,
                version = version + 1
            WHERE id IN
            <foreach collection='ids' item='id' open='(' separator=',' close=')'>
                #{id}
            </foreach>
              AND deleted_at IS NULL
              AND status != 'EXPIRED'
              AND expires_at IS NOT NULL
              AND expires_at &lt;= #{now}
            </script>
            """)
    int expireDue(@Param("ids") List<Long> ids, @Param("now") LocalDateTime now);

    @Delete("DELETE FROM agent_sessions WHERE user_id = #{userId}")
    int deleteAllOwned(@Param("userId") Long userId);
}
