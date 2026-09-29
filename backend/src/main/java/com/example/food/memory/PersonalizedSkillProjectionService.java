package com.example.food.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Persists task-specific strategy projections derived from the versioned structured profile. */
@Service
public class PersonalizedSkillProjectionService {
    private static final List<SkillTarget> TARGETS = List.of(
            new SkillTarget("推荐一道晚餐", "RECOMMEND_RECIPE"),
            new SkillTarget("帮我生成一周菜单", "GENERATE_MEAL_PLAN"),
            new SkillTarget("搜索菜谱：番茄炒蛋", "SEARCH_RECIPE"),
            new SkillTarget("生成购物清单", "SHOPPING_LIST"),
            new SkillTarget("冰箱里有鸡蛋，推荐吃什么", "FRIDGE_RECIPE"),
            new SkillTarget("今晚健身后推荐晚餐", "POST_WORKOUT_MEAL"));

    private final PersonalizedSkillMapper skillMapper;
    private final PersonalizedSkillService skillService;
    private final MemoryPersonalizationService personalizationService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public PersonalizedSkillProjectionService(PersonalizedSkillMapper skillMapper,
                                               PersonalizedSkillService skillService,
                                               MemoryPersonalizationService personalizationService,
                                               ObjectMapper objectMapper,
                                               Clock clock) {
        this.skillMapper = skillMapper;
        this.skillService = skillService;
        this.personalizationService = personalizationService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public int rebuild(Long userId, MemoryProfile profile) {
        if (userId == null || userId <= 0 || profile == null
                || !userId.equals(profile.getUserId())) return 0;
        if (!personalizationService.isEnabled(userId)) return 0;

        int changed = 0;
        for (SkillTarget target : TARGETS) {
            PersonalizedSkillService.SkillContext derived = skillService.resolve(
                    target.query(), profile.getProfileJson());
            if (derived == null || !target.skillName().equals(derived.skillName())) continue;
            if (!derived.personalized()) {
                changed += skillMapper.softDeleteStale(userId, target.skillName(),
                        safeVersion(profile.getVersion()), LocalDateTime.now(clock));
                continue;
            }
            SkillEvidence evidence = collectEvidence(profile.getProfileJson(), derived.strategy());
            upsert(userId, target.skillName(), writeJson(derived.strategy()), evidence,
                    derived.promptVersion(), safeVersion(profile.getVersion()));
            changed++;
        }
        return changed;
    }

    @Transactional
    public PersonalizedSkillService.SkillContext resolve(Long userId, String query, MemoryProfile profile) {
        PersonalizedSkillService.SkillContext base = skillService.resolve(query, null);
        if (base == null) return null;
        if (userId == null || userId <= 0 || profile == null || !userId.equals(profile.getUserId())
                || !personalizationService.isEnabled(userId)) return base;

        int profileVersion = safeVersion(profile.getVersion());
        PersonalizedSkill stored = skillMapper.findActiveOwned(userId, base.skillName());
        if (stored == null || !profileVersionEquals(stored, profileVersion)
                || !skillService.promptVersion().equals(stored.getPromptVersion())) {
            rebuild(userId, profile);
            stored = skillMapper.findActiveOwned(userId, base.skillName());
        }
        if (stored == null) return base;
        if (!profileVersionEquals(stored, profileVersion)) {
            // A concurrent profile rebuild won the race; keep this response aligned to its snapshot.
            return skillService.resolve(query, profile.getProfileJson());
        }
        return skillService.resolvePersisted(query, stored.getStrategyJson());
    }

    public List<PersonalizedSkill> listOwned(Long userId) {
        if (userId == null || userId <= 0) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户身份无效");
        }
        if (!personalizationService.isEnabled(userId)) return List.of();
        return List.copyOf(skillMapper.listActiveOwned(userId));
    }

    private void upsert(Long userId, String skillName, String strategyJson,
                        SkillEvidence evidence, String promptVersion, int profileVersion) {
        for (int attempt = 0; attempt < 3; attempt++) {
            PersonalizedSkill existing = skillMapper.findOwnedForRebuild(userId, skillName);
            if (existing == null) {
                PersonalizedSkill created = new PersonalizedSkill();
                created.setUserId(userId);
                created.setSkillName(skillName);
                created.setStrategyJson(strategyJson);
                created.setConfidence(evidence.confidence());
                created.setEvidenceCount(evidence.count());
                created.setSourceMemoryIdsJson(writeJson(evidence.memoryIds()));
                created.setPromptVersion(promptVersion);
                created.setSourceProfileVersion(profileVersion);
                created.setVersion(0);
                try {
                    skillMapper.insert(created);
                    return;
                } catch (DuplicateKeyException concurrentInsert) {
                    continue;
                }
            }
            if (safeVersion(existing.getSourceProfileVersion()) > profileVersion) return;
            if (sameProjection(existing, strategyJson, evidence, promptVersion, profileVersion)) return;
            int updated = skillMapper.updateProjection(existing.getId(), userId,
                    safeVersion(existing.getVersion()), strategyJson, evidence.confidence(), evidence.count(),
                    writeJson(evidence.memoryIds()), promptVersion, profileVersion, LocalDateTime.now(clock));
            if (updated == 1) return;
        }
        PersonalizedSkill latest = skillMapper.findOwnedForRebuild(userId, skillName);
        if (latest != null && safeVersion(latest.getSourceProfileVersion()) >= profileVersion) return;
        throw new ResponseStatusException(HttpStatus.CONFLICT, "个性化技能版本已变化，请稍后重试");
    }

