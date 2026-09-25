package com.example.food.admin.dashboard;

import com.example.food.admin.dashboard.dto.AdminMemoryEvaluationResponse;
import com.example.food.memory.MemoryCandidateDraft;
import com.example.food.memory.MemoryEpisode;
import com.example.food.memory.MemoryExtractor;
import com.example.food.memory.MemoryQueryPlan;
import com.example.food.memory.MemoryQueryPlanner;
import com.example.food.memory.MemoryReranker;
import com.example.food.memory.MemorySearchCommand;
import com.example.food.memory.MemorySearchHit;
import com.example.food.memory.TagNormalizationService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Runs deterministic, versioned memory checks without calling an LLM or touching user data. */
@Service
public class MemoryEvaluationService {
    public static final String SUITE_VERSION = "memory-evaluation-v1";
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final int RETRIEVAL_K = 3;

    private final MemoryExtractor extractor;
    private final MemoryQueryPlanner queryPlanner;
    private final MemoryReranker reranker;
    private final TagNormalizationService tagNormalizationService;
    private final MemoryEvaluationRunMapper runMapper;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public MemoryEvaluationService(MemoryExtractor extractor, MemoryQueryPlanner queryPlanner,
                                   MemoryReranker reranker, TagNormalizationService tagNormalizationService,
                                   MemoryEvaluationRunMapper runMapper, ObjectMapper objectMapper, Clock clock) {
        this.extractor = extractor;
        this.queryPlanner = queryPlanner;
        this.reranker = reranker;
        this.tagNormalizationService = tagNormalizationService;
        this.runMapper = runMapper;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public synchronized AdminMemoryEvaluationResponse run() {
        Instant started = Instant.now(clock);
        List<AdminMemoryEvaluationResponse.CaseResult> results = new ArrayList<>();
        ExtractionCounts extractionCounts = evaluateExtraction(results);
        double rerankRecallAt3 = evaluateRerankRecall(results);
        double canonicalDedupAccuracy = evaluateCanonicalAliases(results);
        int passed = (int) results.stream().filter(AdminMemoryEvaluationResponse.CaseResult::passed).count();
        Instant finished = Instant.now(clock);
        long duration = Math.max(0L, finished.toEpochMilli() - started.toEpochMilli());
        AdminMemoryEvaluationResponse.Metrics metrics = new AdminMemoryEvaluationResponse.Metrics(
                ratio(extractionCounts.truePositives(), extractionCounts.truePositives() + extractionCounts.falsePositives()),
                ratio(extractionCounts.truePositives(), extractionCounts.truePositives() + extractionCounts.falseNegatives()),
                rerankRecallAt3,
                canonicalDedupAccuracy
        );
        List<AdminMemoryEvaluationResponse.UnmeasuredMetric> unmeasured = unmeasuredMetrics();

        MemoryEvaluationRun run = new MemoryEvaluationRun();
        run.setSuiteVersion(SUITE_VERSION);
        run.setStatus(passed == results.size() ? "PASSED" : "FAILED");
        run.setStartedAt(toLocalDateTime(started));
        run.setFinishedAt(toLocalDateTime(finished));
        run.setTotalCases(results.size());
        run.setPassedCases(passed);
        run.setFailedCases(results.size() - passed);
        run.setDurationMs(duration);
        run.setResultJson(writePayload(metrics, results, unmeasured));
        runMapper.insert(run);
        return response(run, metrics, results, unmeasured);
    }

    public AdminMemoryEvaluationResponse latest() {
        MemoryEvaluationRun run = runMapper.findLatest();
        if (run == null) {
            return new AdminMemoryEvaluationResponse(null, SUITE_VERSION, "NEVER", null, null,
                    0, 0, 0, 0, zeroMetrics(), List.of(), unmeasuredMetrics());
        }
        EvaluationPayload payload = readPayload(run.getResultJson());
        return response(run, payload.metrics(), payload.cases(), payload.unmeasuredMetrics());
    }

    private ExtractionCounts evaluateExtraction(List<AdminMemoryEvaluationResponse.CaseResult> results) {
        List<ExtractionFixture> fixtures = List.of(
                new ExtractionFixture("EXPLICIT_DISLIKE", "显式不喜欢偏好应被准确提取",
                        episode("USER_PREFERENCE_DECLARED", "用户明确表示不吃香菜",
                                "{\"candidateType\":\"INGREDIENT_PREFERENCE\",\"entity\":\"香菜\",\"preference\":\"DISLIKE\"}"),
                        Set.of(candidateKey("INGREDIENT_PREFERENCE", "香菜", "DISLIKE"))),
                new ExtractionFixture("SINGLE_SAVE", "单次收藏只形成低置信度隐式候选",
                        episode("RECIPE_SAVED", "收藏鸡胸肉沙拉",
                                "{\"recipeTitle\":\"鸡胸肉沙拉\",\"ingredients\":[\"鸡胸肉\"]}"),
                        Set.of(candidateKey("RECIPE_PREFERENCE", "鸡胸肉沙拉", "LIKE"),
                                candidateKey("INGREDIENT_PREFERENCE", "鸡胸肉", "LIKE"))),
                new ExtractionFixture("SEARCH_NOT_PREFERENCE", "单纯搜索保留为事件而非长期偏好",
                        episode("RECIPE_SEARCH", "搜索高蛋白晚餐",
                                "{\"query\":\"高蛋白晚餐\",\"ingredients\":[\"鸡胸肉\"]}"),
                        Set.of()),
                new ExtractionFixture("HIGH_RATING", "高评分应提取为正向显式反馈",
                        episode("FINISHED_DISH_REVIEW", "成品评价：回锅肉",
                                "{\"recipeTitle\":\"回锅肉\",\"overallScore\":5}"),
                        Set.of(candidateKey("RECIPE_PREFERENCE", "回锅肉", "LIKE"))),
                new ExtractionFixture("MALFORMED_PAYLOAD", "损坏载荷不得生成候选",
                        episode("RECIPE_SAVED", "收藏记录", "{invalid-json"),
                        Set.of())
        );

        int truePositives = 0;
        int falsePositives = 0;
        int falseNegatives = 0;
        for (ExtractionFixture fixture : fixtures) {
            List<MemoryCandidateDraft> drafts = extractor.extract(fixture.episode());
            Set<String> actual = drafts.stream().map(MemoryEvaluationService::candidateKey)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            Set<String> intersection = new LinkedHashSet<>(actual);
            intersection.retainAll(fixture.expected());
            truePositives += intersection.size();
            falsePositives += actual.size() - intersection.size();
            falseNegatives += fixture.expected().size() - intersection.size();
            results.add(caseResult(fixture.key(), "EXTRACTION", fixture.description(),
                    actual.equals(fixture.expected()), fixture.expected(), actual));
        }

        List<MemoryCandidateDraft> singleSave = extractor.extract(fixtures.get(1).episode());
        BigDecimal maximumConfidence = singleSave.stream()
                .filter(candidate -> "INGREDIENT_PREFERENCE".equals(candidate.candidateType()))
                .map(MemoryCandidateDraft::confidence)
                .max(Comparator.naturalOrder()).orElse(BigDecimal.ZERO);
        boolean cautious = maximumConfidence.compareTo(new BigDecimal("0.7000")) <= 0;
        results.add(caseResult("SINGLE_SAVE_CONFIDENCE", "CONFIDENCE",
                "一次收藏不能被解释成强偏好", cautious, "<= 0.70", maximumConfidence.toPlainString()));
        return new ExtractionCounts(truePositives, falsePositives, falseNegatives);
    }

    private double evaluateRerankRecall(List<AdminMemoryEvaluationResponse.CaseResult> results) {
        List<RetrievalFixture> fixtures = List.of(
                new RetrievalFixture("POST_WORKOUT_DINNER", "健身后晚餐召回高分鸡胸肉历史",
                        "健身后晚餐推荐鸡胸肉，找之前高分的",
                        List.of(
                                hit("EPISODE", 101L, "RECIPE_EXPERIENCE", "高分鸡胸肉晚餐",
                                        "训练后 健身后 晚餐 鸡胸肉 高分 5星", "{\"rating\":5}", null,
                                        "TEMPORARY_CONTEXT", "0.80", "0.90", 5),
                                hit("MEMORY_ITEM", 102L, "INGREDIENT_PREFERENCE", "鸡胸肉 喜欢",
                                        "鸡胸肉 INGREDIENT_PREFERENCE LIKE HIGH PROTEIN", null, "LIKE",
                                        "LONG_TERM", "0.90", "0.90", 40),
                                hit("EPISODE", 103L, "RECIPE_SAVED", "训练后奶茶",
                                        "训练后 晚餐 饮料 甜品", "{}", null, "TEMPORARY_CONTEXT", "1.00", "1.00", 0),
                                hit("MEMORY_ITEM", 104L, "INGREDIENT_PREFERENCE", "香菜 不喜欢",
                                        "香菜 DISLIKE INGREDIENT_PREFERENCE", null, "DISLIKE", "LONG_TERM", "0.95", "0.95", 30)
                        ), Set.of(101L, 102L)),
                new RetrievalFixture("EXPLICIT_AVOID", "明确忌口应排在普通喜好之前",
                        "以后不要给我香菜",
                        List.of(
                                hit("MEMORY_ITEM", 201L, "INGREDIENT_PREFERENCE", "香菜 不喜欢",
                                        "香菜 DISLIKE INGREDIENT_PREFERENCE EXPLICIT", null, "DISLIKE",
                                        "LONG_TERM", "0.95", "0.95", 120),
                                hit("MEMORY_ITEM", 202L, "INGREDIENT_PREFERENCE", "鸡肉 喜欢",
                                        "鸡肉 LIKE INGREDIENT_PREFERENCE", null, "LIKE", "RECENT", "0.55", "0.70", 0),
                                hit("EPISODE", 203L, "RECIPE_SAVED", "收藏香菜牛肉",
                                        "香菜 牛肉 收藏", "{}", null, "TEMPORARY_CONTEXT", "0.50", "0.50", 1),
                                hit("MEMORY_ITEM", 204L, "INGREDIENT_PREFERENCE", "香菜 喜欢",
                                        "香菜 LIKE INGREDIENT_PREFERENCE", null, "LIKE", "LONG_TERM", "0.50", "0.50", 10)
                        ), Set.of(201L))
        );

        int relevantTotal = 0;
        int relevantRetrieved = 0;
        for (RetrievalFixture fixture : fixtures) {
            MemoryQueryPlan plan = queryPlanner.plan(1L, MemorySearchCommand.query(fixture.query()));
            List<Long> actualTop = reranker.rerank(plan, fixture.hits()).stream()
                    .limit(RETRIEVAL_K).map(MemorySearchHit::id).toList();
            Set<Long> found = new LinkedHashSet<>(actualTop);
            found.retainAll(fixture.relevantIds());
            relevantTotal += fixture.relevantIds().size();
            relevantRetrieved += found.size();
            results.add(caseResult(fixture.key(), "RERANK_RECALL_AT_3", fixture.description(),
                    found.size() == fixture.relevantIds().size(), fixture.relevantIds(), actualTop));
        }
        return ratio(relevantRetrieved, relevantTotal);
    }

    private double evaluateCanonicalAliases(List<AdminMemoryEvaluationResponse.CaseResult> results) {
        List<AliasFixture> fixtures = List.of(
                new AliasFixture("CHICKEN_BREAST_ALIASES", "鸡胸、鸡胸肉与英文别名映射到同一合并组",
                        "鸡胸", "鸡胸肉", "chicken breast"),
                new AliasFixture("CHICKEN_PARENT_MERGE", "鸡肉与鸡胸肉共享鸡肉偏好合并组",
                        "鸡肉", "鸡胸肉", "chicken"),
                new AliasFixture("TOMATO_ALIASES", "番茄别名归一到同一标签",
                        "番茄", "西红柿", "tomato"),
                new AliasFixture("EGG_ALIASES", "鸡蛋常用别名归一到同一标签",
                        "鸡蛋", "蛋", "egg")
        );
        int correct = 0;
        for (AliasFixture fixture : fixtures) {
            List<String> groups = fixture.aliases().stream()
                    .map(alias -> tagNormalizationService.normalize("INGREDIENT_PREFERENCE", alias))
                    .map(result -> result.canonicalGroupId())
                    .toList();
            String expectedGroup = groups.get(0);
            boolean sameGroup = expectedGroup != null && groups.stream().allMatch(expectedGroup::equals);
            if (sameGroup) correct++;
            results.add(caseResult(fixture.key(), "CANONICAL_DEDUP", fixture.description(), sameGroup,
                    "同一个 canonicalGroupId", groups));
        }
        return ratio(correct, fixtures.size());
    }

    private List<AdminMemoryEvaluationResponse.UnmeasuredMetric> unmeasuredMetrics() {
        return List.of(
                new AdminMemoryEvaluationResponse.UnmeasuredMetric("conflictResolutionAccuracy", "NOT_MEASURED",
                        "当前尚无独立冲突裁决器及带人工标注的冲突基准集。"),
                new AdminMemoryEvaluationResponse.UnmeasuredMetric("wrongMemoryUsageRate", "NOT_MEASURED",
                        "尚未收集用户对每次实际使用记忆的正确/错误标注。"),
                new AdminMemoryEvaluationResponse.UnmeasuredMetric("staleMemoryUsageRate", "NOT_MEASURED",
                        "尚无记忆过期真值标注，不能仅凭时间衰减分数推断使用错误。"),
                new AdminMemoryEvaluationResponse.UnmeasuredMetric("personalizationWinRate", "NOT_MEASURED",
                        "尚无随机对照、个性化前后用户评价及可比样本。")
        );
    }

    private MemorySearchHit hit(String sourceKind, Long id, String memoryType, String title, String content,
                                String payload, String preference, String temporalType, String confidence,
                                String importance, long ageDays) {
        return new MemorySearchHit(sourceKind, id, memoryType, title, content, payload, preference, temporalType,
                new BigDecimal(confidence), new BigDecimal(importance), LocalDateTime.now(clock).minusDays(ageDays), null);
    }

    private MemoryEpisode episode(String type, String summary, String payload) {
        MemoryEpisode episode = new MemoryEpisode();
        episode.setEpisodeType(type);
        episode.setSummary(summary);
        episode.setPayloadJson(payload);
        return episode;
    }

    private static String candidateKey(MemoryCandidateDraft candidate) {
        return candidateKey(candidate.candidateType(), candidate.entity(), candidate.preference());
    }

    private static String candidateKey(String type, String entity, String preference) {
        return String.join("|", type.toUpperCase(Locale.ROOT), entity.trim().toLowerCase(Locale.ROOT),
                preference.toUpperCase(Locale.ROOT));
    }

    private AdminMemoryEvaluationResponse.CaseResult caseResult(String key, String metric, String description,
                                                               boolean passed, Object expected, Object actual) {
        return new AdminMemoryEvaluationResponse.CaseResult(key, metric, description, passed,
                String.valueOf(expected), String.valueOf(actual));
    }

    private double ratio(long numerator, long denominator) {
        return denominator == 0 ? 1D : (double) numerator / denominator;
    }

    private AdminMemoryEvaluationResponse.Metrics zeroMetrics() {
        return new AdminMemoryEvaluationResponse.Metrics(0D, 0D, 0D, 0D);
    }

    private String writePayload(AdminMemoryEvaluationResponse.Metrics metrics,
                                List<AdminMemoryEvaluationResponse.CaseResult> cases,
                                List<AdminMemoryEvaluationResponse.UnmeasuredMetric> unmeasured) {
        try {
            return objectMapper.writeValueAsString(new EvaluationPayload(metrics, cases, unmeasured));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Memory evaluation result serialization failed", exception);
        }
    }

    private EvaluationPayload readPayload(String json) {
        try {
            return objectMapper.readValue(json, EvaluationPayload.class);
        } catch (Exception exception) {
            throw new IllegalStateException("Memory evaluation result could not be read", exception);
        }
    }

    private AdminMemoryEvaluationResponse response(MemoryEvaluationRun run,
                                                    AdminMemoryEvaluationResponse.Metrics metrics,
                                                    List<AdminMemoryEvaluationResponse.CaseResult> cases,
                                                    List<AdminMemoryEvaluationResponse.UnmeasuredMetric> unmeasured) {
        return new AdminMemoryEvaluationResponse(run.getId(), run.getSuiteVersion(), run.getStatus(),
                toInstant(run.getStartedAt()), toInstant(run.getFinishedAt()), value(run.getTotalCases()),
                value(run.getPassedCases()), value(run.getFailedCases()), value(run.getDurationMs()),
                metrics, cases, unmeasured);
    }

    private LocalDateTime toLocalDateTime(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZONE);
    }

    private Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.atZone(ZONE).toInstant();
    }

    private int value(Integer number) { return number == null ? 0 : Math.max(0, number); }
    private long value(Long number) { return number == null ? 0L : Math.max(0L, number); }

    private record ExtractionFixture(String key, String description, MemoryEpisode episode, Set<String> expected) { }
    private record RetrievalFixture(String key, String description, String query, List<MemorySearchHit> hits,
                                    Set<Long> relevantIds) { }
    private record AliasFixture(String key, String description, String first, String second, String third) {
        List<String> aliases() { return List.of(first, second, third); }
    }
    private record ExtractionCounts(int truePositives, int falsePositives, int falseNegatives) { }
    private record EvaluationPayload(AdminMemoryEvaluationResponse.Metrics metrics,
                                     List<AdminMemoryEvaluationResponse.CaseResult> cases,
                                     List<AdminMemoryEvaluationResponse.UnmeasuredMetric> unmeasuredMetrics) { }
}
