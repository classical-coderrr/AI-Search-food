package com.example.food.memory;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class MemoryVectorIndexJobService {
    public record RebuildRequestResult(int vectorRowsReset, int upsertJobsQueued) { }

    private final MemoryVectorIndexJobMapper jobMapper;
    private final MemoryEmbeddingMapper embeddingMapper;
    private final QdrantMemoryVectorProperties qdrantProperties;
    private final MemoryEmbeddingProperties embeddingProperties;
    private final Clock clock;

    public MemoryVectorIndexJobService(MemoryVectorIndexJobMapper jobMapper, MemoryEmbeddingMapper embeddingMapper,
                                       QdrantMemoryVectorProperties qdrantProperties,
                                       MemoryEmbeddingProperties embeddingProperties, Clock clock) {
        this.jobMapper = jobMapper;
        this.embeddingMapper = embeddingMapper;
        this.qdrantProperties = qdrantProperties;
        this.embeddingProperties = embeddingProperties;
        this.clock = clock;
    }

    @Transactional
    public int enqueueMissingVectors(String model, int limit) {
        if (!qdrantProperties.enabled() || !StringUtils.hasText(model) || limit <= 0) return 0;
        int queued = 0;
        for (MemoryEmbedding vector : embeddingMapper.findUnindexedVectors(model, Math.min(limit, 1000))) {
            if (queueUpsert(vector)) queued++;
        }
        return queued;
    }

    @Transactional
    public RebuildRequestResult requestRebuild(String model) {
        if (!qdrantProperties.enabled()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "记忆向量索引未启用");
        }
        if (!StringUtils.hasText(model)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "记忆向量模型配置无效");
        }
        int reset = embeddingMapper.clearAnnIndexedAtForModel(model);
        int batchSize = Math.max(1, Math.min(qdrantProperties.pollBatchSize(), 1000));
        int queued = enqueueMissingVectors(model, batchSize);
        return new RebuildRequestResult(reset, queued);
    }

    @Transactional
    public boolean enqueueUpsert(MemoryVectorDocument document) {
        if (!qdrantProperties.enabled() || document == null || document.userId() == null
                || document.sourceId() == null || !StringUtils.hasText(document.sourceKind())
                || !StringUtils.hasText(document.model())) return false;
        MemoryEmbedding vector = embeddingMapper.findOwnedVector(document.userId(), document.sourceKind(),
                document.sourceId(), document.model());
        return queueUpsert(vector);
    }

    @Transactional
    public int enqueueDeleteSource(Long userId, String sourceKind, Long sourceId) {
        if (!qdrantProperties.enabled() || !valid(userId) || !StringUtils.hasText(sourceKind)
                || sourceId == null || sourceId <= 0
                || jobMapper.countOpenSourceDelete(userId, sourceKind, sourceId) > 0) return 0;
        return insert(userId, sourceKind, sourceId, "*", null, 0, "DELETE_SOURCE");
    }

    @Transactional
    public int enqueueDeleteAll(Long userId) {
        if (!qdrantProperties.enabled() || !valid(userId) || jobMapper.countOpenUserDelete(userId) > 0) return 0;
        return insert(userId, "USER", userId, "*", null, 0, "DELETE_USER");
    }

    @Transactional
    public MemoryVectorIndexJob claimNext() {
        LocalDateTime now = LocalDateTime.now(clock);
        MemoryVectorIndexJob candidate = jobMapper.findNextClaimable(now);
        if (candidate == null) return null;
        String leaseToken = UUID.randomUUID().toString();
        Duration lease = positiveOr(embeddingProperties.leaseDuration(), Duration.ofMinutes(2));
        if (jobMapper.claim(candidate.getId(), leaseToken, now, now.plus(lease)) != 1) return null;
        return jobMapper.findClaimed(candidate.getId(), leaseToken);
    }

    @Transactional
    public boolean complete(MemoryVectorIndexJob job) {
        return validLease(job) && jobMapper.complete(job.getId(), job.getLeaseToken()) == 1;
    }

    @Transactional
    public boolean retry(MemoryVectorIndexJob job, String errorCode) {
        if (!validLease(job)) return false;
        int attempts = job.getAttempts() == null ? 1 : Math.max(1, job.getAttempts());
        Duration base = positiveOr(embeddingProperties.retryBaseDelay(), Duration.ofSeconds(5));
        Duration maximum = positiveOr(embeddingProperties.retryMaxDelay(), Duration.ofMinutes(30));
        long multiplier = 1L << Math.min(20, Math.max(0, attempts - 1));
        Duration delay;
        try {
            delay = base.multipliedBy(multiplier);
        } catch (ArithmeticException overflow) {
            delay = maximum;
        }
        if (delay.compareTo(maximum) > 0) delay = maximum;
        String safeCode = StringUtils.hasText(errorCode) ? errorCode.trim() : "QDRANT_INDEX_FAILED";
        safeCode = safeCode.replaceAll("[^A-Za-z0-9_.$-]", "_");
        if (safeCode.length() > 128) safeCode = safeCode.substring(0, 128);
        return jobMapper.retry(job.getId(), job.getLeaseToken(), LocalDateTime.now(clock).plus(delay), safeCode) == 1;
    }

    public boolean needsMySqlFallback(Long userId, String model) {
        return !valid(userId) || !StringUtils.hasText(model)
                || jobMapper.countUnfinishedForUser(userId) > 0
                || jobMapper.countUnindexedVectors(userId, model) > 0;
    }

    public boolean hasOpenUpsertJobs(String model) {
        return StringUtils.hasText(model) && jobMapper.countOpenUpsertJobsByModel(model) > 0;
    }

    @Transactional
    public int enqueueRepairUpsert(MemoryEmbedding vector) {
        if (!qdrantProperties.enabled() || vector == null) return 0;
        return queueUpsert(vector) ? 1 : 0;
    }

    private boolean queueUpsert(MemoryEmbedding vector) {
        if (vector == null || !valid(vector.getUserId()) || vector.getSourceId() == null
                || vector.getSourceVersion() == null || !StringUtils.hasText(vector.getSourceKind())
                || !StringUtils.hasText(vector.getEmbeddingModel()) || vector.getDimensions() == null
                || jobMapper.countOpenUpsert(vector.getUserId(), vector.getSourceKind(), vector.getSourceId(),
                vector.getEmbeddingModel(), vector.getSourceVersion()) > 0) return false;
        return insert(vector.getUserId(), vector.getSourceKind(), vector.getSourceId(), vector.getEmbeddingModel(),
                vector.getSourceVersion(), vector.getDimensions(), "UPSERT") == 1;
    }

    private int insert(Long userId, String sourceKind, Long sourceId, String model, Integer sourceVersion,
                       int dimensions, String operation) {
        MemoryVectorIndexJob job = new MemoryVectorIndexJob();
        job.setUserId(userId);
        job.setSourceKind(sourceKind);
        job.setSourceId(sourceId);
        job.setEmbeddingModel(model);
        job.setSourceVersion(sourceVersion);
        job.setDimensions(dimensions);
        job.setOperation(operation);
        LocalDateTime now = LocalDateTime.now(clock);
        job.setAvailableAt(now);
        return jobMapper.insertJob(job, now);
    }

    private boolean valid(Long userId) {
        return userId != null && userId > 0;
    }

    private boolean validLease(MemoryVectorIndexJob job) {
        return job != null && job.getId() != null && StringUtils.hasText(job.getLeaseToken());
    }

    private Duration positiveOr(Duration value, Duration fallback) {
        return value == null || value.isNegative() || value.isZero() ? fallback : value;
    }
}
