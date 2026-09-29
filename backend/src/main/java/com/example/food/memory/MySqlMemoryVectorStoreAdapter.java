package com.example.food.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Uses Qdrant ANN when its index is current; MySQL remains the source of truth and exact-search fallback. */
@Repository
public class MySqlMemoryVectorStoreAdapter implements MemoryVectorStoreAdapter {
    private static final Logger log = LoggerFactory.getLogger(MySqlMemoryVectorStoreAdapter.class);
    private final MemoryEmbeddingMapper mapper;
    private final MemoryEmbeddingIndexJobMapper jobMapper;
    private final ObjectMapper objectMapper;
    private final MemoryEmbeddingProperties properties;
    private final MemoryVectorIndexJobService vectorIndexJobService;
    private final QdrantMemoryVectorIndex qdrantIndex;

    @Autowired
    public MySqlMemoryVectorStoreAdapter(MemoryEmbeddingMapper mapper, MemoryEmbeddingIndexJobMapper jobMapper,
                                        ObjectMapper objectMapper,
                                        MemoryEmbeddingProperties properties,
                                        MemoryVectorIndexJobService vectorIndexJobService,
                                        QdrantMemoryVectorIndex qdrantIndex) {
        this.mapper = mapper;
        this.jobMapper = jobMapper;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.vectorIndexJobService = vectorIndexJobService;
        this.qdrantIndex = qdrantIndex;
    }

    public MySqlMemoryVectorStoreAdapter(MemoryEmbeddingMapper mapper, MemoryEmbeddingIndexJobMapper jobMapper,
                                         ObjectMapper objectMapper, MemoryEmbeddingProperties properties) {
        this(mapper, jobMapper, objectMapper, properties, null, null);
    }

    @Override
    @Transactional
    public void upsert(MemoryVectorDocument document) {
        if (document == null || document.userId() == null || document.sourceId() == null
                || document.embedding() == null || document.embedding().length == 0
                || document.model() == null || document.model().isBlank()) return;
        MemoryEmbedding row = new MemoryEmbedding();
        row.setUserId(document.userId());
        row.setSourceKind(document.sourceKind());
        row.setSourceId(document.sourceId());
        row.setMemoryType(document.memoryType());
        row.setSourceVersion(document.sourceVersion());
        row.setEmbeddingModel(document.model());
        row.setDimensions(document.embedding().length);
        try {
            row.setEmbeddingJson(objectMapper.writeValueAsString(document.embedding()));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Memory vector serialization failed", exception);
        }
        if (mapper.updateOwnedVector(row) != 1) {
            try {
                mapper.insertVector(row);
            } catch (DuplicateKeyException concurrentInsert) {
                if (mapper.updateOwnedVector(row) != 1) throw concurrentInsert;
            }
        }
        if (vectorIndexJobService != null) vectorIndexJobService.enqueueUpsert(document);
    }

    @Override
    public List<MemoryVectorMatch> search(Long userId, String model, float[] queryVector, int limit) {
        if (userId == null || userId <= 0 || queryVector == null || queryVector.length == 0 || limit <= 0) {
            return List.of();
        }
        if (qdrantIndex != null && qdrantIndex.enabled() && vectorIndexJobService != null
                && !vectorIndexJobService.needsMySqlFallback(userId, model)) {
            try {
                return qdrantIndex.search(userId, model, queryVector, limit);
            } catch (RuntimeException unavailable) {
                log.warn("Memory ANN search failed; using MySQL vector fallback ({})",
                        unavailable.getClass().getSimpleName());
            }
        }
        return searchMySql(userId, model, queryVector, limit);
    }

    private List<MemoryVectorMatch> searchMySql(Long userId, String model, float[] queryVector, int limit) {
        List<MemoryEmbedding> rows = mapper.listOwnedVectors(userId, model,
                Math.max(1, Math.min(properties.scanLimit(), 100_000)));
        List<MemoryVectorMatch> matches = new ArrayList<>(rows.size());
        for (MemoryEmbedding row : rows) {
            float[] vector = decode(row);
            if (vector == null || vector.length != queryVector.length) continue;
            double similarity = cosine(queryVector, vector);
            if (Double.isFinite(similarity)) {
                matches.add(new MemoryVectorMatch(row.getSourceKind(), row.getSourceId(),
                        Math.max(0.0, Math.min(1.0, similarity))));
            }
        }
        matches.sort(Comparator.comparingDouble(MemoryVectorMatch::similarity).reversed()
                .thenComparing(MemoryVectorMatch::sourceKind)
                .thenComparing(MemoryVectorMatch::sourceId, Comparator.nullsLast(Comparator.naturalOrder())));
        return matches.stream().limit(limit).toList();
    }

    @Override
    @Transactional
    public void deleteSource(Long userId, String sourceKind, Long sourceId) {
        if (userId != null && userId > 0 && sourceId != null) {
            if (vectorIndexJobService != null) vectorIndexJobService.enqueueDeleteSource(userId, sourceKind, sourceId);
            mapper.deleteOwnedSource(userId, sourceKind, sourceId);
            jobMapper.deleteOwnedSource(userId, sourceKind, sourceId);
        }
    }

    @Override
    @Transactional
    public void deleteAll(Long userId) {
        if (userId != null && userId > 0) {
            if (vectorIndexJobService != null) vectorIndexJobService.enqueueDeleteAll(userId);
            mapper.deleteAllOwned(userId);
            jobMapper.deleteAllOwned(userId);
        }
    }

    private float[] decode(MemoryEmbedding row) {
        try {
            float[] vector = objectMapper.readValue(row.getEmbeddingJson(), float[].class);
            if (row.getDimensions() == null || vector.length != row.getDimensions()) return null;
            for (float value : vector) if (!Float.isFinite(value)) return null;
            return vector;
        } catch (IOException | RuntimeException malformed) {
            return null;
        }
    }

    private double cosine(float[] left, float[] right) {
        double dot = 0;
        double leftNorm = 0;
        double rightNorm = 0;
        for (int i = 0; i < left.length; i++) {
            dot += left[i] * right[i];
            leftNorm += left[i] * left[i];
            rightNorm += right[i] * right[i];
        }
        if (leftNorm == 0 || rightNorm == 0) return 0;
        return dot / Math.sqrt(leftNorm * rightNorm);
    }
}
