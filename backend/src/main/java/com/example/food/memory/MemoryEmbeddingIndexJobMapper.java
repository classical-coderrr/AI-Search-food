package com.example.food.memory;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface MemoryEmbeddingIndexJobMapper extends BaseMapper<MemoryEmbeddingIndexJob> {
    @Update("""
            UPDATE memory_embedding_index_jobs
            SET attempts = CASE
                    WHEN source_version != #{sourceVersion} OR dimensions != #{dimensions} OR status = 'COMPLETE' THEN 1
                    WHEN attempts < 2147483647 THEN attempts + 1
                    ELSE attempts
                END,
                last_error = CASE
                    WHEN source_version != #{sourceVersion} OR dimensions != #{dimensions} OR status = 'COMPLETE' THEN NULL
                    ELSE last_error
                END,
                source_version = #{sourceVersion}, dimensions = #{dimensions}, status = 'PROCESSING',
                available_at = #{now}, lease_token = #{leaseToken}, lease_until = #{leaseUntil},
                completed_at = NULL, updated_at = CURRENT_TIMESTAMP
            WHERE user_id = #{userId} AND source_kind = #{sourceKind} AND source_id = #{sourceId}
              AND embedding_model = #{model}
              AND (source_version != #{sourceVersion} OR dimensions != #{dimensions}
                   OR status = 'COMPLETE'
                   OR ((status = 'PENDING' OR status = 'RETRY') AND available_at <= #{now})
                   OR (status = 'PROCESSING' AND (lease_until IS NULL OR lease_until <= #{now})))
            """)
    int claimExisting(@Param("userId") Long userId, @Param("sourceKind") String sourceKind,
                      @Param("sourceId") Long sourceId, @Param("sourceVersion") Integer sourceVersion,
                      @Param("model") String model, @Param("dimensions") int dimensions,
                      @Param("now") LocalDateTime now, @Param("leaseToken") String leaseToken,
                      @Param("leaseUntil") LocalDateTime leaseUntil);

    @Insert("""
            INSERT INTO memory_embedding_index_jobs
                (user_id, source_kind, source_id, source_version, embedding_model, dimensions,
                 status, attempts, available_at, lease_token, lease_until)
            VALUES (#{userId}, #{sourceKind}, #{sourceId}, #{sourceVersion}, #{model}, #{dimensions},
                    'PROCESSING', 1, #{now}, #{leaseToken}, #{leaseUntil})
            """)
    int insertProcessing(@Param("userId") Long userId, @Param("sourceKind") String sourceKind,
                         @Param("sourceId") Long sourceId, @Param("sourceVersion") Integer sourceVersion,
                         @Param("model") String model, @Param("dimensions") int dimensions,
                         @Param("now") LocalDateTime now, @Param("leaseToken") String leaseToken,
                         @Param("leaseUntil") LocalDateTime leaseUntil);

    @Select("""
            SELECT * FROM memory_embedding_index_jobs
            WHERE user_id = #{userId} AND source_kind = #{sourceKind} AND source_id = #{sourceId}
              AND embedding_model = #{model} AND lease_token = #{leaseToken} AND status = 'PROCESSING'
            """)
    MemoryEmbeddingIndexJob findClaimed(@Param("userId") Long userId, @Param("sourceKind") String sourceKind,
                                        @Param("sourceId") Long sourceId, @Param("model") String model,
                                        @Param("leaseToken") String leaseToken);

    @Update("""
            UPDATE memory_embedding_index_jobs
            SET status = 'COMPLETE', completed_at = #{completedAt}, lease_token = NULL,
                lease_until = NULL, last_error = NULL, updated_at = CURRENT_TIMESTAMP
            WHERE id = #{id} AND status = 'PROCESSING' AND lease_token = #{leaseToken}
            """)
    int complete(@Param("id") Long id, @Param("leaseToken") String leaseToken,
                 @Param("completedAt") LocalDateTime completedAt);

    @Update("""
            UPDATE memory_embedding_index_jobs
            SET status = 'RETRY', available_at = #{availableAt}, lease_token = NULL,
                lease_until = NULL, last_error = #{lastError}, updated_at = CURRENT_TIMESTAMP
            WHERE id = #{id} AND status = 'PROCESSING' AND lease_token = #{leaseToken}
            """)
    int retry(@Param("id") Long id, @Param("leaseToken") String leaseToken,
              @Param("availableAt") LocalDateTime availableAt, @Param("lastError") String lastError);

    @Delete("DELETE FROM memory_embedding_index_jobs WHERE user_id = #{userId} AND source_kind = #{sourceKind} AND source_id = #{sourceId}")
    int deleteOwnedSource(@Param("userId") Long userId, @Param("sourceKind") String sourceKind,
                          @Param("sourceId") Long sourceId);

    @Delete("DELETE FROM memory_embedding_index_jobs WHERE user_id = #{userId}")
    int deleteAllOwned(@Param("userId") Long userId);
}
