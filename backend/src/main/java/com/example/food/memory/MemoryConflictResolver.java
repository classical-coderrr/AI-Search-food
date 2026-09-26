package com.example.food.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.text.Normalizer;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Produces conservative, task-scoped explanations for opposing ingredient memories. */
@Service
public class MemoryConflictResolver {

    private static final int MAX_EXPLANATIONS = 6;
    private static final int MAX_EPISODES_PER_MEMORY = 20;
    private static final DateTimeFormatter MEMORY_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Pattern UNSAFE_LABEL_CHARACTERS = Pattern.compile("[^\\p{IsHan}\\p{L}\\p{N}_\\- ]");

    private final ObjectMapper objectMapper;

    public MemoryConflictResolver(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public boolean hasIngredientConflicts(String profileJson) {
        return !conflicts(parsePreferences(profileJson)).isEmpty();
    }

    public Set<Long> conflictingMemoryItemIds(String profileJson) {
        return conflicts(parsePreferences(profileJson)).stream()
                .flatMap(pair -> java.util.stream.Stream.of(pair.like().id(), pair.dislike().id()))
                .filter(id -> id > 0)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public List<String> explain(String profileJson, MemoryQueryPlan plan,
                                List<MemorySearchHit> retrievedHits, List<MemoryItem> activeItems) {
        return explain(profileJson, plan, retrievedHits, activeItems, List.of());
    }

    public List<String> explain(String profileJson, MemoryQueryPlan plan,
                                List<MemorySearchHit> retrievedHits, List<MemoryItem> activeItems,
                                List<MemoryCandidate> sourceCandidates) {
        List<Preference> preferences = parsePreferences(profileJson);
        List<ConflictPair> pairs = conflicts(preferences);
        if (pairs.isEmpty()) return List.of();

        Map<Long, MemoryItem> itemsById = new LinkedHashMap<>();
        if (activeItems != null) {
            activeItems.stream().filter(item -> item.getId() != null)
                    .forEach(item -> itemsById.put(item.getId(), item));
        }
        Map<Long, MemorySearchHit> episodesById = new LinkedHashMap<>();
        if (retrievedHits != null) {
            retrievedHits.stream()
                    .filter(hit -> "EPISODE".equals(hit.sourceKind()) && hit.id() != null)
                    .forEach(hit -> episodesById.put(hit.id(), hit));
        }
        Map<Long, MemoryCandidate> candidatesById = new LinkedHashMap<>();
        if (sourceCandidates != null) {
            sourceCandidates.stream().filter(candidate -> candidate.getId() != null)
                    .forEach(candidate -> candidatesById.put(candidate.getId(), candidate));
        }
        List<Signal> currentSignals = currentSignals(plan);

        return pairs.stream()
                .filter(pair -> isRelevantToQuery(pair, plan))
                .map(pair -> explain(pair, itemsById, episodesById, candidatesById, currentSignals))
                .sorted(Comparator.comparingInt(Explanation::contextMatch).reversed()
                        .thenComparing(Explanation::newestTime,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(Explanation::entity))
                .limit(MAX_EXPLANATIONS)
                .map(Explanation::text)
                .toList();
    }

    private Explanation explain(ConflictPair pair, Map<Long, MemoryItem> itemsById,
                                Map<Long, MemorySearchHit> episodesById,
                                Map<Long, MemoryCandidate> candidatesById, List<Signal> currentSignals) {
        Side like = side(pair.like(), itemsById, episodesById, candidatesById, currentSignals);
        Side dislike = side(pair.dislike(), itemsById, episodesById, candidatesById, currentSignals);
        Side currentWinner = null;
        String reason = null;

        if (like.explicitEvidence() != dislike.explicitEvidence()) {
            currentWinner = like.explicitEvidence() ? like : dislike;
            reason = "可追溯到用户明确表达或确认的证据优先于另一方向";
        } else if (like.matchesCurrentScene() != dislike.matchesCurrentScene()) {
            currentWinner = like.matchesCurrentScene() ? like : dislike;
            reason = "关联的历史场景与本轮场景相符";
        } else if (isNewer(like, dislike)) {
            currentWinner = like;
            reason = "这条相反记忆的记录时间较新";
        } else if (isNewer(dislike, like)) {
            currentWinner = dislike;
            reason = "这条相反记忆的记录时间较新";
        }

        StringBuilder text = new StringBuilder("同一食材存在正反向记忆：")
                .append(pair.entity()).append("；")
                .append(describe(like, "喜欢")).append("；")
                .append(describe(dislike, "不喜欢")).append("。");
        appendEventChronology(text, like, dislike);
        String currentDescription = currentSignals.isEmpty()
                ? null
                : currentSignals.stream().map(Signal::label).distinct().limit(3)
                .collect(java.util.stream.Collectors.joining("、"));
        if (currentDescription != null) {
            text.append("本轮场景/目标：").append(currentDescription).append("。");
        }
        if (like.explicitEvidence() || dislike.explicitEvidence()) {
            text.append("来源校验：喜欢方向为").append(evidenceLabel(like)).append("，不喜欢方向为")
                    .append(evidenceLabel(dislike)).append("。");
        }

        if (currentWinner != null) {
            String winnerPreference = currentWinner.preference().equals("LIKE") ? "喜欢" : "不喜欢";
            text.append("本轮暂按“").append(winnerPreference).append("”处理，因为").append(reason)
                    .append("；另一方向仍作为不同时间或情境下的记忆保留，不视为已被永久覆盖。");
        } else {
            text.append("现有证据无法可靠判断哪一方向适用于本轮；两条都保留，不要武断归为永久偏好，必要时向用户确认。");
        }
        int contextRank = currentWinner == null ? 0 : currentWinner.explicitEvidence() ? 3
                : like.matchesCurrentScene() != dislike.matchesCurrentScene() ? 2 : 1;
        return new Explanation(text.toString(), contextRank,
                newest(like.profileTime() == null ? like.eventTime() : like.profileTime(),
                        dislike.profileTime() == null ? dislike.eventTime() : dislike.profileTime()), pair.entity());
    }

    private void appendEventChronology(StringBuilder text, Side like, Side dislike) {
        if (like.eventTime() == null || dislike.eventTime() == null
                || like.eventTime().equals(dislike.eventTime())) return;
        Side earlier = like.eventTime().isBefore(dislike.eventTime()) ? like : dislike;
        Side later = earlier == like ? dislike : like;
        text.append("关联事件先后顺序：")
                .append(preferenceLabel(earlier.preference())).append("证据发生于 ")
                .append(formatTime(earlier.eventTime())).append("；")
                .append(preferenceLabel(later.preference())).append("证据发生于 ")
                .append(formatTime(later.eventTime())).append("。");
    }

    private String preferenceLabel(String preference) {
        return "LIKE".equals(preference) ? "喜欢" : "不喜欢";
    }

    private String formatTime(LocalDateTime value) {
        return value.format(MEMORY_TIME_FORMAT);
    }

    private Side side(Preference preference, Map<Long, MemoryItem> itemsById,
                      Map<Long, MemorySearchHit> episodesById, Map<Long, MemoryCandidate> candidatesById,
                      List<Signal> currentSignals) {
        MemoryItem item = itemsById.get(preference.id());
        List<Long> sourceIds = item == null ? List.of() : recentEpisodeIds(item.getSourceEpisodeIdsJson());
        List<Long> candidateIds = item == null ? List.of() : recentEpisodeIds(item.getSourceCandidateIdsJson());
        List<MemoryCandidate> provenance = candidateIds.stream().map(candidatesById::get)
                .filter(java.util.Objects::nonNull).toList();
        boolean userModified = item != null && Boolean.TRUE.equals(item.getUserModified());
        boolean explicitEvidence = userModified
                || provenance.stream().anyMatch(candidate -> isExplicitSource(candidate.getSourceType()));
        boolean behaviorEvidence = provenance.stream()
                .anyMatch(candidate -> "IMPLICIT_BEHAVIOR".equalsIgnoreCase(candidate.getSourceType()));
        List<Signal> historicalSignals = new ArrayList<>();
        LocalDateTime latestEpisodeTime = null;
        for (Long sourceId : sourceIds) {
            MemorySearchHit hit = episodesById.get(sourceId);
            if (hit == null) continue;
            latestEpisodeTime = newest(latestEpisodeTime, hit.occurredAt());
            historicalSignals.addAll(episodeSignals(hit.payload()));
        }
        LocalDateTime profileTime = parseTime(preference.lastSeenAt());
        boolean sceneMatch = historicalSignals.stream().anyMatch(historical -> currentSignals.stream()
                .anyMatch(current -> sameSignal(historical, current)));
        return new Side(preference.preference(), preference.temporalType(), preference.scope(),
                profileTime, latestEpisodeTime, List.copyOf(new LinkedHashSet<>(historicalSignals)), sceneMatch,
                explicitEvidence, userModified || !provenance.isEmpty(), behaviorEvidence);
    }

    private String describe(Side side, String polarity) {
        String temporalLabel = switch (side.temporalType()) {
            case "LONG_TERM", "EXPLICIT_PREFERENCE" -> "长期记录";
            case "RECENT", "SHORT_TERM_TREND", "TEMPORARY_CONTEXT", "SESSION" -> "近期/临时记录";
            default -> "时间类型未标明的记录";
        };
        String time = side.profileTime() == null ? "记忆更新时间未记录"
                : "记忆最近更新于 " + formatTime(side.profileTime());
        if (side.eventTime() != null) {
            time += "，关联行为发生于 " + formatTime(side.eventTime());
        }
        String scope = StringUtils.hasText(side.scope()) ? "，范围 " + displayScope(side.scope()) : "";
        String scenes = side.historicalSignals().isEmpty() ? "，未检索到可核实的历史场景/餐次证据"
                : "，关联场景/餐次/目标 " + side.historicalSignals().stream()
                .map(Signal::label).distinct().limit(2).collect(java.util.stream.Collectors.joining("、"));
        return temporalLabel + "倾向" + polarity + "（" + time + scope + scenes + "）";
    }

    private List<Preference> parsePreferences(String profileJson) {
        if (!StringUtils.hasText(profileJson)) return List.of();
        try {
            JsonNode profile = objectMapper.readTree(profileJson).path("ingredientPreferences");
            List<Preference> result = new ArrayList<>();
            readPreferenceArray(profile.path("liked"), "LIKE", result);
            readPreferenceArray(profile.path("disliked"), "DISLIKE", result);
            return List.copyOf(result);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private void readPreferenceArray(JsonNode values, String groupPreference, List<Preference> target) {
        if (!values.isArray()) return;
        for (JsonNode value : values) {
            String entity = safeLabel(value.path("entity").asText(null));
            if (entity == null) continue;
            String preference = value.path("preference").asText(groupPreference).trim().toUpperCase(Locale.ROOT);
            if (!"LIKE".equals(preference) && !"DISLIKE".equals(preference)) continue;
            Set<String> canonicalIdentities = canonicalIdentities(value);
            target.add(new Preference(value.path("id").asLong(0L), canonicalIdentities, entity, preference,
                    safeCode(value.path("temporalType").asText(null)),
                    safeCode(value.path("scope").asText(null)),
                    value.path("lastSeenAt").asText(null)));
        }
    }

    private List<ConflictPair> conflicts(List<Preference> preferences) {
        Map<String, ConflictPair> pairs = new LinkedHashMap<>();
        for (Preference first : preferences) {
            if (!"LIKE".equals(first.preference())) continue;
            for (Preference second : preferences) {
                if (!"DISLIKE".equals(second.preference()) || !sameIngredient(first, second)) continue;
                String key = conflictKey(first, second);
                ConflictPair existing = pairs.get(key);
                if (existing == null || preferenceScore(first, second) > preferenceScore(existing.like(), existing.dislike())) {
                    pairs.put(key, new ConflictPair(first, second, first.entity()));
                }
            }
        }
        return List.copyOf(pairs.values());
    }

    private int preferenceScore(Preference like, Preference dislike) {
        return ("RECENT".equals(like.temporalType()) ? 1 : 0)
                + ("RECENT".equals(dislike.temporalType()) ? 1 : 0)
                + (like.lastSeenAt() != null ? 1 : 0) + (dislike.lastSeenAt() != null ? 1 : 0);
    }

    private boolean isRelevantToQuery(ConflictPair pair, MemoryQueryPlan plan) {
        if (plan == null || plan.ingredients() == null || plan.ingredients().isEmpty()) return true;
        return plan.ingredients().stream().map(this::safeLabel).filter(java.util.Objects::nonNull)
                .map(this::normalizeKey)
                .anyMatch(ingredient -> {
                    String entity = normalizeKey(pair.entity());
                    return hasPrefixAliasMatch(ingredient, entity);
                });
    }

    private boolean isExplicitSource(String sourceType) {
        return "EXPLICIT".equalsIgnoreCase(sourceType)
                || "USER_CONFIRMED".equalsIgnoreCase(sourceType)
                || "EXPLICIT_FEEDBACK".equalsIgnoreCase(sourceType);
    }

    private String evidenceLabel(Side side) {
        if (side.explicitEvidence()) return "用户明确表达/确认";
        if (side.behaviorEvidence()) return "行为推断";
        return side.sourceKnown() ? "非显式来源" : "来源未能追溯";
    }

    private String displayScope(String scope) {
        return switch (scope) {
            case "USER", "LONG_TERM" -> "个人长期偏好";
            case "SESSION" -> "当前会话";
            case "SCENE" -> "特定场景";
            case "TEMPORARY" -> "临时状态";
            default -> scope;
        };
    }

    private Set<String> canonicalIdentities(JsonNode value) {
        Set<String> identities = new LinkedHashSet<>();
        for (String field : List.of("canonicalGroupId", "canonicalId", "canonicalTagId")) {
            String id = value.path(field).asText(null);
            if (StringUtils.hasText(id) && !"0".equals(id)) {
                String kind = "canonicalTagId".equals(field) ? "TAG_ID:" : "CANONICAL:";
                identities.add(kind + normalizeKey(id));
            }
        }
        return Set.copyOf(identities);
    }

    private boolean sameIngredient(Preference first, Preference second) {
        boolean sameCanonicalTag = first.canonicalIdentities().stream()
                .anyMatch(second.canonicalIdentities()::contains);
        return sameCanonicalTag || normalizeKey(first.entity()).equals(normalizeKey(second.entity()));
    }

    private String conflictKey(Preference first, Preference second) {
        return first.canonicalIdentities().stream().filter(second.canonicalIdentities()::contains)
                .sorted().findFirst().orElse("ENTITY:" + normalizeKey(first.entity()));
    }

    private boolean hasPrefixAliasMatch(String first, String second) {
        if (first.equals(second)) return true;
        int shorterLength = Math.min(first.length(), second.length());
        String shorter = first.length() <= second.length() ? first : second;
        int minimumLength = shorter.codePoints()
                .allMatch(codePoint -> Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN) ? 2 : 5;
        return shorterLength >= minimumLength && (first.startsWith(second) || second.startsWith(first));
    }

    private List<Signal> currentSignals(MemoryQueryPlan plan) {
        if (plan == null) return List.of();
        List<Signal> result = new ArrayList<>();
        addSignals(result, plan.scenes(), "SCENE");
        addSignals(result, plan.mealTypes(), "MEAL");
        addSignals(result, plan.dietGoals(), "GOAL");
        return List.copyOf(result);
    }

    private void addSignals(List<Signal> target, List<String> values, String type) {
        if (values == null) return;
        for (String value : values) {
            String clean = safeLabel(value);
            if (clean != null) target.add(new Signal(type, normalizeKey(clean), displaySignal(clean)));
        }
    }

    private List<Signal> episodeSignals(String payload) {
        if (!StringUtils.hasText(payload)) return List.of();
        try {
            JsonNode root = objectMapper.readTree(payload);
            List<Signal> result = new ArrayList<>();
            addEpisodeSignal(result, root, "scene", "SCENE");
            addEpisodeSignal(result, root, "sceneName", "SCENE");
            addEpisodeSignal(result, root, "occasion", "SCENE");
            addEpisodeSignal(result, root, "mealType", "MEAL");
            addEpisodeSignal(result, root, "goal", "GOAL");
            addEpisodeSignal(result, root, "dietGoal", "GOAL");
            return List.copyOf(result);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private void addEpisodeSignal(List<Signal> target, JsonNode payload, String field, String type) {
        String value = safeLabel(payload.path(field).asText(null));
        if (value != null) target.add(new Signal(type, normalizeKey(value), displaySignal(value)));
    }

    private boolean sameSignal(Signal first, Signal second) {
        if (!first.type().equals(second.type())) return false;
        String a = first.key();
        String b = second.key();
        return hasPrefixAliasMatch(a, b);
    }

    private String displaySignal(String value) {
        String upper = value.toUpperCase(Locale.ROOT);
        return switch (upper) {
            case "POST_WORKOUT", "POST_WORKOUT_DINNER" -> "训练后" + (upper.endsWith("DINNER") ? "晚餐" : "场景");
            case "BREAKFAST" -> "早餐";
            case "LUNCH" -> "午餐";
            case "DINNER" -> "晚餐";
            case "MUSCLE_GAIN" -> "增肌目标";
            case "FAT_LOSS", "WEIGHT_LOSS" -> "减脂目标";
            case "HIGH_PROTEIN" -> "高蛋白目标";
            default -> value.replace('_', ' ');
        };
    }

    private List<Long> recentEpisodeIds(String json) {
        if (!StringUtils.hasText(json)) return List.of();
        try {
            JsonNode ids = objectMapper.readTree(json);
            if (!ids.isArray()) return List.of();
            List<Long> result = new ArrayList<>();
            ids.forEach(id -> {
                if (id.canConvertToLong() && id.asLong() > 0) result.add(id.asLong());
            });
            int from = Math.max(0, result.size() - MAX_EPISODES_PER_MEMORY);
            return List.copyOf(result.subList(from, result.size()));
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private String safeLabel(String value) {
        if (!StringUtils.hasText(value)) return null;
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .replaceAll("[\\p{Cntrl}]", " ");
        normalized = UNSAFE_LABEL_CHARACTERS.matcher(normalized).replaceAll(" ")
                .replaceAll("\\s+", " ").trim();
        if (normalized.isEmpty()) return null;
        return normalized.length() <= 48 ? normalized : normalized.substring(0, 48);
    }

    private String safeCode(String value) {
        if (!StringUtils.hasText(value)) return "";
        return value.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_\\-]", "");
    }

    private String normalizeKey(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .replaceAll("[\\s_\\-]", "").toLowerCase(Locale.ROOT);
    }

    private LocalDateTime parseTime(String value) {
        if (!StringUtils.hasText(value)) return null;
        try {
            return LocalDateTime.parse(value);
        } catch (DateTimeParseException ignored) {
            try {
                return java.time.LocalDate.parse(value).atStartOfDay();
            } catch (DateTimeParseException alsoIgnored) {
                return null;
            }
        }
    }

    private boolean isNewer(Side first, Side second) {
        if (first.eventTime() != null && second.eventTime() != null) {
            return first.eventTime().isAfter(second.eventTime());
        }
        return first.profileTime() != null && second.profileTime() != null
                && first.profileTime().isAfter(second.profileTime());
    }

    private LocalDateTime newest(LocalDateTime first, LocalDateTime second) {
        if (first == null) return second;
        if (second == null) return first;
        return first.isAfter(second) ? first : second;
    }

    private record Preference(long id, Set<String> canonicalIdentities, String entity, String preference,
                              String temporalType, String scope, String lastSeenAt) { }
    private record ConflictPair(Preference like, Preference dislike, String entity) { }
    private record Signal(String type, String key, String label) { }
    private record Side(String preference, String temporalType, String scope,
                        LocalDateTime profileTime, LocalDateTime eventTime,
                        List<Signal> historicalSignals, boolean matchesCurrentScene,
                        boolean explicitEvidence, boolean sourceKnown, boolean behaviorEvidence) { }
    private record Explanation(String text, int contextMatch, LocalDateTime newestTime, String entity) { }
}
