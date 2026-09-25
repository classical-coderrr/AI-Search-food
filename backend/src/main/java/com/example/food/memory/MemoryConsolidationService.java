package com.example.food.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Consolidates immutable extraction candidates into user-owned memory items
 * and rebuilds a small structured profile snapshot for fast agent reads.
 */
@Service
public class MemoryConsolidationService {

    private static final int MAX_CANDIDATES = 500;
    private static final int MAX_ITEMS = 500;
    private static final int PROFILE_VERSION = 1;
    private static final BigDecimal ONE = BigDecimal.ONE;
    private static final BigDecimal ZERO = BigDecimal.ZERO;

    private final MemoryCandidateMapper candidateMapper;
    private final MemoryItemMapper itemMapper;
    private final MemoryItemCandidateMapper itemCandidateMapper;
    private final MemoryProfileMapper profileMapper;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public MemoryConsolidationService(
            MemoryCandidateMapper candidateMapper,
            MemoryItemMapper itemMapper,
            MemoryItemCandidateMapper itemCandidateMapper,
            MemoryProfileMapper profileMapper,
            ObjectMapper objectMapper
    ) {
        this(candidateMapper, itemMapper, itemCandidateMapper, profileMapper,
                objectMapper, Clock.systemDefaultZone());
    }

    MemoryConsolidationService(
            MemoryCandidateMapper candidateMapper,
            MemoryItemMapper itemMapper,
            MemoryItemCandidateMapper itemCandidateMapper,
            MemoryProfileMapper profileMapper,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.candidateMapper = candidateMapper;
        this.itemMapper = itemMapper;
        this.itemCandidateMapper = itemCandidateMapper;
        this.profileMapper = profileMapper;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public ConsolidationResult consolidate(Long userId) {
        requireUser(userId);
        List<MemoryCandidate> candidates = candidateMapper.listForConsolidation(userId, MAX_CANDIDATES);
        Map<String, List<MemoryCandidate>> groups = candidates.stream()
                .collect(Collectors.groupingBy(
                        this::consolidationKey,
                        LinkedHashMap::new,
                        Collectors.toList()
                ));

        int processed = 0;
        int changedItems = 0;
        for (List<MemoryCandidate> group : groups.values()) {
            ConsolidatedGroupResult result = consolidateGroup(userId, group);
            processed += result.processedCandidates();
            changedItems += result.changedItems();
        }

        MemoryProfile profile = rebuildProfile(userId);
        return new ConsolidationResult(
                processed,
                changedItems,
                profile.getProfileVersion(),
                profile.getSourceRevision()
        );
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ConsolidationResult consolidateAfterCommit(Long userId) {
        return consolidate(userId);
    }

    public List<MemoryItem> listOwnedItems(Long userId, int limit) {
        requireUser(userId);
        int safeLimit = Math.max(1, Math.min(limit <= 0 ? 50 : limit, MAX_ITEMS));
        return itemMapper.listActive(userId, safeLimit);
    }

    public MemoryProfile getOwnedProfile(Long userId) {
        requireUser(userId);
        return profileMapper.findOwned(userId);
    }

    private ConsolidatedGroupResult consolidateGroup(Long userId, List<MemoryCandidate> group) {
        MemoryCandidate first = group.get(0);
        String key = consolidationKey(first);
        MemoryItem item = itemMapper.findOwnedByKey(userId, key);
        List<MemoryCandidate> unapplied = unappliedCandidates(userId, item, group);

        if (item == null && unapplied.isEmpty()) {
            return new ConsolidatedGroupResult(0, 0);
        }
        if (item == null) {
            item = newItem(userId, first, key, unapplied);
            try {
                itemMapper.insert(item);
            } catch (DuplicateKeyException duplicate) {
                item = itemMapper.findOwnedByKey(userId, key);
                if (item == null) {
                    throw duplicate;
                }
                unapplied = unappliedCandidates(userId, item, group);
                if (!unapplied.isEmpty()) {
                    mergeIntoExisting(userId, item, unapplied);
                }
            }
        } else if (!unapplied.isEmpty()) {
            mergeIntoExisting(userId, item, unapplied);
        }

        for (MemoryCandidate candidate : group) {
            if (itemCandidateMapper.findByPair(userId, item.getId(), candidate.getId()) == null) {
                MemoryItemCandidate relation = new MemoryItemCandidate();
                relation.setUserId(userId);
                relation.setMemoryItemId(item.getId());
                relation.setCandidateId(candidate.getId());
                try {
                    itemCandidateMapper.insert(relation);
                } catch (DuplicateKeyException ignored) {
                    // Another retry may have created the same provenance link.
                }
            }
            candidateMapper.markConsolidated(userId, candidate.getId(), candidate.getVersion());
        }
        return new ConsolidatedGroupResult(group.size(), 1);
    }

    private List<MemoryCandidate> unappliedCandidates(
            Long userId,
            MemoryItem item,
            List<MemoryCandidate> group
    ) {
        if (item == null) {
            return group;
        }
        List<MemoryCandidate> result = new ArrayList<>();
        for (MemoryCandidate candidate : group) {
            if (itemCandidateMapper.findByPair(userId, item.getId(), candidate.getId()) == null) {
                result.add(candidate);
            }
        }
        return List.copyOf(result);
    }

    private MemoryItem newItem(
            Long userId,
            MemoryCandidate first,
            String key,
            List<MemoryCandidate> candidates
    ) {
        MemoryItem item = new MemoryItem();
        item.setUserId(userId);
        item.setMemoryType(first.getCandidateType());
        item.setCanonicalEntity(normalizeEntity(first.getCanonicalEntity(), first.getEntity()));
        item.setCanonicalTagId(first.getCanonicalTagId());
        item.setCanonicalId(first.getCanonicalId());
        item.setCanonicalCategory(first.getCanonicalCategory());
        item.setCanonicalGroupId(first.getCanonicalGroupId());
        item.setPreference(first.getPreference());
        item.setScope(first.getScope());
        item.setTemporalType(first.getTemporalType());
        item.setStrength(weightedAverageStrength(candidates));
        item.setConfidence(combinedConfidence(ZERO, candidates));
        item.setEvidenceCount(sumEvidence(candidates));
        item.setOccurrenceCount(candidates.size());
        item.setSourceCandidateIdsJson(writeIds(candidates.stream().map(MemoryCandidate::getId).toList()));
        item.setSourceEpisodeIdsJson(writeIds(candidates.stream().map(MemoryCandidate::getEpisodeId).toList()));
        item.setSourceCount(distinctIds(candidates.stream().map(MemoryCandidate::getEpisodeId).toList()).size());
        item.setFirstSeenAt(firstSeen(candidates));
        item.setLastSeenAt(lastSeen(candidates));
        item.setImportance(importance(item.getConfidence(), item.getEvidenceCount()));
        item.setConsolidationKey(key);
        item.setStatus(MemoryItemStatus.ACTIVE.name());
        item.setVersion(0);
        return item;
    }

    private void mergeIntoExisting(Long userId, MemoryItem item, List<MemoryCandidate> candidates) {
        BigDecimal oldConfidence = safe(item.getConfidence());
        BigDecimal newConfidence = combinedConfidence(oldConfidence, candidates);
        int evidenceCount = safeInt(item.getEvidenceCount()) + sumEvidence(candidates);
        int occurrenceCount = safeInt(item.getOccurrenceCount()) + candidates.size();
        Set<Long> sourceCandidateIds = new LinkedHashSet<>(readIds(item.getSourceCandidateIdsJson()));
        sourceCandidateIds.addAll(candidates.stream().map(MemoryCandidate::getId).toList());
        Set<Long> sourceEpisodeIds = new LinkedHashSet<>(readIds(item.getSourceEpisodeIdsJson()));
        sourceEpisodeIds.addAll(candidates.stream().map(MemoryCandidate::getEpisodeId).toList());

        item.setStrength(weightedAverage(item.getStrength(), safeInt(item.getOccurrenceCount()),
                weightedAverageStrength(candidates), candidates.size()));
        item.setConfidence(newConfidence);
        item.setEvidenceCount(evidenceCount);
        item.setOccurrenceCount(occurrenceCount);
        item.setSourceCandidateIdsJson(writeIds(sourceCandidateIds));
        item.setSourceEpisodeIdsJson(writeIds(sourceEpisodeIds));
        item.setSourceCount(sourceEpisodeIds.size());
        item.setFirstSeenAt(min(item.getFirstSeenAt(), firstSeen(candidates)));
        item.setLastSeenAt(max(item.getLastSeenAt(), lastSeen(candidates)));
        item.setImportance(importance(newConfidence, evidenceCount));

        if (itemMapper.updateConsolidated(userId, item, item.getVersion()) != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "记忆合并版本已变化，请稍后重试");
        }
        item.setVersion(item.getVersion() + 1);
    }

    private MemoryProfile rebuildProfile(Long userId) {
        List<MemoryItem> items = itemMapper.listActive(userId, MAX_ITEMS);
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("schemaVersion", PROFILE_VERSION);
        document.put("updatedAt", LocalDateTime.now(clock));
        document.put("summary", Map.of(
                "memoryItemCount", items.size(),
                "highConfidenceCount", items.stream()
                        .filter(item -> safe(item.getConfidence()).compareTo(new BigDecimal("0.75")) >= 0)
                        .count()
        ));
        document.put("ingredientPreferences", preferenceGroups(items, "INGREDIENT_PREFERENCE"));
        document.put("recipePreferences", preferenceGroups(items, "RECIPE_PREFERENCE"));
        document.put("behaviorPatterns", preferenceGroups(items, "RECIPE_BEHAVIOR"));
        document.put("dietGoals", items.stream()
                .filter(item -> "DIET_GOAL".equals(item.getMemoryType())
                        && "LONG_TERM".equalsIgnoreCase(item.getTemporalType()))
                .sorted(Comparator.comparing(this::safeConfidence).reversed())
                .map(this::itemView)
                .toList());
        document.put("otherMemories", items.stream()
                .filter(item -> !Set.of("INGREDIENT_PREFERENCE", "RECIPE_PREFERENCE", "RECIPE_BEHAVIOR")
                        .contains(item.getMemoryType()))
                .map(this::itemView)
                .toList());

        MemoryProfile profile = profileMapper.findOwned(userId);
        if (profile == null) {
            profile = new MemoryProfile();
            profile.setUserId(userId);
            profile.setProfileVersion(PROFILE_VERSION);
            profile.setVersion(0);
            profile.setDeletedAt(null);
            profile.setRebuiltAt(LocalDateTime.now(clock));
            profile.setSourceRevision(maxId(items));
            profile.setProfileJson(writeJson(document));
            try {
                profileMapper.insert(profile);
            } catch (DuplicateKeyException duplicate) {
                profile = profileMapper.findOwned(userId);
                if (profile == null) {
                    throw duplicate;
                }
                updateProfile(userId, profile, document, items);
            }
            return profile;
        }
        updateProfile(userId, profile, document, items);
        return profile;
    }

    private void updateProfile(Long userId, MemoryProfile profile, Map<String, Object> document,
                               List<MemoryItem> items) {
        profile.setProfileVersion(PROFILE_VERSION);
        profile.setSourceRevision(maxId(items));
        profile.setRebuiltAt(LocalDateTime.now(clock));
        profile.setProfileJson(writeJson(document));
        if (profileMapper.updateOwned(userId, profile, profile.getVersion()) != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "用户画像版本已变化，请稍后重试");
        }
        profile.setVersion(profile.getVersion() + 1);
    }

