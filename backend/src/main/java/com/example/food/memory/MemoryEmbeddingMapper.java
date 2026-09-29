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
public interface MemoryEmbeddingMapper extends BaseMapper<MemoryEmbedding> {
    @Select("""
            SELECT user_id, source_kind, source_id, source_version, memory_type, embedding_model,
                   dimensions, embedding_json, created_at, updated_at
            FROM memory_embeddings
            WHERE user_id = #{userId} AND embedding_model = #{model}
            ORDER BY id DESC LIMIT #{limit}
            """)
    List<MemoryEmbedding> listOwnedVectors(@Param("userId") Long userId, @Param("model") String model,
                                          @Param("limit") int limit);

    @Select("""
            SELECT * FROM memory_embeddings
            WHERE user_id = #{userId} AND source_kind = #{sourceKind} AND source_id = #{sourceId}
              AND embedding_model = #{model}
            LIMIT 1
            """)
    MemoryEmbedding findOwnedVector(@Param("userId") Long userId, @Param("sourceKind") String sourceKind,
                                    @Param("sourceId") Long sourceId, @Param("model") String model);

    @Select("""
            SELECT * FROM memory_embeddings
            WHERE embedding_model = #{model} AND ann_indexed_at IS NULL
              AND NOT EXISTS (
                SELECT 1 FROM memory_vector_index_jobs job
                WHERE job.user_id = memory_embeddings.user_id
                  AND job.source_kind = memory_embeddings.source_kind
                  AND job.source_id = memory_embeddings.source_id
                  AND job.embedding_model = memory_embeddings.embedding_model
                  AND job.source_version = memory_embeddings.source_version
                  AND job.operation = 'UPSERT' AND job.status != 'COMPLETE'
              )
            ORDER BY id LIMIT #{limit}
            """)
    List<MemoryEmbedding> findUnindexedVectors(@Param("model") String model, @Param("limit") int limit);

    @Select("""
            SELECT id, user_id, source_kind, source_id, source_version, embedding_model, dimensions
            FROM memory_embeddings
            WHERE embedding_model = #{model} AND ann_indexed_at IS NOT NULL AND id > #{afterId}
            ORDER BY id LIMIT #{limit}
            """)
    List<MemoryEmbedding> listIndexedVectorsAfterId(@Param("model") String model,
                                                     @Param("afterId") long afterId,
                                                     @Param("limit") int limit);

    @Select("""
            <script>
            SELECT user_id, source_kind, source_id, source_version, embedding_model
            FROM memory_embeddings
            WHERE embedding_model = #{model} AND ann_indexed_at IS NOT NULL AND (
              <foreach collection="identities" item="identity" separator=" OR ">
                (user_id = #{identity.userId} AND source_kind = #{identity.sourceKind}
                 AND source_id = #{identity.sourceId} AND source_version = #{identity.sourceVersion})
              </foreach>
            )
            </script>
            """)
    List<MemoryEmbedding> findIndexedVectorsByIdentities(@Param("model") String model,
                                                          @Param("identities") List<MemoryEmbedding> identities);

    @Select("""
            SELECT COUNT(*) FROM memory_embeddings WHERE embedding_model = #{model}
            """)
    int countVectorsByModel(@Param("model") String model);

    @Select("""
            SELECT COUNT(*) FROM memory_embeddings
            WHERE embedding_model = #{model} AND ann_indexed_at IS NOT NULL
            """)
    int countIndexedVectorsByModel(@Param("model") String model);

    @Update("""
            UPDATE memory_embeddings SET ann_indexed_at = NULL
            WHERE embedding_model = #{model} AND ann_indexed_at IS NOT NULL
            """)
    int clearAnnIndexedAtForModel(@Param("model") String model);

    @Update("""
            UPDATE memory_embeddings SET ann_indexed_at = #{indexedAt}
            WHERE user_id = #{userId} AND source_kind = #{sourceKind} AND source_id = #{sourceId}
              AND embedding_model = #{model} AND source_version = #{sourceVersion}
              AND dimensions = #{dimensions}
            """)
    int markAnnIndexed(@Param("userId") Long userId, @Param("sourceKind") String sourceKind,
                       @Param("sourceId") Long sourceId, @Param("model") String model,
                       @Param("sourceVersion") Integer sourceVersion, @Param("dimensions") Integer dimensions,
                       @Param("indexedAt") LocalDateTime indexedAt);