    private boolean sameProjection(PersonalizedSkill existing, String strategyJson,
                                   SkillEvidence evidence, String promptVersion, int profileVersion) {
        return strategyJson.equals(existing.getStrategyJson())
                && evidence.confidence().compareTo(existing.getConfidence()) == 0
                && evidence.count() == safeVersion(existing.getEvidenceCount())
                && writeJson(evidence.memoryIds()).equals(existing.getSourceMemoryIdsJson())
                && promptVersion.equals(existing.getPromptVersion())
                && profileVersion == safeVersion(existing.getSourceProfileVersion())
                && existing.getDeletedAt() == null;
    }

    private boolean profileVersionEquals(PersonalizedSkill skill, int version) {
        return skill != null && safeVersion(skill.getSourceProfileVersion()) == version;
    }

    private SkillEvidence collectEvidence(String profileJson, Map<String, List<String>> strategy) {
        JsonNode profile = parseProfile(profileJson);
        Set<String> names = new LinkedHashSet<>();
        strategy.values().forEach(names::addAll);
        Map<String, EvidenceItem> sources = new LinkedHashMap<>();
        collectEvidence(profile, names, sources);
        collectSkillEvidence(profile.path("skillPreferences"), strategy, sources);
        List<Long> memoryIds = sources.values().stream().map(EvidenceItem::id)
                .filter(id -> id != null && id > 0).distinct().sorted().toList();
        int count = sources.values().stream().mapToInt(EvidenceItem::count).sum();
        BigDecimal confidence = sources.isEmpty() ? BigDecimal.ZERO
                : sources.values().stream().map(EvidenceItem::confidence).min(BigDecimal::compareTo)
                .orElse(BigDecimal.ZERO);
        return new SkillEvidence(confidence.setScale(4, java.math.RoundingMode.HALF_UP), count, memoryIds);
    }

    private void collectEvidence(JsonNode node, Set<String> names, Map<String, EvidenceItem> sources) {
        if (node == null) return;
        if (node.isObject()) {
            String entity = node.path("entity").asText(null);
            if (StringUtils.hasText(entity) && names.contains(entity)) {
                Long id = node.path("id").canConvertToLong() ? node.path("id").asLong() : null;
                String key = id != null && id > 0 ? "id:" + id : "entity:" + entity;
                BigDecimal confidence = node.path("confidence").isNumber()
                        ? node.path("confidence").decimalValue().max(BigDecimal.ZERO).min(BigDecimal.ONE)
                        : BigDecimal.ZERO;
                int evidenceCount = Math.max(1, Math.max(node.path("evidenceCount").asInt(0),
                        node.path("occurrenceCount").asInt(0)));
                EvidenceItem item = new EvidenceItem(id, confidence, evidenceCount);
                mergeEvidence(sources, key, item);
            }
            node.elements().forEachRemaining(child -> collectEvidence(child, names, sources));
        } else if (node.isArray()) {
            node.elements().forEachRemaining(child -> collectEvidence(child, names, sources));
        }
    }

    private void collectSkillEvidence(JsonNode preferences, Map<String, List<String>> strategy,
                                      Map<String, EvidenceItem> sources) {
        if (!preferences.isArray()) return;
        for (JsonNode preference : preferences) {
            String key = SkillPreferenceCatalog.normalizeKey(preference.path("entity").asText(null));
            String strategyKey = SkillPreferenceCatalog.strategyKey(key);
            String value = SkillPreferenceCatalog.normalizeValue(key, preference.path("preference").asText(null));
            if (strategyKey == null || value == null || !strategy.getOrDefault(strategyKey, List.of()).contains(value)) {
                continue;
            }
            Long id = preference.path("id").canConvertToLong() ? preference.path("id").asLong() : null;
            BigDecimal confidence = preference.path("confidence").isNumber()
                    ? preference.path("confidence").decimalValue().max(BigDecimal.ZERO).min(BigDecimal.ONE)
                    : BigDecimal.ZERO;
            int evidenceCount = Math.max(1, Math.max(preference.path("evidenceCount").asInt(0),
                    preference.path("occurrenceCount").asInt(0)));
            String sourceKey = id != null && id > 0 ? "id:" + id : "entity:" + key;
            mergeEvidence(sources, sourceKey, new EvidenceItem(id, confidence, evidenceCount));
        }
    }

    private void mergeEvidence(Map<String, EvidenceItem> sources, String key, EvidenceItem item) {
        EvidenceItem previous = sources.get(key);
        if (previous == null) sources.put(key, item);
        else sources.put(key, new EvidenceItem(previous.id(),
                previous.confidence().min(item.confidence()), Math.max(previous.count(), item.count())));
    }

    private JsonNode parseProfile(String json) {
        if (!StringUtils.hasText(json)) return objectMapper.createObjectNode();
        try {
            JsonNode parsed = objectMapper.readTree(json);
            return parsed != null && parsed.isObject() ? parsed : objectMapper.createObjectNode();
        } catch (Exception ignored) {
            return objectMapper.createObjectNode();
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("个性化技能序列化失败", exception);
        }
    }

    private int safeVersion(Integer value) {
        return value == null ? 0 : Math.max(0, value);
    }

    private record SkillTarget(String query, String skillName) { }
    private record EvidenceItem(Long id, BigDecimal confidence, int count) { }
    private record SkillEvidence(BigDecimal confidence, int count, List<Long> memoryIds) { }
}
