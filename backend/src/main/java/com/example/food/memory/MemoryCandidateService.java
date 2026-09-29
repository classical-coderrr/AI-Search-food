package com.example.food.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.HexFormat;
import java.util.Locale;

@Service
public class MemoryCandidateService {

    public static final String DEFAULT_EXTRACTION_MODEL = "rule-based-v2";
    public static final String DEFAULT_PROMPT_VERSION = "memory-extraction-v1";
    private static final int MAX_LIMIT = 100;
    private static final int MAX_KEY_LENGTH = 192;

    private final MemoryCandidateMapper candidateMapper;
    private final MemoryEvidenceMapper evidenceMapper;
    private final MemoryEpisodeService episodeService;
    private final MemoryExtractor extractor;
    private final TagNormalizationService tagNormalizationService;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final MemoryPersonalizationService personalizationService;
    private final MemoryBehaviorPatternExtractor behaviorPatternExtractor;
    private final MemoryUnsavedBehaviorReconciler unsavedBehaviorReconciler;

    public MemoryCandidateService(
            MemoryCandidateMapper candidateMapper,
            MemoryEvidenceMapper evidenceMapper,
            MemoryEpisodeService episodeService,
            MemoryExtractor extractor,
            TagNormalizationService tagNormalizationService,
            ObjectMapper objectMapper
    ) {
        this(candidateMapper, evidenceMapper, episodeService, extractor, tagNormalizationService,
                objectMapper, Clock.systemDefaultZone(), null, null);
    }

    public MemoryCandidateService(
            MemoryCandidateMapper candidateMapper,
            MemoryEvidenceMapper evidenceMapper,
            MemoryEpisodeService episodeService,
            MemoryExtractor extractor,
            TagNormalizationService tagNormalizationService,
            ObjectMapper objectMapper,
            MemoryPersonalizationService personalizationService
    ) {
        this(candidateMapper, evidenceMapper, episodeService, extractor, tagNormalizationService,
                objectMapper, Clock.systemDefaultZone(), personalizationService, null);
    }

    public MemoryCandidateService(
            MemoryCandidateMapper candidateMapper,
            MemoryEvidenceMapper evidenceMapper,
            MemoryEpisodeService episodeService,
            MemoryExtractor extractor,
            TagNormalizationService tagNormalizationService,
            ObjectMapper objectMapper,
            MemoryPersonalizationService personalizationService,
            MemoryBehaviorPatternExtractor behaviorPatternExtractor
    ) {
        this(candidateMapper, evidenceMapper, episodeService, extractor, tagNormalizationService,
                objectMapper, Clock.systemDefaultZone(), personalizationService, behaviorPatternExtractor, null);
    }

    @Autowired
    public MemoryCandidateService(
            MemoryCandidateMapper candidateMapper,
            MemoryEvidenceMapper evidenceMapper,
            MemoryEpisodeService episodeService,
            MemoryExtractor extractor,
            TagNormalizationService tagNormalizationService,
            ObjectMapper objectMapper,
            MemoryPersonalizationService personalizationService,
            MemoryBehaviorPatternExtractor behaviorPatternExtractor,
            MemoryUnsavedBehaviorReconciler unsavedBehaviorReconciler
    ) {
        this(candidateMapper, evidenceMapper, episodeService, extractor, tagNormalizationService,
                objectMapper, Clock.systemDefaultZone(), personalizationService, behaviorPatternExtractor,
                unsavedBehaviorReconciler);
    }

    MemoryCandidateService(
            MemoryCandidateMapper candidateMapper,
            MemoryEvidenceMapper evidenceMapper,
            MemoryEpisodeService episodeService,
            MemoryExtractor extractor,
            TagNormalizationService tagNormalizationService,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this(candidateMapper, evidenceMapper, episodeService, extractor, tagNormalizationService,
                objectMapper, clock, null, null);
    }

    MemoryCandidateService(
            MemoryCandidateMapper candidateMapper,
            MemoryEvidenceMapper evidenceMapper,
            MemoryEpisodeService episodeService,
            MemoryExtractor extractor,
            TagNormalizationService tagNormalizationService,
            ObjectMapper objectMapper,
            Clock clock,
            MemoryPersonalizationService personalizationService
    ) {
        this(candidateMapper, evidenceMapper, episodeService, extractor, tagNormalizationService,
                objectMapper, clock, personalizationService, null);
    }

