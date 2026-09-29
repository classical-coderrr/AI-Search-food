package com.example.food.memory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class MemoryEmbeddingIndexJobService {
    private static final Logger log = LoggerFactory.getLogger(MemoryEmbeddingIndexJobService.class);
    private static final int MAX_ERROR_LENGTH = 500;

    private final MemoryEmbeddingIndexJobMapper mapper;
    private final MemoryEmbeddingProperties properties;
    private final Clock clock;

    @Autowired
    public MemoryEmbeddingIndexJobService(MemoryEmbeddingIndexJobMapper mapper,
                                          MemoryEmbeddingProperties properties, Clock clock) {
        this.mapper = mapper;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public MemoryEmbeddingIndexJob claim(MemoryEmbeddingCandidate candidate, String model, int dimensions) {
        if (!valid(candidate) || !StringUtils.hasText(model) || dimensions <= 0) return null;
        LocalDateTime now = LocalDateTime.now(clock);
        Duration leaseDuration = positiveOr(properties.leaseDuration(), Duration.ofMinutes(2));
        String leaseToken = UUID.randomUUID().toString();
        int claimed = mapper.claimExisting(candidate.getUserId(), candidate.getSourceKind(), candidate.getSourceId(),
                candidate.getSourceVersion(), model, dimensions, now, leaseToken, now.plus(leaseDuration));
        if (claimed == 0) {
            try {
                claimed = mapper.insertProcessing(candidate.getUserId(), candidate.getSourceKind(), candidate.getSourceId(),
                        candidate.getSourceVersion(), model, dimensions, now, leaseToken, now.plus(leaseDuration));
            } catch (DuplicateKeyException concurrentInsert) {
                claimed = mapper.claimExisting(candidate.getUserId(), candidate.getSourceKind(), candidate.getSourceId(),
                        candidate.getSourceVersion(), model, dimensions, now, leaseToken, now.plus(leaseDuration));
            }
        }
        return claimed == 1 ? mapper.findClaimed(candidate.getUserId(), candidate.getSourceKind(),
                candidate.getSourceId(), model, leaseToken) : null;
    }

    @Transactional
    public boolean complete(MemoryEmbeddingIndexJob job) {
        return validLease(job) && mapper.complete(job.getId(), job.getLeaseToken(), LocalDateTime.now(clock)) == 1;
    }

    @Transactional
    public boolean retry(MemoryEmbeddingIndexJob job, String failureCode) {
        if (!validLease(job)) return false;
        String safeError = StringUtils.hasText(failureCode) ? failureCode.trim() : "EMBEDDING_INDEX_FAILED";
        safeError = safeError.replaceAll("[^A-Za-z0-9_.$-]", "_");
        if (safeError.length() > MAX_ERROR_LENGTH) safeError = safeError.substring(0, MAX_ERROR_LENGTH);
        int attempts = job.getAttempts() == null ? 1 : Math.max(1, job.getAttempts());
        Duration delay = backoff(attempts);
        LocalDateTime availableAt = LocalDateTime.now(clock).plus(delay);
        boolean updated = mapper.retry(job.getId(), job.getLeaseToken(), availableAt, safeError) == 1;
        if (updated) {
            log.warn("Memory embedding indexing deferred sourceKind={} sourceId={} attempt={} delaySeconds={} code={}",
                    job.getSourceKind(), job.getSourceId(), attempts, delay.toSeconds(), safeError);
        }
        return updated;
    }

    @Transactional
    public int deleteSource(Long userId, String sourceKind, Long sourceId) {
        return userId == null || userId <= 0 || sourceId == null ? 0
                : mapper.deleteOwnedSource(userId, sourceKind, sourceId);
    }

    @Transactional
    public int deleteAll(Long userId) {
        return userId == null || userId <= 0 ? 0 : mapper.deleteAllOwned(userId);
    }

    private Duration backoff(int attempts) {
        Duration base = positiveOr(properties.retryBaseDelay(), Duration.ofSeconds(5));
        Duration maximum = positiveOr(properties.retryMaxDelay(), Duration.ofMinutes(30));
        long multiplier = 1L << Math.min(20, Math.max(0, attempts - 1));
        Duration delay;
        try {
            delay = base.multipliedBy(multiplier);
        } catch (ArithmeticException overflow) {
            delay = maximum;
        }
        return delay.compareTo(maximum) > 0 ? maximum : delay;
    }

    private boolean valid(MemoryEmbeddingCandidate candidate) {
        return candidate != null && candidate.getUserId() != null && candidate.getUserId() > 0
                && StringUtils.hasText(candidate.getSourceKind()) && candidate.getSourceId() != null
                && candidate.getSourceId() > 0 && candidate.getSourceVersion() != null;
    }

    private boolean validLease(MemoryEmbeddingIndexJob job) {
        return job != null && job.getId() != null && StringUtils.hasText(job.getLeaseToken());
    }

    private Duration positiveOr(Duration value, Duration fallback) {
        return value == null || value.isNegative() || value.isZero() ? fallback : value;
    }
}
