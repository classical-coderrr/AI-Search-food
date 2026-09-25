package com.example.food.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class MemoryConfirmationService {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;
    private static final BigDecimal CONFIRMED_CONFIDENCE = new BigDecimal("0.9800");
    private static final BigDecimal CONFIRMED_STRENGTH = new BigDecimal("0.9500");
    private static final BigDecimal CONFIRMATION_IMPORTANCE = new BigDecimal("0.9000");

    private final MemoryCandidateMapper candidateMapper;
    private final MemoryEpisodeService episodeService;
    private final MemoryEvidenceMapper evidenceMapper;
    private final MemoryConsolidationService consolidationService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public MemoryConfirmationService(
            MemoryCandidateMapper candidateMapper,
            MemoryEpisodeService episodeService,
            MemoryEvidenceMapper evidenceMapper,
            MemoryConsolidationService consolidationService,
            ObjectMapper objectMapper
    ) {
        this(candidateMapper, episodeService, evidenceMapper, consolidationService,
                objectMapper, Clock.systemDefaultZone());
    }

    MemoryConfirmationService(
            MemoryCandidateMapper candidateMapper,
            MemoryEpisodeService episodeService,
            MemoryEvidenceMapper evidenceMapper,
            MemoryConsolidationService consolidationService,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.candidateMapper = candidateMapper;
        this.episodeService = episodeService;
        this.evidenceMapper = evidenceMapper;
        this.consolidationService = consolidationService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public List<MemoryConfirmationResponse> listPending(Long userId, int limit) {
        requireUser(userId);
        int safeLimit = Math.max(1, Math.min(limit <= 0 ? DEFAULT_LIMIT : limit, MAX_LIMIT));
        LocalDateTime since = LocalDateTime.now(clock).minus(90, ChronoUnit.DAYS);
        return candidateMapper.listPendingConfirmations(userId, safeLimit).stream()
                .map(candidate -> toResponse(userId, candidate, since))
                .toList();
    }

    @Transactional
    public MemoryConfirmationDecisionResult decide(
            Long userId,
            Long candidateId,
            MemoryConfirmationDecisionRequest request
    ) {
        requireUser(userId);
        if (candidateId == null || candidateId <= 0 || request == null
                || request.decision() == null || request.version() == null || request.version() < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "记忆确认请求无效");
        }

        MemoryCandidate candidate = candidateMapper.findOwned(userId, candidateId);
        if (candidate == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "待确认记忆不存在");
        }
        if (candidate.getUserDecision() != null) {
            if (request.decision().name().equals(candidate.getUserDecision())) {
                return new MemoryConfirmationDecisionResult(candidateId, request.decision(),
                        candidate.getStatus(), MemoryConfirmationDecision.CONFIRM == request.decision(), true);
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT, "这条记忆已作出其他决定");
        }
        if (!MemoryCandidateStatus.AWAITING_CONFIRMATION.name().equals(candidate.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "这条记忆当前不需要确认");
        }
        if (!request.version().equals(candidate.getVersion())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "记忆已更新，请刷新后重试");
        }

        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime since = now.minus(90, ChronoUnit.DAYS);
        String entity = candidate.getCanonicalEntity() == null ? candidate.getEntity() : candidate.getCanonicalEntity();
        List<MemoryCandidate> supportingCandidates = candidateMapper.listRecentSimilarDietGoals(
                userId, candidate.getCanonicalGroupId(), entity, since, MAX_LIMIT);
        List<Long> supportingEpisodeIds = supportingCandidates.stream()
                .map(MemoryCandidate::getEpisodeId)
                .filter(id -> id != null)
                .distinct()
                .toList();
        int supportingEvidenceCount = candidateMapper.countRecentSimilarDietGoals(
                userId, candidate.getCanonicalGroupId(), entity, since);

        boolean confirmed = request.decision() == MemoryConfirmationDecision.CONFIRM;
        String nextStatus = confirmed ? MemoryCandidateStatus.ACCEPTED.name() : MemoryCandidateStatus.REJECTED.name();
        String nextSource = confirmed ? "USER_CONFIRMED" : candidate.getSourceType();
        String nextTemporalType = confirmed ? "LONG_TERM" : candidate.getTemporalType();
        BigDecimal nextConfidence = confirmed ? CONFIRMED_CONFIDENCE : candidate.getConfidence();
        BigDecimal nextStrength = confirmed ? CONFIRMED_STRENGTH : candidate.getStrength();
        int evidenceCount = Math.max(1, supportingEvidenceCount);

        if (candidateMapper.decidePendingConfirmation(
                userId,
                candidateId,
                candidate.getVersion(),
                nextStatus,
                request.decision().name(),
                now,
                nextSource,
                nextTemporalType,
                nextConfidence,
                nextStrength,
                evidenceCount
        ) != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "记忆确认已被其他请求处理，请刷新");
        }

        String label = decisionLabel(request.decision());
        String eventKey = "memory-confirmation:" + candidateId + ":" + request.decision().name();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("candidateId", candidateId);
        payload.put("candidateType", candidate.getCandidateType());
        payload.put("entity", entity);
        payload.put("preference", candidate.getPreference());
        payload.put("decision", request.decision().name());
        payload.put("supportingEpisodeIds", supportingEpisodeIds);
        payload.put("supportingCandidateIds", supportingCandidates.stream()
                .map(MemoryCandidate::getId).filter(id -> id != null).distinct().toList());
        payload.put("previousConfidence", candidate.getConfidence());
        payload.put("resultingStatus", nextStatus);

        MemoryEpisode decisionEpisode = episodeService.record(userId, new MemoryEpisodeCommand(
                candidate.getSessionId(),
                null,
                "MEMORY_PREFERENCE_" + request.decision().name(),
                "MEMORY_CONFIRMATION",
                String.valueOf(candidateId),
                eventKey,
                eventKey,
                "用户" + label + "记忆：" + entity,
                writeJson(payload),
                now,
                CONFIRMATION_IMPORTANCE
        )).episode();

        MemoryEvidence evidence = new MemoryEvidence();
        evidence.setUserId(userId);
        evidence.setCandidateId(candidateId);
        evidence.setEpisodeId(decisionEpisode.getId());
        evidence.setSourceType("USER_CONFIRMATION");
        evidence.setSourceEventId(eventKey);
        evidence.setSourceSessionId(candidate.getSessionId());
        evidence.setEvidenceKey(eventKey);
        evidence.setEvidenceText("用户" + label + "“" + entity + "”作为长期饮食目标");
        evidence.setEvidenceJson(writeJson(payload));
        evidence.setExplicitConfirmed(confirmed);
        evidence.setObservedAt(now);
        evidenceMapper.insert(evidence);

        if (confirmed) {
            consolidationService.consolidate(userId);
        }
        MemoryCandidate updated = candidateMapper.findOwned(userId, candidateId);
        return new MemoryConfirmationDecisionResult(candidateId, request.decision(),
                updated == null ? nextStatus : updated.getStatus(), confirmed, false);
    }

    private MemoryConfirmationResponse toResponse(Long userId, MemoryCandidate candidate, LocalDateTime since) {
        String entity = candidate.getCanonicalEntity() == null ? candidate.getEntity() : candidate.getCanonicalEntity();
        List<MemoryCandidate> support = candidateMapper.listRecentSimilarDietGoals(
                userId, candidate.getCanonicalGroupId(), entity, since, 5);
        List<String> summaries = support.stream()
                .map(value -> value.getEpisodeId() == null ? null : safeEpisodeSummary(userId, value.getEpisodeId()))
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toList();
        if (summaries.isEmpty()) {
            summaries = List.of("近 90 天内多次选择该饮食目标并保存菜谱");
        }
        LocalDateTime firstSeenAt = support.stream().map(MemoryCandidate::getExtractedAt)
                .filter(value -> value != null).min(LocalDateTime::compareTo).orElse(candidate.getExtractedAt());
        LocalDateTime lastSeenAt = support.stream().map(MemoryCandidate::getExtractedAt)
                .filter(value -> value != null).max(LocalDateTime::compareTo).orElse(candidate.getExtractedAt());
        int evidenceCount = candidateMapper.countRecentSimilarDietGoals(
                userId, candidate.getCanonicalGroupId(), entity, since);
        return new MemoryConfirmationResponse(candidate.getId(), candidate.getCandidateType(), entity,
                candidate.getPreference(), candidate.getConfidence(), Math.max(1, evidenceCount),
                summaries, firstSeenAt, lastSeenAt, candidate.getVersion());
    }

    private String safeEpisodeSummary(Long userId, Long episodeId) {
        try {
            MemoryEpisode episode = episodeService.findOwned(userId, episodeId);
            if (episode.getSummary() == null) return null;
            String normalized = episode.getSummary().replaceAll("[\\p{Cntrl}]", " ").replaceAll("\\s+", " ").trim();
            return normalized.length() <= 160 ? normalized : normalized.substring(0, 160) + "…";
        } catch (ResponseStatusException ignored) {
            return null;
        }
    }

    private String decisionLabel(MemoryConfirmationDecision decision) {
        return switch (decision) {
            case CONFIRM -> "确认并长期记住";
            case REJECT -> "拒绝";
            case ONLY_THIS_TIME -> "仅本次使用";
        };
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("记忆确认事件序列化失败", exception);
        }
    }

    private void requireUser(Long userId) {
        if (userId == null || userId <= 0) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户身份无效");
        }
    }
}
