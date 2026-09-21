package com.example.food.admin.dashboard;

import com.example.food.agent.AgentToolRegistry;
import com.example.food.admin.dashboard.dto.AdminAgentEvaluationResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class AgentEvaluationService {

    public static final String PASSED = "PASSED";
    public static final String FAILED = "FAILED";
    public static final String NEVER = "NEVER";

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final List<EvaluationCase> CASES = List.of(
            new EvaluationCase(
                    "SAVE_RECIPE_EXPLICIT",
                    "明确保存菜谱时只暴露保存工具",
                    "保存这道菜",
                    List.of("recipe_save"),
                    List.of("pantry_manage", "meal_plan_manage", "recipe_library_manage")
            ),
            new EvaluationCase(
                    "SAVE_RECIPE_PRONOUN",
                    "使用代词收藏最近菜谱时暴露保存工具",
                    "把它收藏起来",
                    List.of("recipe_save"),
                    List.of("pantry_manage", "meal_plan_manage")
            ),
            new EvaluationCase(
                    "SAVE_MENU_NOT_RECIPE",
                    "保存周菜单不能误暴露菜谱保存工具",
                    "保存本周菜单",
                    List.of("meal_plan_manage"),
                    List.of("recipe_save")
            ),
            new EvaluationCase(
                    "PANTRY_WRITE_BOUNDARY",
                    "库存写操作应暴露库存域工具而不是通知或个人设置工具",
                    "帮我消耗两个鸡蛋",
                    List.of("pantry_manage"),
                    List.of("notification_manage", "profile_manage", "recipe_save")
            ),
            new EvaluationCase(
                    "RECIPE_QUERY_READ_ONLY",
                    "查询已保存菜谱不能误暴露保存工具",
                    "查看我的菜谱",
                    List.of("saved_recipes", "recipe_library_manage"),
                    List.of("recipe_save")
            ),
            new EvaluationCase(
                    "ORDINARY_CHAT_BOUNDARY",
                    "普通聊天只走只读快速路径",
                    "你好，讲个笑话",
                    List.of("recipe_generate", "pantry_list", "nutrition_profile"),
                    List.of("pantry_manage", "meal_plan_manage", "recipe_save", "profile_manage")
            )
    );

    private final AgentToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;
    private final AgentEvaluationRunMapper runMapper;
    private final AgentEvaluationCaseResultMapper caseResultMapper;
    private final Clock clock;

    public AgentEvaluationService(
            AgentToolRegistry toolRegistry,
            ObjectMapper objectMapper,
            AgentEvaluationRunMapper runMapper,
            AgentEvaluationCaseResultMapper caseResultMapper,
            Clock clock
    ) {
        this.toolRegistry = toolRegistry;
        this.objectMapper = objectMapper;
        this.runMapper = runMapper;
        this.caseResultMapper = caseResultMapper;
        this.clock = clock;
    }

    @Transactional
    public synchronized AdminAgentEvaluationResponse run() {
        Instant started = Instant.now(clock);
        List<EvaluatedCase> evaluatedCases = CASES.stream()
                .map(this::evaluate)
                .toList();
        Instant finished = Instant.now(clock);
        int passed = (int) evaluatedCases.stream().filter(EvaluatedCase::passed).count();

        AgentEvaluationRun run = new AgentEvaluationRun();
        run.setStatus(passed == evaluatedCases.size() ? PASSED : FAILED);
        run.setStartedAt(toLocalDateTime(started));
        run.setFinishedAt(toLocalDateTime(finished));
        run.setTotalCases(evaluatedCases.size());
        run.setPassedCases(passed);
        run.setFailedCases(evaluatedCases.size() - passed);
        run.setDurationMs(Math.max(0L, finished.toEpochMilli() - started.toEpochMilli()));
        runMapper.insert(run);

        LocalDateTime createdAt = toLocalDateTime(finished);
        for (EvaluatedCase evaluatedCase : evaluatedCases) {
            AgentEvaluationCaseResult result = new AgentEvaluationCaseResult();
            result.setRunId(run.getId());
            result.setCaseKey(evaluatedCase.definition().key());
            result.setDescription(evaluatedCase.definition().description());
            result.setInputMessage(evaluatedCase.definition().inputMessage());
            result.setPassed(evaluatedCase.passed());
            result.setExpectedTools(writeJson(evaluatedCase.definition().expectedTools()));
            result.setActualTools(writeJson(evaluatedCase.actualTools()));
            result.setFailureReason(evaluatedCase.failureReason());
            result.setCreatedAt(createdAt);
            caseResultMapper.insert(result);
        }
        return toResponse(run, evaluatedCases.stream().map(this::toCaseResult).toList());
    }

    public AdminAgentEvaluationResponse latest() {
        AgentEvaluationRun run = runMapper.findLatest();
        if (run == null) {
            return new AdminAgentEvaluationResponse(
                    null, NEVER, null, null, 0, 0, 0, 0L, List.of()
            );
        }
        List<AgentEvaluationCaseResult> results = caseResultMapper.findByRunId(run.getId());
        List<AdminAgentEvaluationResponse.CaseResult> cases = results == null
                ? List.of()
                : results.stream().map(this::toCaseResult).toList();
        return toResponse(run, cases);
    }

    private EvaluatedCase evaluate(EvaluationCase definition) {
        Set<String> actualTools = toolRegistry.functionDefinitions(definition.inputMessage(), false).stream()
                .map(definitionMap -> ((java.util.Map<?, ?>) definitionMap.get("function"))
                        .get("name").toString())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<String> missing = definition.expectedTools().stream()
                .filter(tool -> !actualTools.contains(tool))
                .toList();
        List<String> forbidden = definition.forbiddenTools().stream()
                .filter(actualTools::contains)
                .toList();
        List<String> failures = new ArrayList<>();
        if (!missing.isEmpty()) {
            failures.add("缺少工具：" + String.join("、", missing));
        }
        if (!forbidden.isEmpty()) {
            failures.add("误暴露工具：" + String.join("、", forbidden));
        }
        return new EvaluatedCase(definition, actualTools.stream().toList(), failures.isEmpty(), String.join("；", failures));
    }

    private AdminAgentEvaluationResponse.CaseResult toCaseResult(EvaluatedCase evaluatedCase) {
        return new AdminAgentEvaluationResponse.CaseResult(
                evaluatedCase.definition().key(),
                evaluatedCase.definition().description(),
                evaluatedCase.definition().inputMessage(),
                evaluatedCase.passed(),
                evaluatedCase.definition().expectedTools(),
                evaluatedCase.actualTools(),
                evaluatedCase.failureReason().isBlank() ? null : evaluatedCase.failureReason()
        );
    }

    private AdminAgentEvaluationResponse.CaseResult toCaseResult(AgentEvaluationCaseResult result) {
        return new AdminAgentEvaluationResponse.CaseResult(
                result.getCaseKey(),
                result.getDescription(),
                result.getInputMessage(),
                Boolean.TRUE.equals(result.getPassed()),
                readJson(result.getExpectedTools()),
                readJson(result.getActualTools()),
                result.getFailureReason()
        );
    }

    private AdminAgentEvaluationResponse toResponse(
            AgentEvaluationRun run,
            List<AdminAgentEvaluationResponse.CaseResult> cases
    ) {
        return new AdminAgentEvaluationResponse(
                run.getId(),
                run.getStatus(),
                toInstant(run.getStartedAt()),
                toInstant(run.getFinishedAt()),
                nonNegative(run.getTotalCases()),
                nonNegative(run.getPassedCases()),
                nonNegative(run.getFailedCases()),
                nonNegative(run.getDurationMs()),
                cases == null ? List.of() : cases
        );
    }

    private String writeJson(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values == null ? List.of() : values);
        } catch (JsonProcessingException exception) {
            return "[]";
        }
    }

    private List<String> readJson(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(value, new TypeReference<>() { });
        } catch (JsonProcessingException | RuntimeException exception) {
            return List.of();
        }
    }

    private int nonNegative(Integer value) {
        return value == null ? 0 : Math.max(0, value);
    }

    private long nonNegative(Long value) {
        return value == null ? 0L : Math.max(0L, value);
    }

    private LocalDateTime toLocalDateTime(Instant value) {
        return LocalDateTime.ofInstant(value, ZONE);
    }

    private Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.atZone(ZONE).toInstant();
    }

    private record EvaluationCase(
            String key,
            String description,
            String inputMessage,
            List<String> expectedTools,
            List<String> forbiddenTools
    ) {
    }

    private record EvaluatedCase(
            EvaluationCase definition,
            List<String> actualTools,
            boolean passed,
            String failureReason
    ) {
    }
}
