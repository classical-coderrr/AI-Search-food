package com.example.food.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Component
public class MemoryVectorIndexWorker {
    private static final Logger log = LoggerFactory.getLogger(MemoryVectorIndexWorker.class);
    private static final Duration CONSISTENCY_RETRY_DELAY = Duration.ofSeconds(30);

    private final MemoryVectorIndexJobService jobService;
    private final MemoryEmbeddingMapper embeddingMapper;
    private final QdrantMemoryVectorIndex vectorIndex;
    private final QdrantMemoryVectorProperties qdrantProperties;
    private final MemoryEmbeddingProperties embeddingProperties;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private volatile long nextConsistencyCheckNanos;
    private volatile long nextIdentityAuditNanos;
    private volatile long identityAuditCursor;
    private volatile JsonNode qdrantAuditCursor;

    public MemoryVectorIndexWorker(MemoryVectorIndexJobService jobService,
                                   MemoryEmbeddingMapper embeddingMapper,
                                   QdrantMemoryVectorIndex vectorIndex,
                                   QdrantMemoryVectorProperties qdrantProperties,
                                   MemoryEmbeddingProperties embeddingProperties,
                                   ObjectMapper objectMapper,
                                   Clock clock) {
        this.jobService = jobService;
        this.embeddingMapper = embeddingMapper;
        this.vectorIndex = vectorIndex;
        this.qdrantProperties = qdrantProperties;
        this.embeddingProperties = embeddingProperties;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.memory.vector.qdrant.poll-interval:PT5S}")
    public void processPending() {
        if (!vectorIndex.enabled()) return;
        try {
            if (!vectorIndex.collectionExists()) {
                jobService.requestRebuild(embeddingProperties.model());
            }
            vectorIndex.ensureCollection();
            String model = embeddingProperties.model();
            jobService.enqueueMissingVectors(model, qdrantProperties.pollBatchSize());
            reconcileIfDue(model);
        } catch (RuntimeException unavailable) {
            log.warn("Memory ANN index is unavailable; MySQL vector fallback remains active ({})",
                    unavailable.getClass().getSimpleName());
            return;
        }

        int limit = Math.max(1, Math.min(qdrantProperties.pollBatchSize(), 500));
        for (int i = 0; i < limit; i++) {
            MemoryVectorIndexJob job;
            try {
                job = jobService.claimNext();
            } catch (RuntimeException failure) {
                log.error("Memory ANN index job claim failed", failure);
                return;
            }
            if (job == null) return;
            process(job);
        }
    }

    private void reconcileIfDue(String model) {
        long now = System.nanoTime();
        if (!StringUtils.hasText(model)) return;
        Duration consistencyInterval = positiveOr(qdrantProperties.consistencyCheckInterval(), Duration.ofMinutes(5));
        Duration identityAuditInterval = positiveOr(qdrantProperties.identityAuditInterval(), Duration.ofSeconds(5));
        boolean consistencyDue = now >= nextConsistencyCheckNanos;
        boolean identityAuditDue = now >= nextIdentityAuditNanos;
        if (!consistencyDue && !identityAuditDue) return;

        try {
            if (jobService.hasOpenUpsertJobs(model)) return;
            if (consistencyDue) {
                int mysqlIndexedCount = embeddingMapper.countIndexedVectorsByModel(model);
                long qdrantPointCount = vectorIndex.countPointsByModel(model);
                nextConsistencyCheckNanos = System.nanoTime() + consistencyInterval.toNanos();
                if (qdrantPointCount < mysqlIndexedCount) {
                    log.warn("Memory ANN collection is missing indexed points; requesting rebuild model={} mysqlIndexed={} qdrantPoints={}",
                            model, mysqlIndexedCount, qdrantPointCount);
                    jobService.requestRebuild(model);
                    return;
                }
            }
            if (identityAuditDue) auditIndexedVectorIdentities(model, identityAuditInterval);
        } catch (RuntimeException failure) {
            long retryAt = System.nanoTime() + CONSISTENCY_RETRY_DELAY.toNanos();
            nextConsistencyCheckNanos = retryAt;
            nextIdentityAuditNanos = retryAt;
            throw failure;
        }
    }

