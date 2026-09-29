package com.example.food.memory;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface MemoryVectorIndexJobMapper extends BaseMapper<MemoryVectorIndexJob> {
    @Select("""
            SELECT COUNT(*) FROM memory_vector_index_jobs
            WHERE user_id = #{userId} AND status != 'COMPLETE'
            """)
    int countUnfinishedForUser(@Param("userId") Long userId);

    @Select("""
            SELECT COUNT(*) FROM memory_vector_index_jobs
            WHERE embedding_model = #{model} AND operation = 'UPSERT' AND status != 'COMPLETE'
            """)
    int countOpenUpsertJobsByModel(@Param("model") String model);

    @Select("""
            SELECT COUNT(*) FROM memory_embeddings
            WHERE user_id = #{userId} AND embedding_model = #{model} AND ann_indexed_at IS NULL
            """)
    int countUnindexedVectors(@Param("userId") Long userId, @Param("model") String model);

    @Select("""
            SELECT COUNT(*) FROM memory_vector_index_jobs
            WHERE user_id = #{userId} AND source_kind = #{sourceKind} AND source_id = #{sourceId}
              AND embedding_model = #{model} AND source_version = #{sourceVersion}
              AND operation = 'UPSERT' AND status != 'COMPLETE'
            """)
    int countOpenUpsert(@Param("userId") Long userId, @Param("sourceKind") String sourceKind,
                        @Param("sourceId") Long sourceId, @Param("model") String model,
                        @Param("sourceVersion") Integer sourceVersion);

    @Select("""
            SELECT COUNT(*) FROM memory_vector_index_jobs
            WHERE user_id = #{userId} AND source_kind = #{sourceKind} AND source_id = #{sourceId}
              AND embedding_model = '*' AND operation = 'DELETE_SOURCE' AND status != 'COMPLETE'
            """)
    int countOpenSourceDelete(@Param("userId") Long userId, @Param("sourceKind") String sourceKind,
                              @Param("sourceId") Long sourceId);

    @Select("""
            SELECT COUNT(*) FROM memory_vector_index_jobs
            WHERE user_id = #{userId} AND source_kind = 'USER' AND source_id = #{userId}
              AND operation = 'DELETE_USER' AND status != 'COMPLETE'
            """)
    int countOpenUserDelete(@Param("userId") Long userId);

    @Select("""
            SELECT status, COUNT(*) AS job_count
            FROM memory_vector_index_jobs
            WHERE embedding_model = #{model} AND operation = 'UPSERT'
            GROUP BY status
            """)
    List<Map<String, Object>> countUpsertJobsByStatus(@Param("model") String model);

    @Insert("""
            INSERT INTO memory_vector_index_jobs
                (user_id, source_kind, source_id, embedding_model, source_version, dimensions, operation,
                 status, attempts, available_at, created_at, updated_at)
            VALUES (#{job.userId}, #{job.sourceKind}, #{job.sourceId}, #{job.embeddingModel},
                    #{job.sourceVersion}, #{job.dimensions}, #{job.operation}, 'PENDING', 0,
                    #{now}, #{now}, #{now})
            """)
    int insertJob(@Param("job") MemoryVectorIndexJob job, @Param("now") LocalDateTime now);

    @Select("""
            SELECT * FROM memory_vector_index_jobs candidate
            WHERE ((candidate.status IN ('PENDING', 'RETRY') AND candidate.available_at <= #{now})
                OR (candidate.status = 'PROCESSING' AND (candidate.lease_until IS NULL OR candidate.lease_until <= #{now})))
              AND NOT EXISTS (
                SELECT 1 FROM memory_vector_index_jobs earlier
                WHERE earlier.user_id = candidate.user_id AND earlier.id < candidate.id
                  AND earlier.status != 'COMPLETE'
              )
            ORDER BY candidate.id
            LIMIT 1
            """)
    MemoryVectorIndexJob findNextClaimable(@Param("now") LocalDateTime now);

    @Update("""
            UPDATE memory_vector_index_jobs
            SET status = 'PROCESSING', lease_token = #{leaseToken}, lease_until = #{leaseUntil},
                attempts = CASE WHEN attempts < 2147483647 THEN attempts + 1 ELSE attempts END,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{id}
              AND ((status IN ('PENDING', 'RETRY') AND available_at <= #{now})
                OR (status = 'PROCESSING' AND (lease_until IS NULL OR lease_until <= #{now})))
            """)
    int claim(@Param("id") Long id, @Param("leaseToken") String leaseToken,
              @Param("now") LocalDateTime now, @Param("leaseUntil") LocalDateTime leaseUntil);

    @Select("""
            SELECT * FROM memory_vector_index_jobs
            WHERE id = #{id} AND status = 'PROCESSING' AND lease_token = #{leaseToken}
            """)
    MemoryVectorIndexJob findClaimed(@Param("id") Long id, @Param("leaseToken") String leaseToken);

    @Update("""
            UPDATE memory_vector_index_jobs
            SET status = 'COMPLETE', lease_token = NULL, lease_until = NULL, last_error = NULL,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{id} AND status = 'PROCESSING' AND lease_token = #{leaseToken}
            """)
    int complete(@Param("id") Long id, @Param("leaseToken") String leaseToken);

    @Update("""
            UPDATE memory_vector_index_jobs
            SET status = 'RETRY', available_at = #{availableAt}, lease_token = NULL, lease_until = NULL,
                last_error = #{errorCode}, updated_at = CURRENT_TIMESTAMP
            WHERE id = #{id} AND status = 'PROCESSING' AND lease_token = #{leaseToken}
            """)
    int retry(@Param("id") Long id, @Param("leaseToken") String leaseToken,
              @Param("availableAt") LocalDateTime availableAt, @Param("errorCode") String errorCode);
}