    @Select("""
            <script>
            SELECT i.user_id AS user_id, 'MEMORY_ITEM' AS source_kind, i.id AS source_id,
                   i.version AS source_version, i.memory_type AS memory_type,
                   CONCAT_WS(' ', i.canonical_entity, i.canonical_category, i.memory_type,
                             i.preference, i.scope, i.temporal_type) AS content
            FROM memory_items i
            LEFT JOIN user_memory_settings s ON s.user_id = i.user_id
            LEFT JOIN memory_embeddings v ON v.user_id = i.user_id AND v.source_kind = 'MEMORY_ITEM'
                 AND v.source_id = i.id AND v.embedding_model = #{model}
            LEFT JOIN memory_embedding_index_jobs j ON j.user_id = i.user_id AND j.source_kind = 'MEMORY_ITEM'
                 AND j.source_id = i.id AND j.embedding_model = #{model}
            WHERE i.status = 'ACTIVE' AND i.deleted_at IS NULL
              AND (s.user_id IS NULL OR s.personalization_enabled = TRUE)
              <if test='userId != null'>AND i.user_id = #{userId}</if>
              AND (v.id IS NULL OR v.source_version != i.version OR v.dimensions != #{dimensions})
              AND (j.id IS NULL OR j.source_version != i.version OR j.dimensions != #{dimensions}
                   OR j.status = 'COMPLETE'
                   OR (j.status IN ('PENDING', 'RETRY') AND j.available_at &lt;= #{now})
                   OR (j.status = 'PROCESSING' AND j.lease_until &lt;= #{now}))
            UNION ALL
            SELECT e.user_id AS user_id, 'EPISODE' AS source_kind, e.id AS source_id,
                   e.version AS source_version, e.episode_type AS memory_type,
                   CONCAT_WS(' ', e.episode_type, e.summary, e.source_type, e.payload_json) AS content
            FROM memory_episodes e
            LEFT JOIN user_memory_settings s ON s.user_id = e.user_id
            LEFT JOIN memory_embeddings v ON v.user_id = e.user_id AND v.source_kind = 'EPISODE'
                 AND v.source_id = e.id AND v.embedding_model = #{model}
            LEFT JOIN memory_embedding_index_jobs j ON j.user_id = e.user_id AND j.source_kind = 'EPISODE'
                 AND j.source_id = e.id AND j.embedding_model = #{model}
            WHERE e.deleted_at IS NULL AND e.status != 'REJECTED'
              AND (s.user_id IS NULL OR s.personalization_enabled = TRUE)
              <if test='userId != null'>AND e.user_id = #{userId}</if>
              AND (v.id IS NULL OR v.source_version != e.version OR v.dimensions != #{dimensions})
              AND (j.id IS NULL OR j.source_version != e.version OR j.dimensions != #{dimensions}
                   OR j.status = 'COMPLETE'
                   OR (j.status IN ('PENDING', 'RETRY') AND j.available_at &lt;= #{now})
                   OR (j.status = 'PROCESSING' AND j.lease_until &lt;= #{now}))
            ORDER BY user_id, source_kind, source_id LIMIT #{limit}
            </script>
            """)
    List<MemoryEmbeddingCandidate> findUnindexedCandidates(@Param("model") String model,
                                                           @Param("dimensions") int dimensions,
                                                           @Param("limit") int limit,
                                                           @Param("userId") Long userId,
                                                           @Param("now") LocalDateTime now);

    @Update("""
            UPDATE memory_embeddings
            SET memory_type = #{row.memoryType}, source_version = #{row.sourceVersion},
                dimensions = #{row.dimensions}, embedding_json = #{row.embeddingJson},
                ann_indexed_at = NULL,
                updated_at = CURRENT_TIMESTAMP
            WHERE user_id = #{row.userId} AND source_kind = #{row.sourceKind}
              AND source_id = #{row.sourceId} AND embedding_model = #{row.embeddingModel}
            """)
    int updateOwnedVector(@Param("row") MemoryEmbedding row);

    @Insert("""
            INSERT INTO memory_embeddings
                (user_id, source_kind, source_id, memory_type, source_version, embedding_model,
                 dimensions, embedding_json, ann_indexed_at, created_at, updated_at)
            VALUES
                (#{userId}, #{sourceKind}, #{sourceId}, #{memoryType}, #{sourceVersion}, #{embeddingModel},
                 #{dimensions}, #{embeddingJson}, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """)
    int insertVector(MemoryEmbedding row);

    @Delete("DELETE FROM memory_embeddings WHERE user_id = #{userId} AND source_kind = #{sourceKind} AND source_id = #{sourceId}")
    int deleteOwnedSource(@Param("userId") Long userId, @Param("sourceKind") String sourceKind,
                          @Param("sourceId") Long sourceId);

    @Delete("DELETE FROM memory_embeddings WHERE user_id = #{userId}")
    int deleteAllOwned(@Param("userId") Long userId);
}