    private void auditIndexedVectorIdentities(String model, Duration interval) {
        int batchSize = Math.max(1, Math.min(qdrantProperties.identityAuditBatchSize(), 1000));
        QdrantMemoryVectorIndex.ModelPointPage qdrantPage = vectorIndex.scrollModelPointPage(model,
                qdrantAuditCursor, batchSize);
        List<MemoryEmbedding> pointIdentities = qdrantPage.points().stream()
                .filter(QdrantMemoryVectorIndex.ModelPointIdentity::canonicalId)
                .map(identity -> toMemoryEmbeddingIdentity(identity, model))
                .toList();
        Set<String> indexedIdentities = new HashSet<>();
        if (!pointIdentities.isEmpty()) {
            for (MemoryEmbedding row : embeddingMapper.findIndexedVectorsByIdentities(model, pointIdentities)) {
                indexedIdentities.add(identityKey(row));
            }
        }
        List<String> orphanPointIds = qdrantPage.points().stream()
                .filter(point -> !point.canonicalId()
                        || !indexedIdentities.contains(identityKey(point.userId(), point.sourceKind(),
                        point.sourceId(), point.sourceVersion())))
                .map(QdrantMemoryVectorIndex.ModelPointIdentity::id)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
        if (!orphanPointIds.isEmpty()) vectorIndex.deletePointsById(orphanPointIds);

        List<MemoryEmbedding> expected = embeddingMapper.listIndexedVectorsAfterId(model, identityAuditCursor,
                batchSize);
        if (expected.isEmpty() && identityAuditCursor > 0) {
            identityAuditCursor = 0;
            expected = embeddingMapper.listIndexedVectorsAfterId(model, 0, batchSize);
        }
        int queued = 0;
        if (!expected.isEmpty()) {
            List<MemoryEmbedding> missing = vectorIndex.findMissingPoints(expected);
            for (MemoryEmbedding vector : missing) queued += jobService.enqueueRepairUpsert(vector);
            identityAuditCursor = expected.get(expected.size() - 1).getId();
            if (!missing.isEmpty()) {
                log.warn("Memory ANN identity audit queued repairs for missing or mismatched points model={} checked={} queued={}",
                        model, expected.size(), queued);
            }
        }
        qdrantAuditCursor = qdrantPage.nextOffset();
        nextIdentityAuditNanos = System.nanoTime() + interval.toNanos();
        if (!orphanPointIds.isEmpty()) {
            log.warn("Memory ANN identity audit removed orphan points model={} count={}", model, orphanPointIds.size());
        }
    }

    private MemoryEmbedding toMemoryEmbeddingIdentity(QdrantMemoryVectorIndex.ModelPointIdentity identity, String model) {
        MemoryEmbedding row = new MemoryEmbedding();
        row.setUserId(identity.userId());
        row.setSourceKind(identity.sourceKind());
        row.setSourceId(identity.sourceId());
        row.setSourceVersion(identity.sourceVersion());
        row.setEmbeddingModel(model);
        return row;
    }

    private String identityKey(MemoryEmbedding row) {
        return identityKey(row.getUserId(), row.getSourceKind(), row.getSourceId(), row.getSourceVersion());
    }

    private String identityKey(Long userId, String sourceKind, Long sourceId, Integer sourceVersion) {
        return userId + "|" + sourceKind + "|" + sourceId + "|" + sourceVersion;
    }

    private Duration positiveOr(Duration value, Duration fallback) {
        return value == null || value.isNegative() || value.isZero() ? fallback : value;
    }

    private void process(MemoryVectorIndexJob job) {
        try {
            switch (job.getOperation()) {
                case "UPSERT" -> upsert(job);
                case "DELETE_SOURCE" -> vectorIndex.deleteSource(job.getUserId(), job.getSourceKind(), job.getSourceId());
                case "DELETE_USER" -> vectorIndex.deleteAll(job.getUserId());
                default -> throw new IllegalStateException("Unsupported memory vector operation");
            }
            if (!jobService.complete(job)) {
                log.warn("Memory ANN index lease expired before acknowledgement jobId={}", job.getId());
            }
        } catch (RuntimeException failure) {
            try {
                boolean retried = jobService.retry(job, failure.getClass().getSimpleName());
                log.warn("Memory ANN index job deferred jobId={} userId={} attempt={} stateUpdated={}",
                        job.getId(), job.getUserId(), job.getAttempts(), retried);
            } catch (RuntimeException retryFailure) {
                log.error("Memory ANN index retry state could not be saved; lease recovery will retry jobId={}",
                        job.getId(), retryFailure);
            }
        }
    }

    private void upsert(MemoryVectorIndexJob job) {
        MemoryEmbedding row = embeddingMapper.findOwnedVector(job.getUserId(), job.getSourceKind(),
                job.getSourceId(), job.getEmbeddingModel());
        if (row == null) {
            vectorIndex.deleteSource(job.getUserId(), job.getSourceKind(), job.getSourceId());
            return;
        }
        if (!Objects.equals(job.getSourceVersion(), row.getSourceVersion())
                || !Objects.equals(job.getDimensions(), row.getDimensions())) return;
        float[] vector;
        try {
            vector = objectMapper.readValue(row.getEmbeddingJson(), float[].class);
        } catch (IOException | RuntimeException malformed) {
            throw new IllegalStateException("Qdrant source vector is invalid", malformed);
        }
        if (vector.length != row.getDimensions() || vector.length != embeddingProperties.dimensions()) {
            throw new IllegalStateException("Qdrant source vector dimensions do not match configuration");
        }
        for (float component : vector) {
            if (!Float.isFinite(component)) throw new IllegalStateException("Qdrant source vector contains invalid values");
        }
        vectorIndex.upsert(new MemoryVectorDocument(row.getUserId(), row.getSourceKind(), row.getSourceId(),
                row.getMemoryType(), row.getSourceVersion(), row.getEmbeddingModel(), vector));
        embeddingMapper.markAnnIndexed(row.getUserId(), row.getSourceKind(), row.getSourceId(),
                row.getEmbeddingModel(), row.getSourceVersion(), row.getDimensions(), LocalDateTime.now(clock));
    }
}
