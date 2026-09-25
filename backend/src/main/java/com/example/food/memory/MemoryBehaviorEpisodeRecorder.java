package com.example.food.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

/**
 * Adapts existing business events to the Episode persistence boundary.
 * Memory recording is best effort so a secondary memory failure does not break
 * the user's primary search, save, cooking, or feedback operation.
 */
@Service
public class MemoryBehaviorEpisodeRecorder {

    private static final Logger log = LoggerFactory.getLogger(MemoryBehaviorEpisodeRecorder.class);

    private final MemoryEpisodeService episodeService;
    private final ObjectMapper objectMapper;
    private final MemoryCandidateService candidateService;
    private final MemoryConsolidationService consolidationService;
    private final MemoryPersonalizationService personalizationService;

    public MemoryBehaviorEpisodeRecorder(MemoryEpisodeService episodeService, ObjectMapper objectMapper) {
        this(episodeService, objectMapper, null, null, null);
    }

    MemoryBehaviorEpisodeRecorder(
            MemoryEpisodeService episodeService,
            ObjectMapper objectMapper,
            MemoryCandidateService candidateService
    ) {
        this(episodeService, objectMapper, candidateService, null, null);
    }

    MemoryBehaviorEpisodeRecorder(
            MemoryEpisodeService episodeService,
            ObjectMapper objectMapper,
            MemoryCandidateService candidateService,
            MemoryConsolidationService consolidationService
    ) {
        this(episodeService, objectMapper, candidateService, consolidationService, null);
    }

    @Autowired
    MemoryBehaviorEpisodeRecorder(
            MemoryEpisodeService episodeService,
            ObjectMapper objectMapper,
            MemoryCandidateService candidateService,
            MemoryConsolidationService consolidationService,
            MemoryPersonalizationService personalizationService
    ) {
        this.episodeService = episodeService;
        this.objectMapper = objectMapper;
        this.candidateService = candidateService;
        this.consolidationService = consolidationService;
        this.personalizationService = personalizationService;
    }

    public void record(MemoryBehaviorEpisodeEvent event) {
        if (event == null || event.userId() == null || event.userId() <= 0) {
            return;
        }
        if (!StringUtils.hasText(event.episodeType())
                || !StringUtils.hasText(event.sourceType())
                || !StringUtils.hasText(event.idempotencyKey())) {
            log.warn("跳过无效记忆行为事件 userId={}, episodeType={}, sourceType={}",
                    event.userId(), event.episodeType(), event.sourceType());
            return;
        }

        try {
            if (personalizationService != null && !personalizationService.isEnabled(event.userId())) {
                return;
            }
            MemoryEpisodeService.RecordResult result = episodeService.record(event.userId(), new MemoryEpisodeCommand(
                    event.sessionId(),
                    event.conversationId(),
                    event.episodeType(),
                    event.sourceType(),
                    event.sourceId(),
                    event.eventId(),
                    event.idempotencyKey(),
                    event.summary(),
                    objectMapper.writeValueAsString(event.payload()),
                    event.occurredAt(),
                    event.importance()
            ));
            scheduleCandidateExtraction(event.userId(), result);
        } catch (JsonProcessingException exception) {
            log.error("记忆行为事件序列化失败 userId={}, episodeType={}, sourceId={}",
                    event.userId(), event.episodeType(), event.sourceId(), exception);
        } catch (RuntimeException exception) {
            log.error("记忆行为事件保存失败 userId={}, episodeType={}, sourceId={}",
                    event.userId(), event.episodeType(), event.sourceId(), exception);
        }
    }

    private void scheduleCandidateExtraction(Long userId, MemoryEpisodeService.RecordResult result) {
        if (candidateService == null || result == null || result.episode() == null
                || result.episode().getId() == null) {
            return;
        }
        Runnable extraction = () -> {
            try {
                candidateService.extractAndPersistAfterCommit(userId, result.episode().getId());
            } catch (RuntimeException exception) {
                log.error("记忆候选提取失败 userId={}, episodeId={}", userId,
                        result.episode().getId(), exception);
                return;
            }
            if (consolidationService != null) {
                try {
                    consolidationService.consolidateAfterCommit(userId);
                } catch (RuntimeException exception) {
                    log.error("记忆合并或画像重建失败 userId={}, episodeId={}", userId,
                            result.episode().getId(), exception);
                }
            }
        };
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            extraction.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                extraction.run();
            }
        });
    }
}
