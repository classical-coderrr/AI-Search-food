package com.example.food.memory.mcp;

import com.example.food.agent.AgentKitchenActionService;
import com.example.food.agent.AgentKitchenToolService;
import com.example.food.agent.AgentMemoryToolService;
import com.example.food.agent.AgentToolRegistry;
import com.example.food.memory.MemoryFeedbackType;
import com.example.food.memory.MemoryPersonalizationService;
import com.example.food.memory.MemoryTargetFeedbackRequest;
import com.example.food.memory.MemoryTargetFeedbackService;
import com.example.food.memory.MemoryTargetFeedbackSource;
import com.example.food.memory.MemoryTargetFeedbackTargetResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ElicitFormRequest;
import io.modelcontextprotocol.spec.McpSchema.ElicitResult;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

@Component
public class MemoryMcpToolCatalog {
    private static final Map<String, Object> EMPTY_SCHEMA = objectSchema(Map.of(), List.of());

    private final AgentMemoryToolService memoryToolService;
    private final MemoryTargetFeedbackService feedbackService;
    private final MemoryPersonalizationService personalizationService;
    private final ObjectMapper objectMapper;

    public MemoryMcpToolCatalog(AgentMemoryToolService memoryToolService,
                                MemoryTargetFeedbackService feedbackService,
                                MemoryPersonalizationService personalizationService,
                                ObjectMapper objectMapper) {
        this.memoryToolService = memoryToolService;
        this.feedbackService = feedbackService;
        this.personalizationService = personalizationService;
        this.objectMapper = objectMapper;
    }

    public List<SyncToolSpecification> tools() {
        List<SyncToolSpecification> tools = new ArrayList<>();
        tools.add(tool("memory.search", "检索当前登录用户与本轮任务相关的个人记忆；不检索外部菜谱或知识资料。",
                objectSchema(Map.of(
                        "query", stringProperty("当前问题或需要查找的个人历史"),
                        "limit", integerProperty("返回数量，最多 30")), List.of("query")),
                (exchange, arguments) -> read(AgentToolRegistry.Tool.MEMORY_SEARCH, arguments, exchange)));
        tools.add(tool("memory.profile.get", "读取当前登录用户的结构化画像和可管理的长期记忆。",
                EMPTY_SCHEMA, (exchange, arguments) -> read(AgentToolRegistry.Tool.MEMORY_PROFILE_GET, arguments, exchange)));
        tools.add(tool("memory.episodes.list", "列出当前登录用户的事件记忆，可按事件类型筛选。",
                objectSchema(Map.of(
                        "episodeType", stringProperty("可选事件类型"),
                        "limit", integerProperty("返回数量，最多 30")), List.of()),
                (exchange, arguments) -> read(AgentToolRegistry.Tool.MEMORY_EPISODES_LIST, arguments, exchange)));
        tools.add(tool("memory.recipe.history", "列出当前登录用户的菜谱搜索、收藏、反馈、烹饪和成品评价历史。",
                objectSchema(Map.of("limit", integerProperty("返回数量，最多 30")), List.of()),
                (exchange, arguments) -> read(AgentToolRegistry.Tool.MEMORY_RECIPE_HISTORY, arguments, exchange)));
        tools.add(tool("memory.skill.get", "读取适用于当前任务的个性化执行策略。",
                objectSchema(Map.of("task", stringProperty("当前任务，例如健身后晚餐推荐")), List.of("task")),
                (exchange, arguments) -> read(AgentToolRegistry.Tool.MEMORY_SKILL_GET, arguments, exchange)));
        tools.add(tool("memory.episode.save", "仅保存用户明确表达并在本次 MCP 弹窗中确认的个人偏好事件。执行前必须向用户展示内容并取得明确同意；不得把模型推断或单次行为写成长期偏好。",
                objectSchema(Map.of(
                        "candidateType", enumProperty("偏好类型", List.of("INGREDIENT_PREFERENCE", "DIET_GOAL", "SKILL_PREFERENCE")),
                        "entity", stringProperty("规范化前的食材、饮食目标或技能偏好实体"),
                        "preference", stringProperty("偏好方向；食材使用 LIKE/DISLIKE/AVOID，目标使用 PURSUE/AVOID"),
                        "evidence", stringProperty("用户明确表达偏好的原话"),
                        "idempotencyKey", stringProperty("同一意图重试时必须复用的幂等键")),
                        List.of("candidateType", "entity", "preference", "evidence", "idempotencyKey")),
                this::savePreference));
        tools.add(tool("memory.preference.update", "修改当前登录用户的一条长期偏好记忆。必须先向用户展示变更并通过 MCP 弹窗取得明确同意；使用当前记忆版本，避免覆盖并发更新。",
                objectSchema(Map.of(
                        "memoryId", integerProperty("待修改的记忆 ID"),
                        "version", integerProperty("读取该记忆时的版本号"),
                        "preference", stringProperty("新的偏好方向"),
                        "strength", numberProperty("可选强度，范围 0 到 1")), List.of("memoryId", "version", "preference")),
                this::updatePreference));
        tools.add(tool("memory.feedback.record", "记录用户对本轮实际使用记忆的反馈。必须展示具体记忆及反馈类型，并通过 MCP 弹窗取得用户明确同意。",
                objectSchema(Map.of(
                        "traceId", stringProperty("本轮记忆检索追踪 ID"),
                        "sourceKind", enumProperty("记忆来源类型", List.of("MEMORY_ITEM", "EPISODE")),
                        "sourceId", integerProperty("被使用的记忆或事件 ID"),
                        "feedbackType", enumProperty("反馈类型", Arrays.stream(MemoryFeedbackType.values()).map(Enum::name).toList())),
                        List.of("traceId", "sourceKind", "sourceId", "feedbackType")),
                this::recordFeedback));
        return List.copyOf(tools);
    }

