package com.example.food.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class MemoryProcessingJobService {

    private static final Logger log = LoggerFactory.getLogger(MemoryProcessingJobService.class);
    private static final int MAX_ERROR_LENGTH = 500;

    private final MemoryProcessingJobMapper mapper;
    private final Clock clock;
    private final Duration leaseDuration;
    private final Duration retryBaseDelay;
    private final Duration retryMaxDelay;
    private final int maxAttempts;

    public MemoryProcessingJobService(
            MemoryProcessingJobMapper mapper,
            Clock clock,
            @Value("${app.memory.processing.lease-duration:PT2M}") Duration leaseDuration,
            @Value("${app.memory.processing.retry-base-delay:PT5S}") Duration retryBaseDelay,
            @Value("${app.memory.processing.retry-max-delay:PT30M}") Duration retryMaxDelay,
            @Value("${app.memory.processing.max-attempts:8}") int maxAttempts
    ) {
        this.mapper = mapper;
        this.clock = clock;
        this.leaseDuration = positiveOr(leaseDuration, Duration.ofMinutes(2));
        this.retryBaseDelay = positiveOr(retryBaseDelay, Duration.ofSeconds(5));
        this.retryMaxDelay = positiveOr(retryMaxDelay, Duration.ofMinutes(30));
        this.maxAttempts = Math.max(1, maxAttempts);
    }

    @Transactional
    public void enqueue(Long userId, Long episodeId) {
        if (userId == null || userId <= 0 || episodeId == null || episodeId <= 0) {
            return;
        }
        insertIfAbsent(userId, episodeId);
    }

    @Transactional
    public int recoverUnqueuedEpisodes(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 500));
        int recovered = 0;
        for (MemoryProcessingJobCandidate candidate : mapper.findUnqueuedEpisodes(safeLimit)) {
            if (insertIfAbsent(candidate.userId(), candidate.episodeId())) {
                recovered++;
            }
        }
        if (recovered > 0) {
            log.info("已恢复未入队的记忆事件，数量={}", recovered);
        }
        return recovered;
    }

    @Transactional
    public MemoryProcessingJob claimNext() {
        LocalDateTime now = LocalDateTime.now(clock);
        MemoryProcessingJob candidate = mapper.findNextDue(now);
        if (candidate == null) {
            return null;
        }
        String leaseToken = UUID.randomUUID().toString();
        if (mapper.claim(candidate.getId(), leaseToken, now, now.plus(leaseDuration)) != 1) {
            return null;
        }
        MemoryProcessingJob claimed = mapper.findClaimed(candidate.getId(), leaseToken);
        if (claimed != null) {
            claimed.setLeaseToken(leaseToken);
        }
        return claimed;
    }

    @Transactional
    public boolean complete(MemoryProcessingJob job) {
        if (!validLease(job)) {
            return false;
        }
        return mapper.complete(job.getId(), job.getLeaseToken(), LocalDateTime.now(clock)) == 1;
    }

    @Transactional
    public boolean retryOrFail(MemoryProcessingJob job, Throwable failure) {
        if (!validLease(job)) {
            return false;
        }
        int attempts = job.getAttempts() == null ? 1 : job.getAttempts();
        LocalDateTime availableAt = LocalDateTime.now(clock).plus(backoff(attempts));
        String message = failure == null ? "未知记忆处理异常" : failure.getMessage();
        String safeError = StringUtils.hasText(message) ? message.trim() : failure == null
                ? "未知记忆处理异常" : failure.getClass().getSimpleName();
        if (safeError.length() > MAX_ERROR_LENGTH) {
            safeError = safeError.substring(0, MAX_ERROR_LENGTH);
        }
        return mapper.retryOrFail(job.getId(), job.getLeaseToken(), availableAt, maxAttempts, safeError) == 1;
    }

    @Transactional
    public int deleteAllOwned(Long userId) {
        return userId == null || userId <= 0 ? 0 : mapper.deleteAllOwned(userId);
    }

    private boolean insertIfAbsent(Long userId, Long episodeId) {
        if (!mapper.episodeOwned(userId, episodeId)) {
            return false;
        }
        try {
            mapper.insertPending(userId, episodeId, LocalDateTime.now(clock));
            return true;
        } catch (DuplicateKeyException duplicate) {
            return false;
        }
    }

    private Duration backoff(int attempts) {
        long multiplier = 1L << Math.min(20, Math.max(0, attempts - 1));
        Duration delay;
        try {
            delay = retryBaseDelay.multipliedBy(multiplier);
        } catch (ArithmeticException overflow) {
            delay = retryMaxDelay;
        }
        return delay.compareTo(retryMaxDelay) > 0 ? retryMaxDelay : delay;
    }

    private boolean validLease(MemoryProcessingJob job) {
        return job != null && job.getId() != null && StringUtils.hasText(job.getLeaseToken());
    }

    private Duration positiveOr(Duration value, Duration fallback) {
        return value == null || value.isNegative() || value.isZero() ? fallback : value;
    }
}
