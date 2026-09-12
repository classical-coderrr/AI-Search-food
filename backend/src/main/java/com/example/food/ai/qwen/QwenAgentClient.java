package com.example.food.ai.qwen;

import com.example.food.ai.config.AiModelConfigService;
import com.example.food.ai.config.AiModelRuntimeConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class QwenAgentClient {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy年M月d日 EEEE HH:mm:ss z", Locale.CHINA);
    private static final Pattern TEXT_TOOL_CALL_PATTERN = Pattern.compile("(?s)<tool_call>\\s*(.*?)\\s*</tool_call>");
    private static final String SYSTEM_PROMPT = """
            你是“小厨灵”，一个中文家庭厨房智能助手。
            你的职责是理解用户目标，自主选择已提供的工具，并根据真实工具结果回答。

            当前服务器时间：%s。

            必须遵守：
            1. 查询库存、临期、提醒、周菜单、收藏或营养信息时必须调用对应工具，不得编造。
            2. 用户要求根据食材生成、推荐或调整菜谱时，调用 recipe_generate。
            3. 用户要求保存最近生成的菜谱时，调用 recipe_save；该工具只发起用户确认，不会直接写入。
            4. 只能调用 tools 中声明的函数，工具参数必须符合 JSON Schema。
            5. 工具返回错误时如实说明，不要假装执行成功。
            6. 回答简洁、友好，使用中文；营养内容仅作一般饮食参考，不作医疗判断。
            7. 普通知识、闲聊、日期时间等不需要厨房数据的问题直接回答，不要强行调用工具。
            8. 任何会修改用户数据的工具只会创建确认请求；必须等待用户在确认卡片中确认后才能执行。
            """;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final QwenProperties properties;
    private final AiModelConfigService aiModelConfigService;

    @Autowired
    public QwenAgentClient(
            RestTemplateBuilder restTemplateBuilder,
            ObjectMapper objectMapper,
            QwenProperties properties,
            AiModelConfigService aiModelConfigService
    ) {
        this(
                restTemplateBuilder
                        .setConnectTimeout(Duration.ofSeconds(5))
                        .setReadTimeout(Duration.ofSeconds(120))
                        .build(),
                objectMapper,
                properties,
                aiModelConfigService
        );
    }

    public QwenAgentClient(RestTemplate restTemplate, ObjectMapper objectMapper, QwenProperties properties) {
        this(restTemplate, objectMapper, properties, null);
    }

    public QwenAgentClient(
            RestTemplate restTemplate,
            ObjectMapper objectMapper,
            QwenProperties properties,
            AiModelConfigService aiModelConfigService
    ) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.aiModelConfigService = aiModelConfigService;
    }

    public AgentTurn complete(List<ConversationMessage> conversation, List<Map<String, Object>> tools) {
        AiModelRuntimeConfig runtimeConfig = runtimeConfig();
        if (runtimeConfig.apiKey() == null || runtimeConfig.apiKey().isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "千问 API Key 未配置，请设置 DASHSCOPE_API_KEY");
        }

        try {
            ResponseEntity<JsonNode> response = restTemplate.exchange(
                    requestUrl(runtimeConfig),
                    HttpMethod.POST,
                    new HttpEntity<>(requestBody(conversation, tools, runtimeConfig), headers(runtimeConfig)),
                    JsonNode.class
            );
            return parse(response.getBody(), runtimeConfig);
        } catch (RestClientException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "千问 Agent 服务调用失败，请稍后重试", exception);
        }
    }

    private AiModelRuntimeConfig runtimeConfig() {
        if (aiModelConfigService != null) {
            return aiModelConfigService.textRecipeRuntimeConfig();
        }
        return new AiModelRuntimeConfig("qwen", properties.model(), properties.endpoint(), properties.apiKey());
    }

    private Map<String, Object> requestBody(
            List<ConversationMessage> conversation,
            List<Map<String, Object>> tools,
            AiModelRuntimeConfig runtimeConfig
    ) {
        if (isAnthropic(runtimeConfig)) {
            return anthropicRequestBody(conversation, tools, runtimeConfig);
        }

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt()));
        if (conversation != null) {
            conversation.forEach(message -> messages.add(message.toPayload()));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", runtimeConfig.modelName());
        body.put("messages", messages);
        if (tools != null && !tools.isEmpty()) {
            body.put("tools", tools);
            body.put("tool_choice", "auto");
            body.put("parallel_tool_calls", false);
        }
        body.put("temperature", 0.2);
        return body;
    }

    private Map<String, Object> anthropicRequestBody(
            List<ConversationMessage> conversation,
            List<Map<String, Object>> tools,
            AiModelRuntimeConfig runtimeConfig
    ) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", runtimeConfig.modelName());
        body.put("max_tokens", 4096);
        body.put("system", systemPrompt());
        body.put("messages", anthropicMessages(conversation));
        if (tools != null && !tools.isEmpty()) {
            body.put("tools", tools.stream().map(this::anthropicTool).toList());
        }
        body.put("temperature", 0.2);
        return body;
    }

    private List<Map<String, Object>> anthropicMessages(List<ConversationMessage> conversation) {
        if (conversation == null || conversation.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> messages = new ArrayList<>();
        for (ConversationMessage message : conversation) {
            if ("tool".equals(message.role())) {
                messages.add(Map.of(
                        "role", "user",
                        "content", List.of(Map.of(
                                "type", "tool_result",
                                "tool_use_id", message.toolCallId(),
                                "content", message.content() == null ? "" : message.content()
                        ))
                ));
            } else if ("assistant".equals(message.role())) {
                messages.add(Map.of(
                        "role", "assistant",
                        "content", anthropicAssistantContent(message)
                ));
            } else {
                messages.add(Map.of(
                        "role", "user",
                        "content", message.content() == null ? "" : message.content()
                ));
            }
        }
        return messages;
    }

    private Object anthropicAssistantContent(ConversationMessage message) {
        if (message.toolCalls() == null || message.toolCalls().isEmpty()) {
            return message.content() == null ? "" : message.content();
        }
        List<Map<String, Object>> content = new ArrayList<>();
        if (message.content() != null && !message.content().isBlank()) {
            content.add(Map.of("type", "text", "text", message.content()));
        }
        for (ToolCall call : message.toolCalls()) {
            content.add(Map.of(
                    "type", "tool_use",
                    "id", call.id(),
                    "name", call.name(),
                    "input", toolInput(call.arguments())
            ));
        }
        return content;
    }

    private Map<String, Object> anthropicTool(Map<String, Object> tool) {
        Object functionObject = tool == null ? null : tool.get("function");
        if (!(functionObject instanceof Map<?, ?> function)) {
            return tool == null ? Map.of() : tool;
        }
        Map<String, Object> converted = new LinkedHashMap<>();
        converted.put("name", function.get("name"));
        converted.put("description", function.get("description"));
        converted.put("input_schema", function.get("parameters"));
        return converted;
    }

    private JsonNode toolInput(String arguments) {
        try {
            JsonNode input = objectMapper.readTree(arguments == null || arguments.isBlank() ? "{}" : arguments);
            if (input == null || !input.isObject()) {
                throw new IllegalArgumentException("工具参数不是 JSON 对象");
            }
            return input;
        } catch (IOException | IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "千问 Agent 返回了无效工具参数", exception);
        }
    }

    private String systemPrompt() {
        return SYSTEM_PROMPT.formatted(ZonedDateTime.now(BUSINESS_ZONE).format(TIME_FORMATTER));
    }

    private HttpHeaders headers(AiModelRuntimeConfig runtimeConfig) {
        HttpHeaders headers = new HttpHeaders();
        if (isAnthropic(runtimeConfig)) {
            headers.set("x-api-key", runtimeConfig.apiKey());
            headers.set("anthropic-version", "2023-06-01");
        } else {
            headers.setBearerAuth(runtimeConfig.apiKey());
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        return headers;
    }

    private String requestUrl(AiModelRuntimeConfig runtimeConfig) {
        String endpoint = runtimeConfig.endpoint() == null ? "" : runtimeConfig.endpoint().trim();
        if (isAnthropic(runtimeConfig) || endpoint.endsWith("/chat/completions")) {
            return endpoint;
        }
        return endpoint.endsWith("/") ? endpoint + "chat/completions" : endpoint + "/chat/completions";
    }

    private boolean isAnthropic(AiModelRuntimeConfig runtimeConfig) {
        return "anthropic".equalsIgnoreCase(runtimeConfig.protocol());
    }

    private AgentTurn parse(JsonNode root, AiModelRuntimeConfig runtimeConfig) {
        if (isAnthropic(runtimeConfig)) {
            return parseAnthropic(root, runtimeConfig);
        }

        JsonNode choices = root == null ? null : root.path("choices");
        if (choices == null || !choices.isArray() || choices.isEmpty()) {
            choices = root == null ? null : root.path("output").path("choices");
        }
        if (choices == null || !choices.isArray() || choices.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "千问 Agent 服务未返回有效结果");
        }

        JsonNode message = choices.get(0).path("message");
        String content = message.path("content").isTextual() ? message.path("content").textValue().trim() : "";
        List<ToolCall> toolCalls = new ArrayList<>();
        JsonNode calls = message.path("tool_calls");
        if (calls.isArray()) {
            for (JsonNode call : calls) {
                JsonNode function = call.path("function");
                String id = call.path("id").asText("").trim();
                String name = function.path("name").asText("").trim();
                String arguments = function.path("arguments").asText("{}");
                if (id.isEmpty() || name.isEmpty()) {
                    throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "千问 Agent 返回了无效工具调用");
                }
                toolCalls.add(new ToolCall(id, name, arguments));
            }
        }
        ParsedTextToolCalls parsedTextToolCalls = parseTextToolCalls(content, toolCalls.size());
        content = parsedTextToolCalls.content();
        toolCalls.addAll(parsedTextToolCalls.toolCalls());
        if (content.isEmpty() && toolCalls.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "千问 Agent 没有返回回答或工具调用");
        }
        return new AgentTurn(content, List.copyOf(toolCalls), runtimeConfig.provider(), runtimeConfig.modelName());
    }

    private AgentTurn parseAnthropic(JsonNode root, AiModelRuntimeConfig runtimeConfig) {
        JsonNode blocks = root == null ? null : root.path("content");
        StringBuilder content = new StringBuilder();
        List<ToolCall> toolCalls = new ArrayList<>();
        if (blocks != null && blocks.isArray()) {
            for (JsonNode block : blocks) {
                String type = block.path("type").asText("");
                if ("text".equals(type)) {
                    String text = block.path("text").asText("");
                    if (!text.isBlank()) {
                        content.append(text);
                    }
                } else if ("tool_use".equals(type)) {
                    String id = block.path("id").asText("").trim();
                    String name = block.path("name").asText("").trim();
                    if (id.isEmpty() || name.isEmpty()) {
                        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "千问 Agent 返回了无效工具调用");
                    }
                    try {
                        toolCalls.add(new ToolCall(
                                id,
                                name,
                                objectMapper.writeValueAsString(block.path("input").isMissingNode()
                                        ? objectMapper.createObjectNode()
                                        : block.path("input"))
                        ));
                    } catch (IOException exception) {
                        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "千问 Agent 返回了无效工具参数", exception);
                    }
                }
            }
        } else if (blocks != null && blocks.isTextual()) {
            content.append(blocks.textValue());
        }
        ParsedTextToolCalls parsedTextToolCalls = parseTextToolCalls(content.toString(), toolCalls.size());
        if (parsedTextToolCalls.content().isEmpty() && toolCalls.isEmpty() && parsedTextToolCalls.toolCalls().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "千问 Agent 没有返回回答或工具调用");
        }
        toolCalls.addAll(parsedTextToolCalls.toolCalls());
        return new AgentTurn(parsedTextToolCalls.content().trim(), List.copyOf(toolCalls), runtimeConfig.provider(), runtimeConfig.modelName());
    }

    private ParsedTextToolCalls parseTextToolCalls(String content, int existingCallCount) {
        if (content == null || content.isBlank()) {
            return new ParsedTextToolCalls(content == null ? "" : content, List.of());
        }

        Matcher matcher = TEXT_TOOL_CALL_PATTERN.matcher(content);
        if (!matcher.find()) {
            return new ParsedTextToolCalls(content, List.of());
        }

        StringBuilder cleanedContent = new StringBuilder();
        List<ToolCall> toolCalls = new ArrayList<>();
        int cursor = 0;
        int generatedId = existingCallCount;
        do {
            cleanedContent.append(content, cursor, matcher.start());
            try {
                JsonNode call = objectMapper.readTree(matcher.group(1).trim());
                if (call == null || !call.isObject()) {
                    throw new IllegalArgumentException("工具调用不是 JSON 对象");
                }
                String name = call.path("name").asText("").trim();
                if (name.isEmpty()) {
                    throw new IllegalArgumentException("工具名称为空");
                }
                JsonNode arguments = call.path("arguments");
                if (arguments.isMissingNode() || arguments.isNull()) {
                    arguments = objectMapper.createObjectNode();
                } else if (arguments.isTextual()) {
                    arguments = objectMapper.readTree(arguments.textValue());
                }
                if (arguments == null || !arguments.isObject()) {
                    throw new IllegalArgumentException("工具参数不是 JSON 对象");
                }
                String id = call.path("id").asText("").trim();
                if (id.isEmpty()) {
                    id = "text_tool_call_" + (++generatedId);
                }
                toolCalls.add(new ToolCall(id, name, objectMapper.writeValueAsString(arguments)));
            } catch (IOException | IllegalArgumentException exception) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "千问 Agent 返回了无效工具调用", exception);
            }
            cursor = matcher.end();
        } while (matcher.find());
        cleanedContent.append(content, cursor, content.length());
        return new ParsedTextToolCalls(cleanedContent.toString().trim(), List.copyOf(toolCalls));
    }

    public record AgentTurn(String content, List<ToolCall> toolCalls, String provider, String model) {
    }

    public record ToolCall(String id, String name, String arguments) {
    }

    private record ParsedTextToolCalls(String content, List<ToolCall> toolCalls) {
    }

    public record ConversationMessage(
            String role,
            String content,
            List<ToolCall> toolCalls,
            String toolCallId
    ) {
        public static ConversationMessage user(String content) {
            return new ConversationMessage("user", content, List.of(), null);
        }

        public static ConversationMessage assistant(String content) {
            return new ConversationMessage("assistant", content, List.of(), null);
        }

        public static ConversationMessage assistant(AgentTurn turn) {
            return new ConversationMessage("assistant", turn.content(), turn.toolCalls(), null);
        }

        public static ConversationMessage tool(String toolCallId, String content) {
            return new ConversationMessage("tool", content, List.of(), toolCallId);
        }

        private Map<String, Object> toPayload() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("role", role);
            if ("tool".equals(role)) {
                payload.put("tool_call_id", toolCallId);
                payload.put("content", content == null ? "" : content);
                return payload;
            }
            payload.put("content", content == null ? "" : content);
            if (toolCalls != null && !toolCalls.isEmpty()) {
                payload.put("tool_calls", toolCalls.stream().map(call -> Map.of(
                        "id", call.id(),
                        "type", "function",
                        "function", Map.of(
                                "name", call.name(),
                                "arguments", call.arguments()
                        )
                )).toList());
            }
            return payload;
        }
    }
}