    private CallToolResult read(AgentToolRegistry.Tool tool, JsonNode arguments, McpSyncServerExchange exchange) {
        Long userId = userId(exchange);
        AgentKitchenToolService.ToolResult result = memoryToolService.executeRead(tool, arguments, userId);
        return success(result);
    }

    private CallToolResult savePreference(McpSyncServerExchange exchange, JsonNode arguments) {
        Long userId = userId(exchange);
        requirePersonalizationEnabled(userId);
        memoryToolService.validateDeclaration(arguments, arguments.path("evidence").asText());
        String entity = arguments.path("entity").asText();
        String preference = arguments.path("preference").asText();
        String evidence = arguments.path("evidence").asText();
        if (!requestConfirmation(exchange, "确认将“" + entity + "”记录为“" + preference
                + "”偏好吗？依据是：“" + evidence + "”。")) {
            return cancelled("未获得用户确认，偏好事件未写入。");
        }
        AgentKitchenActionService.ActionResult result = memoryToolService.executeConfirmed(
                "MEMORY_PREFERENCE_DECLARATION", arguments, userId, arguments.path("idempotencyKey").asText());
        return success(result);
    }

    private CallToolResult updatePreference(McpSyncServerExchange exchange, JsonNode arguments) {
        Long userId = userId(exchange);
        requirePersonalizationEnabled(userId);
        var current = memoryToolService.validateUpdate(arguments, userId);
        String preference = arguments.path("preference").asText();
        if (!requestConfirmation(exchange, "确认将“" + current.entity() + "”的偏好从“"
                + current.preference() + "”修改为“" + preference + "”吗？")) {
            return cancelled("未获得用户确认，长期记忆未修改。");
        }
        AgentKitchenActionService.ActionResult result = memoryToolService.executeConfirmed(
                "MEMORY_PREFERENCE_UPDATE", arguments, userId, null);
        return success(result);
    }

