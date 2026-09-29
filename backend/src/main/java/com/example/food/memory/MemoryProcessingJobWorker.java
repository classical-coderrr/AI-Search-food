package com.example.food.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class MemoryProcessingJobWorker {

    private static final Logger log = LoggerFactory.getLogger(MemoryProcessingJobWorker.class);

    private final MemoryProcessingJobService jobService;
    private final MemoryCandidateService candidateService;
    private final MemoryConsolidationService consolidationService;
    private final MemoryPersonalizationService personalizationService;
    private final MemoryEmbeddingIndexService embeddingIndexService;
    private final int batchSize;

    @org.springframework.beans.factory.annotation.Autowired
    public MemoryProcessingJobWorker(
            MemoryProcessingJobService jobService,
            MemoryCandidateService candidateService,
            MemoryConsolidationService consolidationService,
            MemoryPersonalizationService personalizationService,
            MemoryEmbeddingIndexService embeddingIndexService,
            @Value("${app.memory.processing.batch-size:10}") int batchSize
    ) {
        this.jobService = jobService;
        this.candidateService = candidateService;
        this.consolidationService = consolidationService;
        this.personalizationService = personalizationService;
        this.embeddingIndexService = embeddingIndexService;
        this.batchSize = Math.max(1, Math.min(batchSize, 100));
    }

    public MemoryProcessingJobWorker(MemoryProcessingJobService jobService,
                                     MemoryCandidateService candidateService,
                                     MemoryConsolidationService consolidationService,
                                     MemoryPersonalizationService personalizationService,
                                     int batchSize) {
        this(jobService, candidateService, consolidationService, personalizationService, null, batchSize);
    }

    @Scheduled(fixedDelayString = "${app.memory.processing.poll-interval:PT2S}")
    public void processPending() {
        try {
            jobService.recoverUnqueuedEpisodes(batchSize * 10);
        } catch (RuntimeException failure) {
            log.error("记忆处理队列恢复失败", failure);
        }
        for (int count = 0; count < batchSize; count++) {
            MemoryProcessingJob job;
            try {
                job = jobService.claimNext();
            } catch (RuntimeException failure) {
                log.error("领取记忆处理任务失败", failure);
                return;
            }
            if (job == null) {
                return;
            }
            process(job);
        }
    }

    private void process(MemoryProcessingJob job) {
        try {
            if (personalizationService.isEnabled(job.getUserId())) {
                candidateService.extractAndPersistAfterCommit(job.getUserId(), job.getEpisodeId());
                consolidationService.consolidateAfterCommit(job.getUserId());
            }
            if (embeddingIndexService != null) {
                try {
                    embeddingIndexService.indexPending();
                } catch (RuntimeException indexingFailure) {
                    log.warn("Memory embedding indexing deferred after episode consolidation ({})",
                            indexingFailure.getClass().getSimpleName());
                }
            }
            if (!jobService.complete(job)) {
                log.warn("记忆处理租约已失效，任务结果未确认 jobId={}, episodeId={}",
                        job.getId(), job.getEpisodeId());
            }
        } catch (RuntimeException failure) {
            try {
                boolean updated = jobService.retryOrFail(job, failure);
                log.error("记忆异步处理失败并已安排重试 jobId={}, userId={}, episodeId={}, attempt={}, stateUpdated={}",
                        job.getId(), job.getUserId(), job.getEpisodeId(), job.getAttempts(), updated, failure);
            } catch (RuntimeException retryFailure) {
                log.error("记忆异步处理失败且重试状态写入失败，后续将依赖租约回收 jobId={}, userId={}, episodeId={}",
                        job.getId(), job.getUserId(), job.getEpisodeId(), retryFailure);
            }
        }
    }
}
