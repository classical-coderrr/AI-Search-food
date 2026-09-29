package com.example.food.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Adapts existing business events to the Episode persistence boundary.
 * Memory recording is best effort so a secondary memory failure does not break
 * the user's primary search, save, cooking, or feedback operation.
 */
@Service
public class MemoryBehaviorEpisodeRecorder {

    private static final Logger log = LoggerFactory.getLogger(MemoryBehaviorEpisodeRecorder.class);
    private static final Set<String> EXTRACTABLE_EPISODE_TYPES = Set.of(
            "RECIPE_SAVED", "RECIPE_UNSAVED", "RECIPE_FEEDBACK", "FINISHED_DISH_REVIEW",
            "USER_PREFERENCE_DECLARED", "USER_PREFERENCE_CONFIRMED"
    );

    private final MemoryEpisodeService episodeService;
    private final ObjectMapper objectMapper;
    private final MemoryPersonalizationService personalizationService;
    private final MemoryProcessingJobService jobService;
    private final TransactionTemplate episodeTransaction;
    private final TransactionTemplate queueTransaction;

    public MemoryBehaviorEpisodeRecorder(MemoryEpisodeService episodeService, ObjectMapper objectMapper) {
        this(episodeService, objectMapper, null, null, null);
    }

    MemoryBehaviorEpisodeRecorder(
            MemoryEpisodeService episodeService,
            ObjectMapper objectMapper,
            MemoryPersonalizationService personalizationService,
            MemoryProcessingJobService jobService
    ) {
        this(episodeService, objectMapper, personalizationService, jobService, null);
    }

    @Autowired
    MemoryBehaviorEpisodeRecorder(
            MemoryEpisodeService episodeService,
            ObjectMapper objectMapper,
            MemoryPersonalizationService personalizationService,
            MemoryProcessingJobService jobService,
            PlatformTransactionManager transactionManager
    ) {
        this.episodeService = episodeService;
        this.objectMapper = objectMapper;
        this.personalizationService = personalizationService;
        this.jobService = jobService;
        this.episodeTransaction = newTransactionTemplate(transactionManager);
        this.queueTransaction = newTransactionTemplate(transactionManager);
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

        if (episodeTransaction != null
                && TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    recordAfterBusinessCommit(event);
                }
            });
            return;
        }
        recordAfterBusinessCommit(event);
    }

    private void recordAfterBusinessCommit(MemoryBehaviorEpisodeEvent event) {
        MemoryEpisodeService.RecordResult result;
        try {
            if (personalizationService != null && !personalizationService.isEnabled(event.userId())) {
                return;
            }
            MemoryEpisodeCommand command = new MemoryEpisodeCommand(
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
            );
            result = inTransaction(episodeTransaction, () -> episodeService.record(event.userId(), command));
        } catch (JsonProcessingException exception) {
            log.error("记忆行为事件序列化失败 userId={}, episodeType={}, sourceId={}",
                    event.userId(), event.episodeType(), event.sourceId(), exception);
            return;
        } catch (RuntimeException exception) {
            log.error("记忆行为事件保存失败 userId={}, episodeType={}, sourceId={}",
                    event.userId(), event.episodeType(), event.sourceId(), exception);
            return;
        }

        String recordedType = result == null || result.episode() == null ? null : result.episode().getEpisodeType();
        if (jobService == null || result == null || result.episode() == null || recordedType == null
                || !EXTRACTABLE_EPISODE_TYPES.contains(recordedType.trim().toUpperCase(Locale.ROOT))) {
            return;
        }
        try {
            inTransaction(queueTransaction, () -> {
                jobService.enqueue(event.userId(), result.episode().getId());
                return null;
            });
        } catch (RuntimeException exception) {
            log.error("记忆任务入队失败，后台恢复扫描会尝试补捞 userId={}, episodeId={}",
                    event.userId(), result.episode().getId(), exception);
        }
    }

    private <T> T inTransaction(TransactionTemplate template, Supplier<T> work) {
        return template == null ? work.get() : template.execute(status -> work.get());
    }

    private TransactionTemplate newTransactionTemplate(PlatformTransactionManager transactionManager) {
        if (transactionManager == null) return null;
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }

}
