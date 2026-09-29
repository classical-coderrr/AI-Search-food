package com.example.food.admin.dashboard;

import com.example.food.admin.dashboard.dto.AdminMemoryEvaluationResponse;
import com.example.food.memory.MemoryCandidateDraft;
import com.example.food.memory.MemoryCandidate;
import com.example.food.memory.MemoryBehaviorPatternExtractor;
import com.example.food.memory.MemoryConflictResolution;
import com.example.food.memory.MemoryConflictResolver;
import com.example.food.memory.MemoryEpisode;
import com.example.food.memory.MemoryExtractor;
import com.example.food.memory.MemoryItem;
import com.example.food.memory.MemoryQueryPlan;
import com.example.food.memory.MemoryQueryPlanner;
import com.example.food.memory.MemoryReranker;
import com.example.food.memory.MemorySearchCommand;
import com.example.food.memory.MemorySearchHit;
import com.example.food.memory.PersonalizedSkillService;
import com.example.food.memory.SkillPreferenceCatalog;
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
    public static final String SUITE_VERSION = "memory-evaluation-v6";
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final int RETRIEVAL_K = 3;

    private final MemoryExtractor extractor;
    private final MemoryQueryPlanner queryPlanner;
    private final MemoryReranker reranker;
    private final TagNormalizationService tagNormalizationService;
    private final PersonalizedSkillService personalizedSkillService;
    private final MemoryConflictResolver conflictResolver;
    private final MemoryBehaviorPatternExtractor behaviorPatternExtractor;
    private final MemoryEvaluationRunMapper runMapper;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public MemoryEvaluationService(MemoryExtractor extractor, MemoryQueryPlanner queryPlanner,
                                   MemoryReranker reranker, TagNormalizationService tagNormalizationService,
                                   PersonalizedSkillService personalizedSkillService,
                                   MemoryConflictResolver conflictResolver,
                                   MemoryBehaviorPatternExtractor behaviorPatternExtractor,
                                   MemoryEvaluationRunMapper runMapper, ObjectMapper objectMapper, Clock clock) {
        this.extractor = extractor;
        this.queryPlanner = queryPlanner;
        this.reranker = reranker;
        this.tagNormalizationService = tagNormalizationService;
        this.personalizedSkillService = personalizedSkillService;
        this.conflictResolver = conflictResolver;
        this.behaviorPatternExtractor = behaviorPatternExtractor;
        this.runMapper = runMapper;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public synchronized AdminMemoryEvaluationResponse run() {
        Instant started = Instant.now(clock);
        List<AdminMemoryEvaluationResponse.CaseResult> results = new ArrayList<>();
        ExtractionCounts extractionCounts = evaluateExtraction(results);
        evaluateBehaviorPatternLearning(results);
        double rerankRecallAt3 = evaluateRerankRecall(results);
        double canonicalDedupAccuracy = evaluateCanonicalAliases(results);
        ConflictCounts conflictCounts = evaluateConflictSelection(results);
        ConflictAdjudicationCounts adjudicationCounts = evaluateConflictAdjudication(results);
        StaleMemoryCounts staleMemoryCounts = evaluateStaleMemorySelection(results);
        PersonalizationCounts personalizationCounts = evaluatePersonalizationScenarios(results);
        evaluateExecutionSkillPreferences(results);
        int passed = (int) results.stream().filter(AdminMemoryEvaluationResponse.CaseResult::passed).count();
        Instant finished = Instant.now(clock);
        long duration = Math.max(0L, finished.toEpochMilli() - started.toEpochMilli());
        AdminMemoryEvaluationResponse.Metrics metrics = new AdminMemoryEvaluationResponse.Metrics(
                ratio(extractionCounts.truePositives(), extractionCounts.truePositives() + extractionCounts.falsePositives()),
                ratio(extractionCounts.truePositives(), extractionCounts.truePositives() + extractionCounts.falseNegatives()),
                rerankRecallAt3,
                canonicalDedupAccuracy,
                ratio(conflictCounts.correct(), conflictCounts.total()),
                ratio(adjudicationCounts.correct(), adjudicationCounts.total()),
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
        return response(run, payload.metrics(), payload.cases(), unmeasuredMetrics());
    }

    private ExtractionCounts evaluateExtraction(List<AdminMemoryEvaluationResponse.CaseResult> results) {
        List<ExtractionFixture> fixtures = List.of(
                new ExtractionFixture("EXPLICIT_DISLIKE", "显式不喜欢偏好应被准确提取",
                        episode("USER_PREFERENCE_DECLARED", "用户明确表示不吃香菜",
                                "{\"candidateType\":\"INGREDIENT_PREFERENCE\",\"entity\":\"香菜\",\"preference\":\"DISLIKE\"}"),
                        Set.of(candidateKey("INGREDIENT_PREFERENCE", "香菜", "DISLIKE"))),
                new ExtractionFixture("SINGLE_SAVE", "单次收藏只记录菜谱偏好，不把配方食材推断为用户偏好",
                        episode("RECIPE_SAVED", "收藏鸡胸肉沙拉",
                                "{\"recipeTitle\":\"鸡胸肉沙拉\",\"ingredients\":[\"鸡胸肉\"]}"),
                        Set.of(candidateKey("RECIPE_PREFERENCE", "鸡胸肉沙拉", "LIKE"))),
                new ExtractionFixture("SEARCH_NOT_PREFERENCE", "单纯搜索保留为事件而非长期偏好",
                        episode("RECIPE_SEARCH", "搜索高蛋白晚餐",
                                "{\"query\":\"高蛋白晚餐\",\"ingredients\":[\"鸡胸肉\"]}"),
                        Set.of()),
                new ExtractionFixture("HIGH_RATING", "90/100 高分应提取为正向显式反馈",
                        episode("FINISHED_DISH_REVIEW", "成品评价：回锅肉",
                                "{\"recipeTitle\":\"回锅肉\",\"overallScore\":90}"),
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

    private void evaluateBehaviorPatternLearning(List<AdminMemoryEvaluationResponse.CaseResult> results) {
        LocalDateTime now = LocalDateTime.now(clock);
        MemoryEpisode saved = behaviorEpisode(91001L, 910L, now.minusDays(3), "RECIPE_SAVED",
                "鸡胸肉沙拉", "recipe-1",
                "{\"recipeId\":\"recipe-1\",\"title\":\"鸡胸肉沙拉\",\"ingredients\":[\"鸡胸肉\"]}");
        MemoryEpisode cooked = behaviorEpisode(91002L, 910L, now.minusDays(2), "RECIPE_FEEDBACK",
                "鸡胸肉意面", "feedback-2",
                "{\"action\":\"COOKED\",\"recipeTitle\":\"鸡胸肉意面\",\"ingredients\":[\"鸡胸\"]}");
        MemoryEpisode liked = behaviorEpisode(91003L, 910L, now.minusDays(1), "RECIPE_FEEDBACK",
                "鸡胸肉饭", "feedback-3",
                "{\"action\":\"REACTION\",\"reaction\":\"LIKE\",\"recipeTitle\":\"鸡胸肉饭\","
                        + "\"ingredients\":[\"chicken breast\"]}");
        List<MemoryBehaviorPatternExtractor.CandidateSupport> repeated =
                behaviorPatternExtractor.extractFromHistory(liked, List.of(saved, cooked, liked));
        boolean repeatedAccepted = repeated.size() == 3
                && repeated.stream().allMatch(support -> "INGREDIENT_PREFERENCE".equals(support.draft().candidateType())
                && "鸡胸肉".equals(support.draft().entity())
                && "LIKE".equals(support.draft().preference())
                && "IMPLICIT_BEHAVIOR".equals(support.draft().sourceType())
                && support.draft().confidence().compareTo(new BigDecimal("0.7000")) <= 0)
                && repeated.stream().map(support -> support.episode().getId()).distinct().count() == 3;
        results.add(caseResult("REPEATED_RECIPE_BEHAVIOR_LEARNS_INGREDIENT", "IMPLICIT_BEHAVIOR_PATTERN",
                "多次正向行为跨不同菜谱后形成有证据、低于强偏好的近期食材倾向",
                repeatedAccepted, "鸡胸肉 LIKE；3 条来源 Episode；confidence <= 0.70",
                repeated.stream().map(support -> support.episode().getId() + ":" + support.draft().entity()
                        + ":" + support.draft().confidence()).toList()));

        MemoryEpisode singleSave = behaviorEpisode(91101L, 911L, now.minusDays(1), "RECIPE_SAVED",
                "鸡胸肉沙拉", "recipe-1",
                "{\"recipeId\":\"recipe-1\",\"title\":\"鸡胸肉沙拉\",\"ingredients\":[\"鸡胸肉\"]}");
        boolean singleSaveRejected = behaviorPatternExtractor.extractFromHistory(singleSave, List.of(singleSave))
                .isEmpty();
        results.add(caseResult("SINGLE_SAVE_DOES_NOT_LEARN_INGREDIENT", "IMPLICIT_BEHAVIOR_PATTERN",
                "一次收藏菜谱不会把其中食材记录为用户偏好", singleSaveRejected,
                "无 INGREDIENT_PREFERENCE", "候选数="
                        + behaviorPatternExtractor.extractFromHistory(singleSave, List.of(singleSave)).size()));

        MemoryEpisode sameRecipeSaved = behaviorEpisode(91201L, 912L, now.minusDays(3), "RECIPE_SAVED",
                "鸡胸肉沙拉", "recipe-1",
                "{\"recipeId\":\"recipe-1\",\"title\":\"鸡胸肉沙拉\",\"ingredients\":[\"鸡胸肉\"]}");
        MemoryEpisode sameRecipeCooked = behaviorEpisode(91202L, 912L, now.minusDays(2), "RECIPE_FEEDBACK",
                "鸡胸肉沙拉", "feedback-2",
                "{\"action\":\"COOKED\",\"recipeTitle\":\"鸡胸肉沙拉\",\"ingredients\":[\"鸡胸肉\"]}");
        MemoryEpisode sameRecipeLiked = behaviorEpisode(91203L, 912L, now.minusDays(1), "RECIPE_FEEDBACK",
                "鸡胸肉沙拉", "feedback-3",
                "{\"action\":\"REACTION\",\"reaction\":\"LIKE\",\"recipeTitle\":\"鸡胸肉沙拉\","
                        + "\"ingredients\":[\"鸡胸肉\"]}");
        boolean sameRecipeRejected = behaviorPatternExtractor.extractFromHistory(sameRecipeLiked,
                List.of(sameRecipeSaved, sameRecipeCooked, sameRecipeLiked)).isEmpty();
        results.add(caseResult("REPEATED_ACTIONS_ON_ONE_RECIPE_ARE_NOT_A_PATTERN", "IMPLICIT_BEHAVIOR_PATTERN",
                "同一道菜上的重复操作不等于跨菜谱的食材偏好", sameRecipeRejected,
                "无候选：distinctRecipeCount < 2", "候选数="
                        + behaviorPatternExtractor.extractFromHistory(sameRecipeLiked,
                        List.of(sameRecipeSaved, sameRecipeCooked, sameRecipeLiked)).size()));

        MemoryEpisode unsavedA = behaviorEpisode(91301L, 913L, now.minusDays(4), "RECIPE_SAVED",
                "鸡胸肉沙拉", "recipe-1",
                "{\"recipeId\":\"recipe-1\",\"title\":\"鸡胸肉沙拉\",\"ingredients\":[\"鸡胸肉\"]}");
        MemoryEpisode unsavedB = behaviorEpisode(91302L, 913L, now.minusDays(3), "RECIPE_SAVED",
                "鸡胸肉意面", "recipe-2",
                "{\"recipeId\":\"recipe-2\",\"title\":\"鸡胸肉意面\",\"ingredients\":[\"鸡胸肉\"]}");
        MemoryEpisode canceledSave = behaviorEpisode(91303L, 913L, now.minusDays(2), "RECIPE_UNSAVED",
                "取消收藏", "recipe-1", "{\"recipeId\":\"recipe-1\"}");
        MemoryEpisode latestCooked = behaviorEpisode(91304L, 913L, now.minusDays(1), "RECIPE_FEEDBACK",
                "鸡胸肉饭", "feedback-4",
                "{\"action\":\"COOKED\",\"recipeTitle\":\"鸡胸肉饭\",\"ingredients\":[\"鸡胸肉\"]}");
        boolean unsaveRemovesSupport = behaviorPatternExtractor.extractFromHistory(latestCooked,
                List.of(unsavedA, unsavedB, canceledSave, latestCooked)).isEmpty();
        results.add(caseResult("UNSAVED_RECIPE_NO_LONGER_SUPPORTS_PATTERN", "IMPLICIT_BEHAVIOR_PATTERN",
                "取消收藏的菜谱不继续作为正向偏好证据", unsaveRemovesSupport,
                "剩余支持未达到 3 条，不生成候选", "候选数="
                        + behaviorPatternExtractor.extractFromHistory(latestCooked,
                        List.of(unsavedA, unsavedB, canceledSave, latestCooked)).size()));

        MemoryEpisode oldRecipeSave = behaviorEpisode(91311L, 913L, now.minusDays(6), "RECIPE_SAVED",
                "鸡胸肉沙拉", "recipe-1",
                "{\"recipeId\":\"recipe-1\",\"title\":\"鸡胸肉沙拉\",\"ingredients\":[\"鸡胸肉\"]}");
        MemoryEpisode recipeUnsaved = behaviorEpisode(91312L, 913L, now.minusDays(5), "RECIPE_UNSAVED",
                "取消收藏", "recipe-1", "{\"recipeId\":\"recipe-1\"}");
        MemoryEpisode recipeSavedAgain = behaviorEpisode(91313L, 913L, now.minusDays(4), "RECIPE_SAVED",
                "鸡胸肉沙拉", "recipe-1",
                "{\"recipeId\":\"recipe-1\",\"title\":\"鸡胸肉沙拉\",\"ingredients\":[\"鸡胸肉\"]}");
        MemoryEpisode recipeTwoSaved = behaviorEpisode(91314L, 913L, now.minusDays(3), "RECIPE_SAVED",
                "鸡胸肉意面", "recipe-2",
                "{\"recipeId\":\"recipe-2\",\"title\":\"鸡胸肉意面\",\"ingredients\":[\"鸡胸肉\"]}");
        MemoryEpisode recipeThreeCooked = behaviorEpisode(91315L, 913L, now.minusDays(1), "RECIPE_FEEDBACK",
                "鸡胸肉饭", "feedback-5",
                "{\"action\":\"COOKED\",\"recipeTitle\":\"鸡胸肉饭\",\"ingredients\":[\"鸡胸肉\"]}");
        List<MemoryBehaviorPatternExtractor.CandidateSupport> resavedPattern =
                behaviorPatternExtractor.extractFromHistory(recipeThreeCooked,
                        List.of(oldRecipeSave, recipeUnsaved, recipeSavedAgain, recipeTwoSaved, recipeThreeCooked));
        boolean resaveRestoresSupport = resavedPattern.size() == 3
                && resavedPattern.stream().map(support -> support.episode().getId()).distinct().count() == 3
                && resavedPattern.stream().noneMatch(support -> oldRecipeSave.getId().equals(support.episode().getId()));
        results.add(caseResult("RESAVE_AFTER_UNSAVE_RESTORES_ONLY_NEW_SUPPORT", "IMPLICIT_BEHAVIOR_PATTERN",
                "取消收藏会撤销此前收藏证据，但之后重新收藏可重新计入", resaveRestoresSupport,
                "重新收藏后的 3 条支持事件；不包含旧收藏", resavedPattern.stream()
                        .map(support -> support.episode().getId()).toList()));

        MemoryEpisode oneUserSave = behaviorEpisode(91401L, 914L, now.minusDays(2), "RECIPE_SAVED",
                "鸡胸肉沙拉", "recipe-1",
                "{\"recipeId\":\"recipe-1\",\"title\":\"鸡胸肉沙拉\",\"ingredients\":[\"鸡胸肉\"]}");
        MemoryEpisode foreignCooked = behaviorEpisode(91402L, 915L, now.minusDays(1), "RECIPE_FEEDBACK",
                "鸡胸肉意面", "feedback-2",
                "{\"action\":\"COOKED\",\"recipeTitle\":\"鸡胸肉意面\",\"ingredients\":[\"鸡胸肉\"]}");
        MemoryEpisode sameUserCurrent = behaviorEpisode(91403L, 914L, now, "RECIPE_FEEDBACK",
                "鸡胸肉饭", "feedback-3",
                "{\"action\":\"COOKED\",\"recipeTitle\":\"鸡胸肉饭\",\"ingredients\":[\"鸡胸肉\"]}");
        boolean userIsolated = behaviorPatternExtractor.extractFromHistory(sameUserCurrent,
                List.of(oneUserSave, foreignCooked, sameUserCurrent)).isEmpty();
        results.add(caseResult("BEHAVIOR_PATTERN_USER_ISOLATION", "IMPLICIT_BEHAVIOR_PATTERN",
                "其他用户的行为不能补足当前用户的记忆证据", userIsolated,
                "仅当前用户 2 条证据，不生成候选", "候选数="
                        + behaviorPatternExtractor.extractFromHistory(sameUserCurrent,
                        List.of(oneUserSave, foreignCooked, sameUserCurrent)).size()));
    }

    private MemoryEpisode behaviorEpisode(Long id, Long userId, LocalDateTime occurredAt, String type,
                                          String title, String sourceId, String payload) {
        MemoryEpisode episode = new MemoryEpisode();
        episode.setId(id);
        episode.setUserId(userId);
        episode.setEpisodeType(type);
        episode.setSourceType("OFFLINE_EVALUATION");
        episode.setSourceId(sourceId);
        episode.setEventId("offline-event-" + id);
        episode.setIdempotencyKey("offline-idempotency-" + id);
        episode.setSummary(title);
        episode.setPayloadJson(payload);
        episode.setOccurredAt(occurredAt);
        episode.setStatus("RAW");
        return episode;
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

    private ConflictAdjudicationCounts evaluateConflictAdjudication(
            List<AdminMemoryEvaluationResponse.CaseResult> results) {
        LocalDateTime lunchAt = LocalDateTime.parse("2026-01-10T12:00:00");
        LocalDateTime workoutAt = LocalDateTime.parse("2026-01-09T18:00:00");
        List<AdjudicationFixture> fixtures = List.of(
                new AdjudicationFixture("SCENE_MATCH_OUTWEIGHS_OLDER_EVENT",
                        "训练后晚餐优先采用训练后场景中的相反偏好，同时保留长期记录",
                        conflictProfile(5101, 5102, "香菜", "INGREDIENT_CILANTRO",
                                "LONG_TERM", "RECENT", lunchAt, workoutAt),
                        scenePlan("训练后晚餐", "POST_WORKOUT", "DINNER"),
                        List.of(
                                conflictItem(5101, "[6101]", "[]", false),
                                conflictItem(5102, "[6102]", "[]", false)),
                        List.of(
                                conflictEpisode(6101, "{\"scene\":\"WEEKDAY_LUNCH\",\"mealType\":\"LUNCH\"}", lunchAt),
                                conflictEpisode(6102, "{\"scene\":\"POST_WORKOUT_DINNER\",\"mealType\":\"DINNER\"}", workoutAt)),
                        List.of(), 5102L, "DISLIKE", "SCENE_MATCH"),
                new AdjudicationFixture("EXPLICIT_DISLIKE_OUTWEIGHS_NEWER_INFERENCE",
                        "明确不吃优先于更新的一次行为推断，不能反向覆盖",
                        conflictProfile(5201, 5202, "香菜", "INGREDIENT_CILANTRO",
                                "RECENT", "LONG_TERM", LocalDateTime.parse("2026-02-01T10:00:00"),
                                LocalDateTime.parse("2026-01-01T10:00:00")),
                        ingredientPlan("香菜"),
                        List.of(
                                conflictItem(5201, "[]", "[6201]", false),
                                conflictItem(5202, "[]", "[6202]", false)),
                        List.of(),
                        List.of(conflictCandidate(6201, "IMPLICIT_BEHAVIOR"),
                                conflictCandidate(6202, "EXPLICIT")),
                        5202L, "DISLIKE", "EXPLICIT_EVIDENCE"),
                new AdjudicationFixture("NEWER_CONTEXTUAL_PREFERENCE_WINS_THIS_TURN",
                        "缺少匹配场景时按较新记录作本轮临时选择",
                        conflictProfile(5301, 5302, "香菜", "INGREDIENT_CILANTRO",
                                "LONG_TERM", "RECENT", LocalDateTime.parse("2025-01-01T10:00:00"),
                                LocalDateTime.parse("2026-02-01T10:00:00")),
                        ingredientPlan("香菜"),
                        List.of(conflictItem(5301, "[]", "[]", false),
                                conflictItem(5302, "[]", "[]", false)),
                        List.of(), List.of(), 5302L, "DISLIKE", "TEMPORAL_RECENCY"),
                new AdjudicationFixture("AMBIGUOUS_CONFLICT_PRESERVES_BOTH",
                        "时间、场景与显式来源都无法区分时不强行选边",
                        conflictProfile(5401, 5402, "香菜", "INGREDIENT_CILANTRO",
                                "LONG_TERM", "LONG_TERM", lunchAt, lunchAt),
                        ingredientPlan("香菜"),
                        List.of(conflictItem(5401, "[]", "[]", false),
                                conflictItem(5402, "[]", "[]", false)),
                        List.of(), List.of(), null, "UNRESOLVED", "UNRESOLVED")
        );

        int correct = 0;
        for (AdjudicationFixture fixture : fixtures) {
            List<MemoryConflictResolution> decisions = conflictResolver.resolve(fixture.profileJson(),
                    fixture.plan(), fixture.episodes(), fixture.items(), fixture.candidates());
            MemoryConflictResolution actual = decisions.isEmpty() ? null : decisions.get(0);
            boolean passed = actual != null
                    && java.util.Objects.equals(fixture.expectedSelectedItemId(), actual.selectedMemoryItemId())
                    && fixture.expectedPreference().equals(actual.selectedPreference())
                    && fixture.expectedResolutionType().equals(actual.resolutionType());
            if (passed) correct++;
            String expected = fixture.expectedPreference() + "/" + fixture.expectedResolutionType()
                    + "/" + fixture.expectedSelectedItemId();
            String actualValue = actual == null ? "NO_DECISION"
                    : actual.selectedPreference() + "/" + actual.resolutionType() + "/" + actual.selectedMemoryItemId();
            results.add(caseResult(fixture.key(), "CONFLICT_ADJUDICATION", fixture.description(), passed,
                    expected, actualValue));
        }
        return new ConflictAdjudicationCounts(correct, fixtures.size());
    }

    private String conflictProfile(long likeId, long dislikeId, String entity, String canonicalId,
                                   String likeTemporal, String dislikeTemporal,
                                   LocalDateTime likeAt, LocalDateTime dislikeAt) {
        return """
                {"ingredientPreferences":{
                  "liked":[{"id":%d,"entity":"%s","canonicalId":"%s","preference":"LIKE","scope":"USER","temporalType":"%s","lastSeenAt":"%s"}],
                  "disliked":[{"id":%d,"entity":"%s","canonicalId":"%s","preference":"DISLIKE","scope":"USER","temporalType":"%s","lastSeenAt":"%s"}]}}
                """.formatted(likeId, entity, canonicalId, likeTemporal, likeAt,
                dislikeId, entity, canonicalId, dislikeTemporal, dislikeAt);
    }

    private MemoryQueryPlan scenePlan(String query, String scene, String mealType) {
        return new MemoryQueryPlan(query, "RECIPE_RECOMMENDATION", query, null,
                List.of(), List.of(), List.of(scene), List.of(mealType), null, null,
                List.of(), List.of(), null, null, 5);
    }

    private MemoryQueryPlan ingredientPlan(String ingredient) {
        return new MemoryQueryPlan(ingredient, "GENERAL_MEMORY_RECALL", ingredient, null,
                List.of(), List.of(), List.of(), List.of(), null, null,
                List.of(ingredient), List.of(), null, null, 5);
    }

    private MemoryItem conflictItem(long id, String episodeIds, String candidateIds, boolean userModified) {
        MemoryItem item = new MemoryItem();
        item.setId(id);
        item.setSourceEpisodeIdsJson(episodeIds);
        item.setSourceCandidateIdsJson(candidateIds);
        item.setUserModified(userModified);
        return item;
    }

    private MemorySearchHit conflictEpisode(long id, String payload, LocalDateTime occurredAt) {
        return new MemorySearchHit("EPISODE", id, "RECIPE_FEEDBACK", "场景证据", "偏好场景", payload,
                null, "TEMPORARY_CONTEXT", BigDecimal.valueOf(0.8), BigDecimal.valueOf(0.7), occurredAt, null);
    }

    private MemoryCandidate conflictCandidate(long id, String sourceType) {
        MemoryCandidate candidate = new MemoryCandidate();
        candidate.setId(id);
        candidate.setSourceType(sourceType);
        return candidate;
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

    private void evaluateExecutionSkillPreferences(List<AdminMemoryEvaluationResponse.CaseResult> results) {
        String validProfile = """
                {"skillPreferences":[
                  {"entity":"CANDIDATE_COUNT","preference":"3","scope":"USER","temporalType":"LONG_TERM","confidence":0.96},
                  {"entity":"MAX_COOKING_TIME","preference":"30","scope":"USER","temporalType":"LONG_TERM","confidence":0.91},
                  {"entity":"RESPONSE_STYLE","preference":"CONCISE","scope":"USER","temporalType":"LONG_TERM","confidence":0.90},
                  {"entity":"INCLUDE_INGREDIENT_WEIGHT","preference":"YES","scope":"USER","temporalType":"LONG_TERM","confidence":0.89}
                ]}
                """;
        PersonalizedSkillService.SkillContext skill = personalizedSkillService.resolve("推荐一道晚餐", validProfile);
        Map<String, List<String>> strategy = skill == null ? Map.of() : skill.strategy();
        boolean applied = strategy.getOrDefault("candidateCount", List.of()).equals(List.of("3"))
                && strategy.getOrDefault("maxCookingTime", List.of()).equals(List.of("30"))
                && strategy.getOrDefault("responseStyle", List.of()).equals(List.of("CONCISE"))
                && strategy.getOrDefault("includeIngredientWeight", List.of()).equals(List.of("YES"))
                && skill.promptContext().contains("不超过 3 个候选")
                && skill.promptContext().contains("不超过 30 分钟")
                && skill.promptContext().contains("回答风格：简洁")
                && skill.promptContext().contains("明确克数");
        results.add(caseResult("LONG_TERM_EXECUTION_PREFERENCES_APPLIED", "SKILL_EXECUTION_PREFERENCE",
                "高置信度长期执行习惯进入个性化技能策略和提示词", applied,
                "数量=3、时长=30、简洁回答、标注克数", strategy));

        String unsafeProfile = """
                {"skillPreferences":[
                  {"entity":"CANDIDATE_COUNT","preference":"99","scope":"USER","temporalType":"LONG_TERM","confidence":0.99},
                  {"entity":"MAX_COOKING_TIME","preference":"30","scope":"USER","temporalType":"TEMPORARY","confidence":0.99},
                  {"entity":"RESPONSE_STYLE","preference":"DETAILED","scope":"USER","temporalType":"LONG_TERM","confidence":0.74},
                  {"entity":"INCLUDE_INGREDIENT_WEIGHT","preference":"YES","scope":"SESSION","temporalType":"LONG_TERM","confidence":0.99}
                ]}
                """;
        PersonalizedSkillService.SkillContext unsafeSkill = personalizedSkillService.resolve(
                "推荐一道晚餐", unsafeProfile);
        Map<String, List<String>> unsafeStrategy = unsafeSkill == null ? Map.of() : unsafeSkill.strategy();
        boolean rejected = SkillPreferenceCatalog.keys().stream()
                .map(SkillPreferenceCatalog::strategyKey)
                .noneMatch(unsafeStrategy::containsKey);
        results.add(caseResult("UNSAFE_EXECUTION_PREFERENCES_REJECTED", "SKILL_EXECUTION_PREFERENCE",
                "不在白名单、临时、低置信度或非用户范围的执行习惯不得生效", rejected,
                "不应用任何执行习惯", unsafeStrategy));

        boolean negationHandled = !SkillPreferenceCatalog.evidenceSupports(
                "INCLUDE_INGREDIENT_WEIGHT", "YES", "不用写克数")
                && SkillPreferenceCatalog.evidenceSupports("INCLUDE_INGREDIENT_WEIGHT", "NO", "不用写克数")
                && !SkillPreferenceCatalog.evidenceSupports("RESPONSE_STYLE", "DETAILED", "不要太详细，简短一点")
                && SkillPreferenceCatalog.evidenceSupports("RESPONSE_STYLE", "CONCISE", "不要太详细，简短一点")
                && !SkillPreferenceCatalog.evidenceSupports("RESPONSE_STYLE", "DETAILED", "不要详细步骤")
                && SkillPreferenceCatalog.evidenceSupports("RESPONSE_STYLE", "CONCISE", "不要详细步骤")
                && !SkillPreferenceCatalog.evidenceSupports("CANDIDATE_COUNT", "3", "不要给我三个选项")
                && SkillPreferenceCatalog.evidenceSupports("CANDIDATE_COUNT", "3", "不要超过三个选项")
                && !SkillPreferenceCatalog.evidenceSupports("INCLUDE_INGREDIENT_WEIGHT", "YES", "不要具体用量")
                && SkillPreferenceCatalog.evidenceSupports("INCLUDE_INGREDIENT_WEIGHT", "NO", "不要具体用量")
                && SkillPreferenceCatalog.evidenceSupports("MAX_COOKING_TIME", "30", "最好不要超过半小时");
        results.add(caseResult("NEGATED_EXECUTION_PREFERENCE_EVIDENCE", "SKILL_EXECUTION_PREFERENCE",
                "区分否定表达与数量上限，避免把用户意思记反", negationHandled,
                "反向表达拒绝、明确上限接受", "克数否定、回答否定和数量语境按原意判定"));
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
                        "固定离线样本裁决准确率单独展示；线上实际决议仍缺少用户正确/错误标注。"),
                new AdminMemoryEvaluationResponse.UnmeasuredMetric("wrongMemoryUsageRate", "NOT_MEASURED",
                        "线上会另行展示用户主动标注的错误率及覆盖率；自愿反馈样本不能代表全部记忆使用。"),
                new AdminMemoryEvaluationResponse.UnmeasuredMetric("staleMemoryUsageRate", "NOT_MEASURED",
                        "线上会另行展示用户主动标注的过期率及覆盖率；自愿反馈样本不能代表全部记忆使用。"),
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
        return new AdminMemoryEvaluationResponse.Metrics(0D, 0D, 0D, 0D, 0D, 0D, 0D, 0D);
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
    private record AdjudicationFixture(String key, String description, String profileJson, MemoryQueryPlan plan,
                                       List<MemoryItem> items, List<MemorySearchHit> episodes,
                                       List<MemoryCandidate> candidates, Long expectedSelectedItemId,
                                       String expectedPreference, String expectedResolutionType) { }
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
    private record ConflictAdjudicationCounts(int correct, int total) { }
    private record StaleMemoryCounts(int selected, int candidates) { }
    private record PersonalizationCounts(int wins, int total) { }
    private record EvaluationPayload(AdminMemoryEvaluationResponse.Metrics metrics,
                                     List<AdminMemoryEvaluationResponse.CaseResult> cases,
                                     List<AdminMemoryEvaluationResponse.UnmeasuredMetric> unmeasuredMetrics) { }
}