    private CallToolResult recordFeedback(McpSyncServerExchange exchange, JsonNode arguments) {
        Long userId = userId(exchange);
        requirePersonalizationEnabled(userId);
        MemoryTargetFeedbackRequest request = objectMapper.convertValue(arguments, MemoryTargetFeedbackRequest.class);
        MemoryTargetFeedbackTargetResponse target = feedbackService.targets(userId, request.traceId()).stream()
                .filter(candidate -> candidate.sourceKind() == request.sourceKind()
                        && candidate.sourceId().equals(request.sourceId()))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "这条记忆并未用于本轮回答"));
        if (!requestConfirmation(exchange, "确认将你对“" + target.title() + "”的反馈记录为“"
                + request.feedbackType().name() + "”吗？")) {
            return cancelled("未获得用户确认，反馈未记录。");
        }
        return success(feedbackService.submit(userId, request));
    }

    private Long userId(McpSyncServerExchange exchange) {
        McpTransportContext context = exchange.transportContext();
        return MemoryMcpUserContext.requireUserId(context);
    }

    private void requirePersonalizationEnabled(Long userId) {
        if (!personalizationService.isEnabled(userId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "当前账号已关闭个性化，不能读写个人长期记忆");
        }
    }

    private boolean requestConfirmation(McpSyncServerExchange exchange, String prompt) {
        var capabilities = exchange.getClientCapabilities();
        if (capabilities == null || capabilities.elicitation() == null
                || capabilities.elicitation().form() == null && capabilities.elicitation().url() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "当前 MCP 客户端不支持用户确认弹窗；为保护记忆安全，本次写入未执行");
        }
        ElicitResult result = exchange.createElicitation(ElicitFormRequest.builder(prompt, Map.of(
                "type", "object",
                "properties", Map.of("confirmed", Map.of("type", "boolean", "description", "确认执行此操作")),
                "required", List.of("confirmed"),
                "additionalProperties", false
        )).build());
        return result.action() == ElicitResult.Action.ACCEPT
                && result.content() != null
                && Boolean.TRUE.equals(result.content().get("confirmed"));
    }

    private SyncToolSpecification tool(String name, String description, Map<String, Object> schema,
                                       BiFunction<McpSyncServerExchange, JsonNode, CallToolResult> handler) {
        return SyncToolSpecification.builder()
                .tool(McpSchema.Tool.builder(name, schema).description(description).build())
                .callHandler((exchange, request) -> {
                    JsonNode arguments = objectMapper.valueToTree(request.arguments() == null
                            ? Map.of() : request.arguments());
                    try {
                        return handler.apply(exchange, arguments);
                    } catch (ResponseStatusException | IllegalArgumentException exception) {
                        String reason = exception instanceof ResponseStatusException statusException
                                ? statusException.getReason() : exception.getMessage();
                        return failure(reason == null ? "参数无效" : reason);
                    }
                })
                .build();
    }

    private CallToolResult success(Object value) {
        return CallToolResult.builder()
                .content(List.of(McpSchema.TextContent.builder(toJson(value)).build()))
                .isError(false)
                .build();
    }

    private CallToolResult cancelled(String message) {
        return success(Map.of("confirmed", false, "message", message));
    }

    private CallToolResult failure(String message) {
        return CallToolResult.builder()
                .content(List.of(McpSchema.TextContent.builder(message).build()))
                .isError(true)
                .build();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("MCP 记忆工具结果序列化失败", exception);
        }
    }

    private static Map<String, Object> objectSchema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        schema.put("additionalProperties", false);
        return Map.copyOf(schema);
    }

    private static Map<String, Object> stringProperty(String description) {
        return Map.of("type", "string", "description", description, "maxLength", 500);
    }

    private static Map<String, Object> integerProperty(String description) {
        return Map.of("type", "integer", "description", description, "minimum", 1);
    }

    private static Map<String, Object> numberProperty(String description) {
        return Map.of("type", "number", "description", description, "minimum", 0, "maximum", 1);
    }

    private static Map<String, Object> enumProperty(String description, List<String> values) {
        return Map.of("type", "string", "description", description, "enum", values);
    }
}
