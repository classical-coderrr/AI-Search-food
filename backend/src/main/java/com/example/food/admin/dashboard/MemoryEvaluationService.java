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
import com.example.food.memory.PersonalizedSkillService;
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
import java.util.Map;
import java.util.Set;

/** Runs deterministic, versioned memory checks without calling an LLM or touching user data. */
@Service
public class MemoryEvaluationService {
    public static final String SUITE_VERSION = "memory-evaluation-v2";
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final int RETRIEVAL_K = 3;

    private final MemoryExtractor extractor;
    private final MemoryQueryPlanner queryPlanner;
    private final MemoryReranker reranker;
    private final TagNormalizationService tagNormalizationService;
    private final PersonalizedSkillService personalizedSkillService;
    private final MemoryEvaluationRunMapper runMapper;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public MemoryEvaluationService(MemoryExtractor extractor, MemoryQueryPlanner queryPlanner,
                                   MemoryReranker reranker, TagNormalizationService tagNormalizationService,
                                   PersonalizedSkillService personalizedSkillService,
                                   MemoryEvaluationRunMapper runMapper, ObjectMapper objectMapper, Clock clock) {
        this.extractor = extractor;
        this.queryPlanner = queryPlanner;
        this.reranker = reranker;
        this.tagNormalizationService = tagNormalizationService;
        this.personalizedSkillService = personalizedSkillService;
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
        ConflictCounts conflictCounts = evaluateConflictSelection(results);
        StaleMemoryCounts staleMemoryCounts = evaluateStaleMemorySelection(results);
        PersonalizationCounts personalizationCounts = evaluatePersonalizationScenarios(results);
        int passed = (int) results.stream().filter(AdminMemoryEvaluationResponse.CaseResult::passed).count();
        Instant finished = Instant.now(clock);
        long duration = Math.max(0L, finished.toEpochMilli() - started.toEpochMilli());
        AdminMemoryEvaluationResponse.Metrics metrics = new AdminMemoryEvaluationResponse.Metrics(
                ratio(extractionCounts.truePositives(), extractionCounts.truePositives() + extractionCounts.falsePositives()),
                ratio(extractionCounts.truePositives(), extractionCounts.truePositives() + extractionCounts.falseNegatives()),
                rerankRecallAt3,
                canonicalDedupAccuracy,
                ratio(conflictCounts.correct(), conflictCounts.total()),
                ratio(staleMemoryCounts.selected(), staleMemoryCounts.candidates()),
                ratio(personalizationCounts.wins(), personalizationCounts.total())
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

    /** Measures which conflicting candidate the current retrieval ranking selects, not persistent conflict merging. */
    private ConflictCounts evaluateConflictSelection(List<AdminMemoryEvaluationResponse.CaseResult> results) {
        List<ConflictFixture> fixtures = List.of(
                new ConflictFixture("RECENT_CONTEXT_VS_LONG_TERM_TASTE",
                        "近期明确想清淡時，近期状态应优先但保留长期喜欢辣的历史",
                        "最近这顿不要辣，推荐清淡晚餐",
                        List.of(
                                hit("MEMORY_ITEM", 301L, "TASTE_PREFERENCE", "近期想吃清淡少辣",
                                        "最近 这顿 不要辣 清淡 晚餐 DISLIKE", null, "DISLIKE",
                                        "SHORT_TERM_TREND", "0.90", "0.90", 0),
                                hit("MEMORY_ITEM", 302L, "TASTE_PREFERENCE", "长期喜欢辣味",
                                        "长期 稳定口味 喜欢 辣味 LIKE", null, "LIKE",
                                        "EXPLICIT_PREFERENCE", "0.98", "0.98", 60)
                        ), 301L, Set.of(301L, 302L)),
                new ConflictFixture("EXPLICIT_AVOID_VS_SINGLE_SAVE",
                        "明确长期不吃香菜应优先于一次收藏香菜菜谱",
                        "以后推荐菜不要香菜",
                        List.of(
                                hit("MEMORY_ITEM", 303L, "INGREDIENT_PREFERENCE", "明确不吃香菜",
                                        "以后 推荐 菜 不要 香菜 显式 不喜欢 DISLIKE", null, "DISLIKE",
                                        "EXPLICIT_PREFERENCE", "0.99", "0.99", 120),
                                hit("EPISODE", 304L, "RECIPE_SAVED", "近期收藏香菜食谱",
                                        "近期 收藏 香菜 食谱 RECIPE_SAVED", "{}", null,
                                        "TEMPORARY_CONTEXT", "0.52", "0.48", 1)
                        ), 303L, Set.of(303L, 304L))
        );

        int correct = 0;
        for (ConflictFixture fixture : fixtures) {
            List<MemorySearchHit> ranked = reranker.rerank(plan(fixture.query()), fixture.candidates());
            List<Long> topIds = ranked.stream().limit(RETRIEVAL_K).map(MemorySearchHit::id).toList();
            boolean selectedCurrentWinner = !topIds.isEmpty() && fixture.expectedWinnerId().equals(topIds.get(0));
            boolean preservedBothScopes = topIds.containsAll(fixture.preservedIds());
            boolean passed = selectedCurrentWinner && preservedBothScopes;
            if (passed) correct++;
            results.add(caseResult(fixture.key(), "CONFLICT_SCENARIO_SELECTION", fixture.description(), passed,
                    "winner=" + fixture.expectedWinnerId() + ", preserved=" + fixture.preservedIds(), topIds));
        }
        return new ConflictCounts(correct, fixtures.size());
    }

    /** Rate is stale-labeled fixtures selected in Top-3 divided by stale-labeled candidates in this offline suite. */
    private StaleMemoryCounts evaluateStaleMemorySelection(List<AdminMemoryEvaluationResponse.CaseResult> results) {
        List<StaleFixture> fixtures = List.of(
                new StaleFixture("STALE_SHORT_TERM_TREND_120D", "120 天前的短期口味趋势不应进入 Top-3",
                        "训练后晚餐清淡少辣不要辣",
                        staleAndFreshCandidates(401L, "SHORT_TERM_TREND", 120, 402L), 401L),
                new StaleFixture("STALE_TEMPORARY_CONTEXT_30D", "30 天前的临时上下文不应进入 Top-3",
                        "今晚训练后晚餐清淡少辣不要辣",
                        staleAndFreshCandidates(411L, "TEMPORARY_CONTEXT", 30, 412L), 411L)
        );

        int staleSelected = 0;
        int staleCandidates = 0;
        for (StaleFixture fixture : fixtures) {
            List<MemorySearchHit> ranked = reranker.rerank(plan(fixture.query()), fixture.candidates());
            List<Long> topIds = ranked.stream().limit(RETRIEVAL_K).map(MemorySearchHit::id).toList();
            boolean selected = topIds.contains(fixture.staleId());
            if (selected) staleSelected++;
            staleCandidates++;
            results.add(caseResult(fixture.key(), "STALE_MEMORY_TOP_3", fixture.description(), !selected,
                    "staleId=" + fixture.staleId() + " excluded from Top-3", topIds));
        }
        return new StaleMemoryCounts(staleSelected, staleCandidates);
    }

    private List<MemorySearchHit> staleAndFreshCandidates(Long staleId, String temporalType,
                                                           long ageDays, Long freshId) {
        String matchingText = "训练后 晚餐 清淡 少辣 不要辣 DISLIKE";
        return List.of(
                hit("MEMORY_ITEM", staleId, "TASTE_PREFERENCE", "过期的清淡少辣短期趋势",
                        matchingText, null, "DISLIKE", temporalType, "0.99", "0.99", ageDays),
                hit("MEMORY_ITEM", freshId, "TASTE_PREFERENCE", "当前训练后晚餐状态",
                        matchingText, null, "DISLIKE", "EXPLICIT_PREFERENCE", "0.90", "0.90", 0),
                hit("MEMORY_ITEM", freshId + 1, "TASTE_PREFERENCE", "当前晚餐口味反馈",
                        matchingText, null, "DISLIKE", "EXPLICIT_PREFERENCE", "0.90", "0.90", 0),
                hit("MEMORY_ITEM", freshId + 2, "TASTE_PREFERENCE", "当前少辣偏好反馈",
                        matchingText, null, "DISLIKE", "EXPLICIT_PREFERENCE", "0.90", "0.90", 0)
        );
    }

    /** Paired synthetic choices exercise the skill strategy; this is not a live-user or LLM win rate. */
    private PersonalizationCounts evaluatePersonalizationScenarios(
            List<AdminMemoryEvaluationResponse.CaseResult> results) {
        List<PersonalizationFixture> fixtures = List.of(
                new PersonalizationFixture("LONG_TERM_LIKE_IMPROVES_CHOICE",
                        "长期稳定喜欢的食材能把个性化候选提升到基线之前",
                        "推荐一道晚餐",
                        """
                                {"ingredientPreferences":{"liked":[{"entity":"鸡胸肉","scope":"USER",
                                "temporalType":"LONG_TERM","confidence":0.92,"evidenceCount":3}]}}
                                """,
                        "prioritizeIngredients", "鸡胸肉", Set.of("鸡胸肉"), Set.of(), 2.0,
                        List.of(new BenchmarkRecipe("清炒西兰花", "西兰花 蒜"),
                                new BenchmarkRecipe("香煎鸡胸肉", "鸡胸肉 黑胡椒"))),
                new PersonalizationFixture("LONG_TERM_DISLIKE_AVOIDED",
                        "长期高置信度不喜欢的食材应从基线首选中避开",
                        "推荐一道晚餐",
                        """
                                {"ingredientPreferences":{"disliked":[{"entity":"香菜","scope":"USER",
                                "temporalType":"LONG_TERM","confidence":0.97,"evidenceCount":3}]}}
                                """,
                        "avoidIngredients", "香菜", Set.of(), Set.of("香菜"), 2.0,
                        List.of(new BenchmarkRecipe("香菜拌牛肉", "牛肉 香菜"),
                                new BenchmarkRecipe("蒜香牛肉", "牛肉 大蒜"))),
                new PersonalizationFixture("RECENT_REPEATED_DISLIKE_SOFT_AVOID",
                        "重复近期不喜欢只作为软避让信号，而非长期禁忌",
                        "推荐一道晚餐",
                        """
                                {"ingredientPreferences":{"disliked":[{"entity":"香菜","scope":"USER",
                                "temporalType":"RECENT","confidence":0.69,"evidenceCount":2}]}}
                                """,
                        "softAvoidIngredients", "香菜", Set.of(), Set.of("香菜"), 0.5,
                        List.of(new BenchmarkRecipe("香菜豆腐", "豆腐 香菜"),
                                new BenchmarkRecipe("葱烧豆腐", "豆腐 葱")))
        );

        int wins = 0;
        for (PersonalizationFixture fixture : fixtures) {
            PersonalizedSkillService.SkillContext skill = personalizedSkillService.resolve(
                    fixture.query(), fixture.profileJson());
            List<String> signalValues = skill == null ? List.of()
                    : skill.strategy().getOrDefault(fixture.expectedSignal(), List.of());
            boolean signalPresent = signalValues.contains(fixture.expectedIngredient());
            BenchmarkRecipe baseline = fixture.candidates().get(0);
            BenchmarkRecipe personalized = choosePersonalized(fixture.candidates(), skill);
            double baselineUtility = utility(baseline, fixture);
            double personalizedUtility = utility(personalized, fixture);
            boolean won = signalPresent && personalizedUtility > baselineUtility;
            if (won) wins++;
            results.add(caseResult(fixture.key(), "PERSONALIZATION_SCENARIO_WIN", fixture.description(), won,
                    "strategy=" + fixture.expectedSignal() + ", utility>" + baselineUtility,
                    "strategy=" + signalValues + ", choice=" + personalized.title()
                            + ", utility=" + personalizedUtility));
        }
        return new PersonalizationCounts(wins, fixtures.size());
    }

    private BenchmarkRecipe choosePersonalized(List<BenchmarkRecipe> candidates,
                                                PersonalizedSkillService.SkillContext skill) {
        BenchmarkRecipe best = candidates.get(0);
        double bestScore = strategyScore(best, skill);
        for (int i = 1; i < candidates.size(); i++) {
            BenchmarkRecipe candidate = candidates.get(i);
            double score = strategyScore(candidate, skill);
            if (score > bestScore) {
                best = candidate;
                bestScore = score;
            }
        }
        return best;
    }

    private double strategyScore(BenchmarkRecipe recipe, PersonalizedSkillService.SkillContext skill) {
        if (skill == null) return 0D;
        Map<String, List<String>> strategy = skill.strategy();
        double score = 0D;
        score += matchingSignals(recipe, strategy.get("prioritizeIngredients")) * 2D;
        score -= matchingSignals(recipe, strategy.get("avoidIngredients")) * 10D;
        score -= matchingSignals(recipe, strategy.get("softAvoidIngredients"));
        return score;
    }

    private long matchingSignals(BenchmarkRecipe recipe, List<String> signals) {
        if (signals == null || signals.isEmpty()) return 0L;
        String content = (recipe.title() + " " + recipe.ingredients()).toLowerCase(Locale.ROOT);
        return signals.stream().filter(signal -> content.contains(signal.toLowerCase(Locale.ROOT))).count();
    }

    private double utility(BenchmarkRecipe recipe, PersonalizationFixture fixture) {
        double value = 0D;
        for (String ingredient : fixture.likedIngredients()) {
            if (containsIngredient(recipe, ingredient)) value += 1D;
        }
        for (String ingredient : fixture.dislikedIngredients()) {
            if (containsIngredient(recipe, ingredient)) value -= fixture.dislikePenalty();
        }
        return value;
    }

    private boolean containsIngredient(BenchmarkRecipe recipe, String ingredient) {
        String content = (recipe.title() + " " + recipe.ingredients()).toLowerCase(Locale.ROOT);
        return content.contains(ingredient.toLowerCase(Locale.ROOT));
    }

    private MemoryQueryPlan plan(String query) {
        return queryPlanner.plan(1L, MemorySearchCommand.query(query));
    }

    private List<AdminMemoryEvaluationResponse.UnmeasuredMetric> unmeasuredMetrics() {
        return List.of(
                new AdminMemoryEvaluationResponse.UnmeasuredMetric("conflictResolutionAccuracy", "NOT_MEASURED",
                        "离线样本只评估冲突记忆的检索优先级；持久化冲突合并尚无独立裁决器。"),
                new AdminMemoryEvaluationResponse.UnmeasuredMetric("wrongMemoryUsageRate", "NOT_MEASURED",
                        "尚未收集用户对每次实际使用记忆的正确/错误标注。"),
                new AdminMemoryEvaluationResponse.UnmeasuredMetric("staleMemoryUsageRate", "NOT_MEASURED",
                        "离线样本统计 Top-3 召回；线上实际使用仍缺少记忆过期真值标注。"),
                new AdminMemoryEvaluationResponse.UnmeasuredMetric("personalizationWinRate", "NOT_MEASURED",
                        "离线场景胜率不等于真实用户胜率；仍需随机对照及用户评价。")
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
        return new AdminMemoryEvaluationResponse.Metrics(0D, 0D, 0D, 0D, 0D, 0D, 0D);
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
    private record ConflictFixture(String key, String description, String query,
                                   List<MemorySearchHit> candidates, Long expectedWinnerId,
                                   Set<Long> preservedIds) { }
    private record StaleFixture(String key, String description, String query,
                                List<MemorySearchHit> candidates, Long staleId) { }
    private record PersonalizationFixture(String key, String description, String query, String profileJson,
                                          String expectedSignal, String expectedIngredient,
                                          Set<String> likedIngredients, Set<String> dislikedIngredients,
                                          double dislikePenalty, List<BenchmarkRecipe> candidates) { }
    private record BenchmarkRecipe(String title, String ingredients) { }
    private record AliasFixture(String key, String description, String first, String second, String third) {
        List<String> aliases() { return List.of(first, second, third); }
    }
    private record ExtractionCounts(int truePositives, int falsePositives, int falseNegatives) { }
    private record ConflictCounts(int correct, int total) { }
    private record StaleMemoryCounts(int selected, int candidates) { }
    private record PersonalizationCounts(int wins, int total) { }
    private record EvaluationPayload(AdminMemoryEvaluationResponse.Metrics metrics,
                                     List<AdminMemoryEvaluationResponse.CaseResult> cases,
                                     List<AdminMemoryEvaluationResponse.UnmeasuredMetric> unmeasuredMetrics) { }
}