    MemoryCandidateService(
            MemoryCandidateMapper candidateMapper,
            MemoryEvidenceMapper evidenceMapper,
            MemoryEpisodeService episodeService,
            MemoryExtractor extractor,
            TagNormalizationService tagNormalizationService,
            ObjectMapper objectMapper,
            Clock clock,
            MemoryPersonalizationService personalizationService,
            MemoryBehaviorPatternExtractor behaviorPatternExtractor
    ) {
        this(candidateMapper, evidenceMapper, episodeService, extractor, tagNormalizationService,
                objectMapper, clock, personalizationService, behaviorPatternExtractor, null);
    }

    MemoryCandidateService(
            MemoryCandidateMapper candidateMapper,
            MemoryEvidenceMapper evidenceMapper,
            MemoryEpisodeService episodeService,
            MemoryExtractor extractor,
            TagNormalizationService tagNormalizationService,
            ObjectMapper objectMapper,
            Clock clock,
            MemoryPersonalizationService personalizationService,
            MemoryBehaviorPatternExtractor behaviorPatternExtractor,
            MemoryUnsavedBehaviorReconciler unsavedBehaviorReconciler
    ) {
        this.candidateMapper = candidateMapper;
        this.evidenceMapper = evidenceMapper;
        this.episodeService = episodeService;
        this.extractor = extractor;
        this.tagNormalizationService = tagNormalizationService;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.personalizationService = personalizationService;
        this.behaviorPatternExtractor = behaviorPatternExtractor;
        this.unsavedBehaviorReconciler = unsavedBehaviorReconciler;
    }

