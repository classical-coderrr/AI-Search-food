package com.example.food.memory;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface MemoryProcessingJobMapper extends BaseMapper<MemoryProcessingJob> {

    @Select("""
            SELECT COUNT(*) > 0 FROM memory_episodes
            WHERE user_id = #{userId} AND id = #{episodeId} AND deleted_at IS NULL
            """)
    boolean episodeOwned(@Param("userId") Long userId, @Param("episodeId") Long episodeId);

    @Select("""
            SELECT * FROM memory_processing_jobs
            WHERE (status = 'PENDING' AND available_at <= #{now})
               OR (status = 'PROCESSING' AND lease_until <= #{now})
            ORDER BY available_at, id
            LIMIT 1
            """)
    MemoryProcessingJob findNextDue(@Param("now") LocalDateTime now);

    @Select("""
            SELECT * FROM memory_processing_jobs
            WHERE id = #{id} AND lease_token = #{leaseToken}
            """)
    MemoryProcessingJob findClaimed(
            @Param("id") Long id,
            @Param("leaseToken") String leaseToken
    );

    @Update("""
            UPDATE memory_processing_jobs
            SET status = 'PROCESSING',
                attempts = attempts + 1,
                lease_token = #{leaseToken},
                lease_until = #{leaseUntil},
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{id}
              AND ((status = 'PENDING' AND available_at <= #{now})
                OR (status = 'PROCESSING' AND lease_until <= #{now}))
            """)
    int claim(
            @Param("id") Long id,
            @Param("leaseToken") String leaseToken,
            @Param("now") LocalDateTime now,
            @Param("leaseUntil") LocalDateTime leaseUntil
    );

    @Update("""
            UPDATE memory_processing_jobs
            SET status = 'COMPLETED',
                completed_at = #{completedAt},
                lease_token = NULL,
                lease_until = NULL,
                last_error = NULL,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{id} AND status = 'PROCESSING' AND lease_token = #{leaseToken}
            """)
    int complete(
            @Param("id") Long id,
            @Param("leaseToken") String leaseToken,
            @Param("completedAt") LocalDateTime completedAt
    );

    @Update("""
            UPDATE memory_processing_jobs
            SET status = CASE WHEN attempts >= #{maxAttempts} THEN 'FAILED' ELSE 'PENDING' END,
                available_at = #{availableAt},
                lease_token = NULL,
                lease_until = NULL,
                last_error = #{lastError},
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{id} AND status = 'PROCESSING' AND lease_token = #{leaseToken}
            """)
    int retryOrFail(
            @Param("id") Long id,
            @Param("leaseToken") String leaseToken,
            @Param("availableAt") LocalDateTime availableAt,
            @Param("maxAttempts") int maxAttempts,
            @Param("lastError") String lastError
    );

    @Select("""
            SELECT e.user_id AS userId, e.id AS episodeId
            FROM memory_episodes e
            LEFT JOIN memory_processing_jobs j
              ON j.user_id = e.user_id AND j.episode_id = e.id
            WHERE e.deleted_at IS NULL
              AND e.status = 'RAW'
              AND e.episode_type IN (
                'RECIPE_SAVED', 'RECIPE_UNSAVED', 'RECIPE_FEEDBACK', 'FINISHED_DISH_REVIEW',
                'USER_PREFERENCE_DECLARED', 'USER_PREFERENCE_CONFIRMED'
              )
              AND j.id IS NULL
            ORDER BY e.id
            LIMIT #{limit}
            """)
    List<MemoryProcessingJobCandidate> findUnqueuedEpisodes(@Param("limit") int limit);

    @Insert("""
            INSERT INTO memory_processing_jobs (user_id, episode_id, status, attempts, available_at)
            VALUES (#{userId}, #{episodeId}, 'PENDING', 0, #{availableAt})
            """)
    int insertPending(
            @Param("userId") Long userId,
            @Param("episodeId") Long episodeId,
            @Param("availableAt") LocalDateTime availableAt
    );

    @Delete("DELETE FROM memory_processing_jobs WHERE user_id = #{userId}")
    int deleteAllOwned(@Param("userId") Long userId);
}