    private Map<String, List<Map<String, Object>>> preferenceGroups(
            List<MemoryItem> items,
            String type
    ) {
        Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        groups.put("liked", new ArrayList<>());
        groups.put("disliked", new ArrayList<>());
        groups.put("other", new ArrayList<>());
        items.stream()
                .filter(item -> type.equals(item.getMemoryType()))
                .sorted(Comparator.comparing(this::safeConfidence).reversed())
                .forEach(item -> {
                    String preference = item.getPreference() == null
                            ? "other"
                            : item.getPreference().toLowerCase(Locale.ROOT);
                    String group = "like".equals(preference) ? "liked"
                            : "dislike".equals(preference) ? "disliked" : "other";
                    groups.get(group).add(itemView(item));
                });
        return groups;
    }

    private Map<String, Object> itemView(MemoryItem item) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", item.getId());
        view.put("entity", item.getCanonicalEntity());
        view.put("canonicalTagId", item.getCanonicalTagId());
        view.put("canonicalId", item.getCanonicalId());
        view.put("canonicalCategory", item.getCanonicalCategory());
        view.put("canonicalGroupId", item.getCanonicalGroupId());
        view.put("preference", item.getPreference());
        view.put("strength", item.getStrength());
        view.put("confidence", item.getConfidence());
        view.put("importance", item.getImportance());
        view.put("evidenceCount", item.getEvidenceCount());
        view.put("occurrenceCount", item.getOccurrenceCount());
        view.put("sourceCount", item.getSourceCount());
        view.put("scope", item.getScope());
        view.put("temporalType", item.getTemporalType());
        view.put("firstSeenAt", item.getFirstSeenAt());
        view.put("lastSeenAt", item.getLastSeenAt());
        return view;
    }

    private BigDecimal combinedConfidence(BigDecimal base, Collection<MemoryCandidate> candidates) {
        BigDecimal remaining = ONE.subtract(clamp(base));
        for (MemoryCandidate candidate : candidates) {
            BigDecimal contribution = clamp(candidate.getConfidence())
                    .multiply(sourceWeight(candidate.getSourceType()))
                    .multiply(new BigDecimal("0.80").add(clamp(candidate.getStrength())
                            .multiply(new BigDecimal("0.20"))));
            remaining = remaining.multiply(ONE.subtract(clamp(contribution)));
        }
        return scale(ONE.subtract(remaining));
    }

    private BigDecimal sourceWeight(String sourceType) {
        if ("EXPLICIT".equalsIgnoreCase(sourceType)
                || "USER_CONFIRMED".equalsIgnoreCase(sourceType)
                || "EXPLICIT_FEEDBACK".equalsIgnoreCase(sourceType)) {
            return new BigDecimal("1.00");
        }
        return new BigDecimal("0.70");
    }

    private BigDecimal weightedAverageStrength(Collection<MemoryCandidate> candidates) {
        BigDecimal totalWeight = ZERO;
        BigDecimal total = ZERO;
        for (MemoryCandidate candidate : candidates) {
            BigDecimal weight = clamp(candidate.getConfidence());
            totalWeight = totalWeight.add(weight);
            total = total.add(clamp(candidate.getStrength()).multiply(weight));
        }
        return totalWeight.compareTo(ZERO) == 0 ? ZERO : scale(total.divide(totalWeight, 8, RoundingMode.HALF_UP));
    }

    private BigDecimal weightedAverage(
            BigDecimal first,
            int firstCount,
            BigDecimal second,
            int secondCount
    ) {
        int totalCount = firstCount + secondCount;
        if (totalCount <= 0) {
            return scale(second);
        }
        return scale(safe(first).multiply(BigDecimal.valueOf(firstCount))
                .add(safe(second).multiply(BigDecimal.valueOf(secondCount)))
                .divide(BigDecimal.valueOf(totalCount), 8, RoundingMode.HALF_UP));
    }

    private BigDecimal importance(BigDecimal confidence, int evidenceCount) {
        BigDecimal evidenceFactor = BigDecimal.valueOf(Math.min(1.0, evidenceCount / 5.0));
        return scale(new BigDecimal("0.35")
                .add(clamp(confidence).multiply(new BigDecimal("0.35")))
                .add(evidenceFactor.multiply(new BigDecimal("0.30"))));
    }

    private String consolidationKey(MemoryCandidate candidate) {
        String raw = String.join("|",
                value(candidate.getCandidateType()),
                canonicalMergeKey(candidate),
                value(candidate.getPreference()),
                value(candidate.getScope()),
                value(candidate.getTemporalType())
        );
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8));
            return value(candidate.getCandidateType()) + "|" + HexFormatHolder.format(digest);
        } catch (Exception exception) {
            throw new IllegalStateException("记忆合并键生成失败", exception);
        }
    }

    private int sumEvidence(Collection<MemoryCandidate> candidates) {
        return candidates.stream().mapToInt(candidate -> Math.max(1, safeInt(candidate.getEvidenceCount()))).sum();
    }

    private LocalDateTime firstSeen(Collection<MemoryCandidate> candidates) {
        return candidates.stream().map(MemoryCandidate::getExtractedAt)
                .filter(value -> value != null).min(LocalDateTime::compareTo)
                .orElse(LocalDateTime.now(clock));
    }

    private LocalDateTime lastSeen(Collection<MemoryCandidate> candidates) {
        return candidates.stream().map(MemoryCandidate::getExtractedAt)
                .filter(value -> value != null).max(LocalDateTime::compareTo)
                .orElse(LocalDateTime.now(clock));
    }

    private String writeIds(Collection<Long> values) {
        return writeJson(distinctIds(values));
    }

    private List<Long> readIds(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, objectMapper.getTypeFactory()
                    .constructCollectionType(List.class, Long.class));
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private List<Long> distinctIds(Collection<Long> values) {
        return values.stream().filter(value -> value != null).collect(Collectors.collectingAndThen(
                Collectors.toCollection(LinkedHashSet::new), List::copyOf));
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("结构化画像序列化失败", exception);
        }
    }

    private String canonicalMergeKey(MemoryCandidate candidate) {
        if (StringUtils.hasText(candidate.getCanonicalGroupId())) {
            return candidate.getCanonicalGroupId().trim().toUpperCase(Locale.ROOT);
        }
        if (StringUtils.hasText(candidate.getCanonicalId())) {
            return candidate.getCanonicalId().trim().toUpperCase(Locale.ROOT);
        }
        return normalizeEntity(candidate.getCanonicalEntity(), candidate.getEntity()).toLowerCase(Locale.ROOT);
    }

    private String normalizeEntity(String canonicalEntity, String rawEntity) {
        String value = StringUtils.hasText(canonicalEntity) ? canonicalEntity : rawEntity;
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    private String value(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private BigDecimal clamp(BigDecimal value) {
        BigDecimal normalized = safe(value);
        return normalized.max(ZERO).min(ONE);
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? ZERO : value;
    }

    private int safeInt(Integer value) {
        return value == null ? 0 : Math.max(0, value);
    }

    private BigDecimal safeConfidence(MemoryItem item) {
        return safe(item.getConfidence());
    }

    private BigDecimal scale(BigDecimal value) {
        return clamp(value).setScale(4, RoundingMode.HALF_UP);
    }

    private LocalDateTime min(LocalDateTime first, LocalDateTime second) {
        if (first == null) return second;
        if (second == null) return first;
        return first.isBefore(second) ? first : second;
    }

    private LocalDateTime max(LocalDateTime first, LocalDateTime second) {
        if (first == null) return second;
        if (second == null) return first;
        return first.isAfter(second) ? first : second;
    }

    private long maxId(List<MemoryItem> items) {
        return items.stream().map(MemoryItem::getId).filter(value -> value != null)
                .max(Long::compareTo).orElse(0L);
    }

    private void requireUser(Long userId) {
        if (userId == null || userId <= 0) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户身份无效");
        }
    }

    private record ConsolidatedGroupResult(int processedCandidates, int changedItems) {
    }

    public record ConsolidationResult(
            int processedCandidateCount,
            int changedItemCount,
            Integer profileVersion,
            Long profileSourceRevision
    ) {
    }

    private static final class HexFormatHolder {
        private static String format(byte[] bytes) {
            StringBuilder result = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) {
                result.append(String.format("%02x", value));
            }
            return result.toString();
        }
    }
}