    @Transactional
    public ExtractionResult extractAndPersist(Long userId, Long episodeId) {
        return extractAndPersist(userId, episodeId, DEFAULT_EXTRACTION_MODEL, DEFAULT_PROMPT_VERSION);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ExtractionResult extractAndPersistAfterCommit(Long userId, Long episodeId) {
        return extractAndPersist(userId, episodeId, DEFAULT_EXTRACTION_MODEL, DEFAULT_PROMPT_VERSION);
    }

    @Transactional
    public ExtractionResult extractAndPersist(
            Long userId,
            Long episodeId,
            String extractionModel,
            String promptVersion
    ) {
        requireUser(userId);
        String model = requireText(extractionModel, "提取模型不能为空", 128);
        String prompt = requireText(promptVersion, "提取提示词版本不能为空", 64);
        if (personalizationService != null && !personalizationService.isEnabled(userId)) {
            return new ExtractionResult(List.of(), 0, 0);
        }
        MemoryEpisode episode = episodeService.findOwned(userId, episodeId);
        if (unsavedBehaviorReconciler != null) {
            unsavedBehaviorReconciler.reconcile(userId, episode);
        }
        List<MemoryCandidateDraft> episodeDrafts = extractor.extract(episode);
        if (!prepareRecipeFeedbackReaction(userId, episode, episodeDrafts)) {
            return new ExtractionResult(List.of(), 0, 0);
        }

        List<EpisodeDraft> drafts = new java.util.ArrayList<>();
        episodeDrafts.forEach(draft -> drafts.add(new EpisodeDraft(episode, draft)));
        if (behaviorPatternExtractor != null) {
            behaviorPatternExtractor.extract(userId, episode).stream()
                    .map(support -> new EpisodeDraft(support.episode(), support.draft()))
                    .forEach(drafts::add);
        }

        int duplicateCount = 0;
        List<MemoryCandidate> candidates = new java.util.ArrayList<>();
        for (EpisodeDraft supportedDraft : drafts) {
            MemoryEpisode candidateEpisode = supportedDraft.episode();
            MemoryCandidateDraft draft = supportedDraft.draft();
            TagNormalizationResult normalization = tagNormalizationService.normalize(
                    draft.candidateType(), draft.entity());
            String extractionKey = extractionKey(candidateEpisode, draft, normalization, prompt);
            MemoryCandidate candidate = candidateMapper.findOwnedByExtractionKey(userId, extractionKey);
            boolean duplicate = candidate != null;
            if (!duplicate) {
                candidate = newCandidate(userId, candidateEpisode, draft, normalization, extractionKey, model, prompt);
                try {
                    candidateMapper.insert(candidate);
                } catch (DuplicateKeyException exception) {
                    candidate = candidateMapper.findOwnedByExtractionKey(userId, extractionKey);
                    if (candidate == null) {
                        throw exception;
                    }
                    duplicate = true;
                }
            }
            if (duplicate) {
                duplicateCount++;
            }
            persistEvidence(userId, candidateEpisode, candidate, draft, extractionKey);
            candidates.add(candidate);
        }
        // Recheck after persistence so a newer reaction committed during extraction
        // cannot leave this older event as the active candidate.
        prepareRecipeFeedbackReaction(userId, episode, episodeDrafts);
        return new ExtractionResult(candidates, duplicateCount, drafts.size());
    }

    private boolean prepareRecipeFeedbackReaction(
            Long userId,
            MemoryEpisode episode,
            List<MemoryCandidateDraft> drafts
    ) {
        String action = recipeFeedbackReactionAction(episode);
        if (action == null || !StringUtils.hasText(episode.getSourceId())) {
            return true;
        }
        if ("REACTION".equals(action)
                && drafts.stream().noneMatch(draft -> "RECIPE_PREFERENCE".equals(draft.candidateType()))) {
            return true;
        }

        MemoryEpisode latestReaction = episodeService.listOwnedRecipeFeedbackBySource(
                        userId, episode.getSourceId()).stream()
                .filter(candidateEpisode -> recipeFeedbackReactionAction(candidateEpisode) != null)
                .findFirst()
                .orElse(null);
        boolean isLatest = latestReaction != null && episode.getId().equals(latestReaction.getId());

        for (MemoryCandidate previous : candidateMapper.listOwnedRecipeFeedbackCandidatesBySource(
                userId, episode.getSourceId())) {
            boolean latestCandidate = isLatest && "REACTION".equals(action)
                    && episode.getId().equals(previous.getEpisodeId());
            if (!latestCandidate) {
                candidateMapper.markSuperseded(userId, previous.getId(), previous.getVersion());
            }
        }
        return isLatest;
    }

    private String recipeFeedbackReactionAction(MemoryEpisode episode) {
        if (episode == null || !"RECIPE_FEEDBACK".equalsIgnoreCase(episode.getEpisodeType())
                || !StringUtils.hasText(episode.getPayloadJson())) {
            return null;
        }
        try {
            JsonNode payload = objectMapper.readTree(episode.getPayloadJson());
            String action = payload == null ? null : payload.path("action").asText(null);
            if (!StringUtils.hasText(action)) return null;
            String normalized = action.trim().toUpperCase(Locale.ROOT);
            return "REACTION".equals(normalized) || "REACTION_CLEARED".equals(normalized)
                    ? normalized : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    public MemoryCandidate findOwned(Long userId, Long candidateId) {
        requireUser(userId);
        MemoryCandidate candidate = candidateId == null ? null : candidateMapper.findOwned(userId, candidateId);
        if (candidate == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "候选记忆不存在");
        }
        return candidate;
    }

    public List<MemoryCandidate> listOwned(
            Long userId,
            Long episodeId,
            String candidateType,
            int limit
    ) {
        requireUser(userId);
        int safeLimit = Math.max(1, Math.min(limit <= 0 ? 20 : limit, MAX_LIMIT));
        return candidateMapper.listOwned(userId, episodeId, normalize(candidateType), safeLimit);
    }

    private MemoryCandidate newCandidate(
            Long userId,
            MemoryEpisode episode,
            MemoryCandidateDraft draft,
            TagNormalizationResult normalization,
            String extractionKey,
            String extractionModel,
            String promptVersion
    ) {
        MemoryCandidate candidate = new MemoryCandidate();
        candidate.setUserId(userId);
        candidate.setEpisodeId(episode.getId());
        candidate.setSessionId(episode.getSessionId());
        candidate.setCandidateType(draft.candidateType());
        candidate.setEntity(draft.entity());
        candidate.setCanonicalTagId(normalization.canonicalTagId());
        candidate.setCanonicalId(normalization.canonicalId());
        candidate.setCanonicalEntity(normalization.canonicalName());
        candidate.setCanonicalCategory(normalization.category());
        candidate.setCanonicalGroupId(normalization.canonicalGroupId());
        candidate.setNormalizationConfidence(normalization.confidence());
        candidate.setNormalizationSource(normalization.source());
        candidate.setPreference(draft.preference());
        candidate.setStrength(draft.strength());
        candidate.setConfidence(draft.confidence());
        candidate.setSourceType(draft.sourceType());
        candidate.setScope(draft.scope());
        candidate.setTemporalType(draft.temporalType());
        candidate.setEvidenceCount(1);
        candidate.setExtractionKey(extractionKey);
        candidate.setExtractionModel(extractionModel);
        candidate.setPromptVersion(promptVersion);
        candidate.setExtractedAt(LocalDateTime.now(clock));
        candidate.setStatus(initialStatus(userId, draft, normalization).name());
        candidate.setVersion(0);
        return candidate;
    }

    private MemoryCandidateStatus initialStatus(
            Long userId,
            MemoryCandidateDraft draft,
            TagNormalizationResult normalization
    ) {
        if (!"DIET_GOAL".equalsIgnoreCase(draft.candidateType())
                || !"PURSUE".equalsIgnoreCase(draft.preference())
                || !"IMPLICIT_BEHAVIOR".equalsIgnoreCase(draft.sourceType())) {
            return MemoryCandidateStatus.PENDING;
        }

        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime since = now.minus(90, ChronoUnit.DAYS);
        String canonicalEntity = normalization.mapped() ? normalization.canonicalName() : draft.entity();
        String canonicalGroupId = normalization.canonicalGroupId();
        int priorOccurrences = candidateMapper.countRecentSimilarDietGoals(
                userId, canonicalGroupId, canonicalEntity, since);
        if (priorOccurrences < 2
                || candidateMapper.countRecentDecisionsForSimilarDietGoal(
                userId, canonicalGroupId, canonicalEntity, since) > 0) {
            return MemoryCandidateStatus.PENDING;
        }

        boolean alreadyAsked = candidateMapper.listRecentSimilarDietGoals(
                        userId, canonicalGroupId, canonicalEntity, since, 100).stream()
                .anyMatch(candidate -> MemoryCandidateStatus.AWAITING_CONFIRMATION.name()
                        .equals(candidate.getStatus()));
        return alreadyAsked ? MemoryCandidateStatus.PENDING : MemoryCandidateStatus.AWAITING_CONFIRMATION;
    }

    private void persistEvidence(
            Long userId,
            MemoryEpisode episode,
            MemoryCandidate candidate,
            MemoryCandidateDraft draft,
            String evidenceKey
    ) {
        if (candidate.getId() == null) {
            throw new IllegalStateException("候选记忆保存后缺少编号");
        }
        if (evidenceMapper.findByCandidateAndKey(candidate.getId(), evidenceKey) != null) {
            return;
        }
        MemoryEvidence evidence = new MemoryEvidence();
        evidence.setUserId(userId);
        evidence.setCandidateId(candidate.getId());
        evidence.setEpisodeId(episode.getId());
        evidence.setSourceType(episode.getSourceType());
        evidence.setSourceEventId(episode.getEventId());
        evidence.setSourceSessionId(episode.getSessionId());
        evidence.setEvidenceKey(evidenceKey);
        evidence.setEvidenceText(draft.evidenceText());
        evidence.setEvidenceJson(writeEvidenceJson(draft));
        evidence.setExplicitConfirmed(draft.explicitConfirmed());
        evidence.setObservedAt(episode.getOccurredAt());
        evidenceMapper.insert(evidence);
    }

    private String writeEvidenceJson(MemoryCandidateDraft draft) {
        try {
            return objectMapper.writeValueAsString(draft.evidence());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("记忆证据序列化失败", exception);
        }
    }

    private String extractionKey(
            MemoryEpisode episode,
            MemoryCandidateDraft draft,
            TagNormalizationResult normalization,
            String promptVersion
    ) {
        String normalizedEntity = normalization.mapped()
                ? normalization.canonicalId()
                : tagNormalizationService.normalizeText(draft.entity());
        String raw = episode.getId() + "|" + promptVersion + "|"
                + draft.candidateType() + "|" + normalizedEntity + "|" + draft.preference();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8));
            String key = episode.getId() + "|" + promptVersion + "|" + HexFormat.of().formatHex(digest);
            return key.length() <= MAX_KEY_LENGTH ? key : key.substring(0, MAX_KEY_LENGTH);
        } catch (Exception exception) {
            throw new IllegalStateException("候选记忆幂等键生成失败", exception);
        }
    }

    private String requireText(String value, String message, int maxLength) {
        if (!StringUtils.hasText(value) || value.trim().length() > maxLength) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        }
        return value.trim();
    }

    private String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private void requireUser(Long userId) {
        if (userId == null || userId <= 0) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户身份无效");
        }
    }

    public record ExtractionResult(
            List<MemoryCandidate> candidates,
            int duplicateCount,
            int extractedCount
    ) {
        public ExtractionResult {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
        }

        public boolean hasCandidates() {
            return !candidates.isEmpty();
        }
    }

    private record EpisodeDraft(MemoryEpisode episode, MemoryCandidateDraft draft) { }
}
