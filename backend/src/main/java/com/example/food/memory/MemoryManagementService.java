package com.example.food.memory;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
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
    private final MemoryVectorStoreAdapter vectorStore;
    private final MemoryConflictDecisionMapper conflictDecisionMapper;

    @org.springframework.beans.factory.annotation.Autowired
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
            MemoryPersonalizationService personalizationService,
            MemoryVectorStoreAdapter vectorStore,
            MemoryConflictDecisionMapper conflictDecisionMapper
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
        this.vectorStore = vectorStore;
        this.conflictDecisionMapper = conflictDecisionMapper;
    }

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
            MemoryPersonalizationService personalizationService,
            MemoryVectorStoreAdapter vectorStore
    ) {
        this(itemMapper, itemCandidateMapper, candidateMapper, evidenceMapper, episodeMapper, profileMapper,
                sessionMapper, retrievalTraceMapper, consolidationService, personalizationService, vectorStore, null);
    }

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
        this(itemMapper, itemCandidateMapper, candidateMapper, evidenceMapper, episodeMapper, profileMapper,
                sessionMapper, retrievalTraceMapper, consolidationService, personalizationService, null);
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

    public List<MemoryManagementItemResponse> listAgentMemories(Long userId, int limit) {
        requireUser(userId);
        int safeLimit = Math.max(1, Math.min(limit <= 0 ? 12 : limit, 30));
        return itemMapper.listActive(userId, safeLimit).stream().map(this::toResponse).toList();
    }

    public MemoryManagementItemResponse requireActiveAgentMemory(Long userId, Long memoryId, Integer version) {
        requireUser(userId);
        if (memoryId == null || memoryId <= 0 || version == null || version < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "记忆编号或版本无效");
        }
        MemoryItem item = itemMapper.findActiveOwned(userId, memoryId);
        if (item == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "记忆不存在或不属于当前账号");
        }
        if (!version.equals(item.getVersion())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "记忆已更新，请重新检索后再修改");
        }
        MemoryManagementItemResponse response = toResponse(item);
        if (!response.editable()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "该类记忆不支持偏好修改");
        }
        return response;
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
        if (!allowedPreferences(item.getMemoryType(), item.getCanonicalEntity()).contains(preference)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "该类记忆不支持此偏好选项");
        }
        String consolidationKey = userManagedConsolidationKey(item, preference);
        if ("SKILL_PREFERENCE".equalsIgnoreCase(item.getMemoryType())
                && !consolidationKey.equals(item.getConsolidationKey())) {
            MemoryItem duplicate = itemMapper.findOwnedByKey(userId, consolidationKey);
            if (duplicate != null && !memoryId.equals(duplicate.getId())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "已有相同的执行习惯记录，请先删除重复记录");
            }
        }
        if (itemMapper.updateUserManaged(userId, memoryId, item.getVersion(), preference,
                request.strength().setScale(4, java.math.RoundingMode.HALF_UP), consolidationKey) != 1) {
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
        if (conflictDecisionMapper != null) conflictDecisionMapper.deleteOwnedForMemory(userId, memoryId);
        if (vectorStore != null) vectorStore.deleteSource(userId, "MEMORY_ITEM", memoryId);
        candidateMapper.rejectCandidatesForMemory(userId, memoryId);
        consolidationService.refreshProfileForManagement(userId);
        return new MemoryItemDeleteResult(memoryId, true);
    }

    @Transactional
    public MemoryClearResult clearAll(Long userId) {
        requireUser(userId);
        if (vectorStore != null) vectorStore.deleteAll(userId);
        if (conflictDecisionMapper != null) conflictDecisionMapper.deleteAllOwned(userId);
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
                !allowedPreferences(item.getMemoryType(), item.getCanonicalEntity()).isEmpty()
        );
    }

    private Set<String> allowedPreferences(String memoryType, String entity) {
        if (memoryType == null) return Set.of();
        return switch (memoryType.toUpperCase(Locale.ROOT)) {
            case "INGREDIENT_PREFERENCE" -> INGREDIENT_PREFERENCES;
            case "RECIPE_PREFERENCE" -> RECIPE_PREFERENCES;
            case "DIET_GOAL" -> DIET_GOAL_PREFERENCES;
            case "SKILL_PREFERENCE" -> SkillPreferenceCatalog.allowedValues(entity);
            default -> Set.of();
        };
    }

    private String userManagedConsolidationKey(MemoryItem item, String preference) {
        if (!"SKILL_PREFERENCE".equalsIgnoreCase(item.getMemoryType())) return item.getConsolidationKey();
        String mergeEntity = StringUtils.hasText(item.getCanonicalGroupId()) ? item.getCanonicalGroupId()
                : StringUtils.hasText(item.getCanonicalId()) ? item.getCanonicalId()
                : item.getCanonicalEntity() == null ? "" : item.getCanonicalEntity();
        mergeEntity = mergeEntity.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        String raw = String.join("|", item.getMemoryType().trim().toUpperCase(Locale.ROOT), mergeEntity,
                preference.trim().toUpperCase(Locale.ROOT), item.getScope().trim().toUpperCase(Locale.ROOT),
                item.getTemporalType().trim().toUpperCase(Locale.ROOT));
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return item.getMemoryType().trim().toUpperCase(Locale.ROOT) + "|"
                    + java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("记忆合并键生成失败", exception);
        }
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
