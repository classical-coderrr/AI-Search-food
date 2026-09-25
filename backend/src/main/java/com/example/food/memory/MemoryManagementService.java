package com.example.food.memory;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class MemoryManagementService {

    private static final int MAX_ITEMS = 500;
    private static final Set<String> INGREDIENT_PREFERENCES = Set.of("LIKE", "DISLIKE", "AVOID");
    private static final Set<String> RECIPE_PREFERENCES = Set.of("LIKE", "DISLIKE");
    private static final Set<String> DIET_GOAL_PREFERENCES = Set.of("PURSUE", "AVOID");

    private final MemoryItemMapper itemMapper;
    private final MemoryItemCandidateMapper itemCandidateMapper;
    private final MemoryCandidateMapper candidateMapper;
    private final MemoryEvidenceMapper evidenceMapper;
    private final MemoryEpisodeMapper episodeMapper;
    private final MemoryProfileMapper profileMapper;
    private final MemorySessionMapper sessionMapper;
    private final MemoryRetrievalTraceMapper retrievalTraceMapper;
    private final MemoryConsolidationService consolidationService;
    private final MemoryPersonalizationService personalizationService;

    public MemoryManagementService(
            MemoryItemMapper itemMapper,
            MemoryItemCandidateMapper itemCandidateMapper,
            MemoryCandidateMapper candidateMapper,
            MemoryEvidenceMapper evidenceMapper,
            MemoryEpisodeMapper episodeMapper,
            MemoryProfileMapper profileMapper,
            MemorySessionMapper sessionMapper,
            MemoryRetrievalTraceMapper retrievalTraceMapper,
            MemoryConsolidationService consolidationService,
            MemoryPersonalizationService personalizationService
    ) {
        this.itemMapper = itemMapper;
        this.itemCandidateMapper = itemCandidateMapper;
        this.candidateMapper = candidateMapper;
        this.evidenceMapper = evidenceMapper;
        this.episodeMapper = episodeMapper;
        this.profileMapper = profileMapper;
        this.sessionMapper = sessionMapper;
        this.retrievalTraceMapper = retrievalTraceMapper;
        this.consolidationService = consolidationService;
        this.personalizationService = personalizationService;
    }

    public MemoryManagementResponse getOverview(Long userId) {
        requireUser(userId);
        List<MemoryManagementItemResponse> memories = itemMapper.listActive(userId, MAX_ITEMS).stream()
                .map(this::toResponse)
                .toList();
        return new MemoryManagementResponse(
                personalizationService.getState(userId),
                itemMapper.countActiveOwned(userId),
                memories
        );
    }

    @Transactional
    public MemoryManagementItemResponse updateItem(
            Long userId,
            Long memoryId,
            MemoryItemUpdateRequest request
    ) {
        requireUser(userId);
        if (memoryId == null || memoryId <= 0 || request == null
                || request.version() == null || request.version() < 0
                || request.strength() == null
                || request.strength().compareTo(BigDecimal.ZERO) < 0
                || request.strength().compareTo(BigDecimal.ONE) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "记忆修改请求无效");
        }

        MemoryItem item = itemMapper.findActiveOwned(userId, memoryId);
        if (item == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "记忆不存在");
        }
        if (!request.version().equals(item.getVersion())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "记忆已更新，请刷新后重试");
        }

        String preference = normalizePreference(request.preference());
        if (!allowedPreferences(item.getMemoryType()).contains(preference)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "该类记忆不支持此偏好选项");
        }
        if (itemMapper.updateUserManaged(userId, memoryId, item.getVersion(), preference,
                request.strength().setScale(4, java.math.RoundingMode.HALF_UP)) != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "记忆已被其他请求修改，请刷新后重试");
        }
        // User-edited source candidates must not be replayed as fresh model decisions.
        candidateMapper.rejectCandidatesForMemory(userId, memoryId);
        consolidationService.refreshProfileForManagement(userId);

        MemoryItem updated = itemMapper.findActiveOwned(userId, memoryId);
        if (updated == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "记忆已变化，请刷新后重试");
        }
        return toResponse(updated);
    }

    @Transactional
    public MemoryItemDeleteResult deleteItem(Long userId, Long memoryId, Integer version) {
        requireUser(userId);
        if (memoryId == null || memoryId <= 0 || version == null || version < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "记忆删除请求无效");
        }
        MemoryItem item = itemMapper.findActiveOwned(userId, memoryId);
        if (item == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "记忆不存在");
        }
        if (!version.equals(item.getVersion())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "记忆已更新，请刷新后重试");
        }
        if (itemMapper.softDeleteOwned(userId, memoryId, version) != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "记忆已被其他请求修改，请刷新后重试");
        }
        candidateMapper.rejectCandidatesForMemory(userId, memoryId);
        consolidationService.refreshProfileForManagement(userId);
        return new MemoryItemDeleteResult(memoryId, true);
    }

    @Transactional
    public MemoryClearResult clearAll(Long userId) {
        requireUser(userId);
        itemCandidateMapper.deleteAllOwned(userId);
        evidenceMapper.deleteAllOwned(userId);
        int candidates = candidateMapper.deleteAllOwned(userId);
        int episodes = episodeMapper.deleteAllOwned(userId);
        profileMapper.deleteOwned(userId);
        int memories = itemMapper.deleteAllOwned(userId);
        int sessions = sessionMapper.deleteAllOwned(userId);
        int traces = retrievalTraceMapper.deleteAllOwned(userId);
        return new MemoryClearResult(episodes, candidates, memories, sessions, traces);
    }

    private MemoryManagementItemResponse toResponse(MemoryItem item) {
        return new MemoryManagementItemResponse(
                item.getId(), item.getMemoryType(), item.getCanonicalEntity(), item.getPreference(),
                item.getStrength(), item.getConfidence(), item.getEvidenceCount(), item.getOccurrenceCount(),
                item.getSourceCount(), item.getScope(), item.getTemporalType(), item.getFirstSeenAt(),
                item.getLastSeenAt(), item.getVersion(), Boolean.TRUE.equals(item.getUserModified()),
                !allowedPreferences(item.getMemoryType()).isEmpty()
        );
    }

    private Set<String> allowedPreferences(String memoryType) {
        if (memoryType == null) return Set.of();
        return switch (memoryType.toUpperCase(Locale.ROOT)) {
            case "INGREDIENT_PREFERENCE" -> INGREDIENT_PREFERENCES;
            case "RECIPE_PREFERENCE" -> RECIPE_PREFERENCES;
            case "DIET_GOAL" -> DIET_GOAL_PREFERENCES;
            default -> Set.of();
        };
    }

    private String normalizePreference(String preference) {
        if (preference == null || preference.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "记忆偏好不能为空");
        }
        return preference.trim().toUpperCase(Locale.ROOT);
    }

    private void requireUser(Long userId) {
        if (userId == null || userId <= 0) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户身份无效");
        }
    }
}
