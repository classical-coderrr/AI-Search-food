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

        List<ToolCall> toolCalls = new ArrayList<>();
        String content;
        boolean invalidStructuredCall = false;
        if (choices != null && choices.isArray() && !choices.isEmpty()) {
            JsonNode message = choices.get(0).path("message");
            content = message.path("content").isTextual() ? message.path("content").textValue().trim() : "";
            ParsedStructuredToolCalls parsedCalls = parseOpenAiToolCalls(message.path("tool_calls"), 0);
            toolCalls.addAll(parsedCalls.toolCalls());
            invalidStructuredCall = parsedCalls.invalid();
        } else if (root != null && root.path("output").isArray()) {
            // Qwen also exposes the Responses API shape when an endpoint is configured that way.
            content = outputText(root, root.path("output"));
            ParsedStructuredToolCalls parsedCalls = parseResponsesToolCalls(root.path("output"), 0);
            toolCalls.addAll(parsedCalls.toolCalls());
            invalidStructuredCall = parsedCalls.invalid();
        } else {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "千问 Agent 服务未返回有效结果");
        }
        ParsedTextToolCalls parsedTextToolCalls = parseTextToolCalls(content, toolCalls.size());
        content = parsedTextToolCalls.content();
        toolCalls.addAll(parsedTextToolCalls.toolCalls());
        if (content.isEmpty() && toolCalls.isEmpty()) {
            if (invalidStructuredCall) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "千问 Agent 返回了无效工具调用");
            }
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "千问 Agent 没有返回回答或工具调用");
        }
        return new AgentTurn(content, List.copyOf(toolCalls), runtimeConfig.provider(), runtimeConfig.modelName());
    }

    private ParsedStructuredToolCalls parseOpenAiToolCalls(JsonNode calls, int existingCallCount) {
        if (calls == null || calls.isMissingNode() || calls.isNull()) {
            return new ParsedStructuredToolCalls(List.of(), false);
        }
        List<ToolCall> toolCalls = new ArrayList<>();
        boolean invalid = false;
        int generatedId = existingCallCount;
        List<JsonNode> nodes = calls.isArray() ? toList(calls) : List.of(calls);
        for (JsonNode call : nodes) {
            JsonNode function = call.path("function").isObject() ? call.path("function") : call;
            String name = normalizeToolName(function.path("name").asText(""));
            if (name.isEmpty()) {
                invalid = true;
                continue;
            }
            String id = firstText(call, "id", "call_id");
            if (id.isEmpty()) {
                id = "qwen_tool_call_" + (++generatedId);
            }
            try {
                toolCalls.add(new ToolCall(id, name, normalizeToolArguments(
                        firstPresent(function, "arguments", "input", call.path("arguments"))
                )));
            } catch (IOException | IllegalArgumentException exception) {
                invalid = true;
            }
        }
        return new ParsedStructuredToolCalls(List.copyOf(toolCalls), invalid);
    }

    private ParsedStructuredToolCalls parseResponsesToolCalls(JsonNode output, int existingCallCount) {
        List<ToolCall> toolCalls = new ArrayList<>();
        boolean invalid = false;
        int generatedId = existingCallCount;
        for (JsonNode item : toList(output)) {
            String type = item.path("type").asText("");
            if (!("function_call".equals(type) || "tool_call".equals(type))) {
                continue;
            }
            String name = normalizeToolName(item.path("name").asText(""));
            if (name.isEmpty()) {
                invalid = true;
                continue;
            }
            String id = firstText(item, "call_id", "id");
            if (id.isEmpty()) {
                id = "qwen_tool_call_" + (++generatedId);
            }
            try {
                toolCalls.add(new ToolCall(id, name, normalizeToolArguments(
                        firstPresent(item, "arguments", "input", item.path("arguments"))
                )));
            } catch (IOException | IllegalArgumentException exception) {
                invalid = true;
            }
        }
        return new ParsedStructuredToolCalls(List.copyOf(toolCalls), invalid);
    }

    private String outputText(JsonNode root, JsonNode output) {
        String direct = root.path("output_text").asText("").trim();
        if (!direct.isEmpty()) {
            return direct;
        }
        StringBuilder content = new StringBuilder();
        for (JsonNode item : toList(output)) {
            if (!"message".equals(item.path("type").asText(""))) {
                continue;
            }
            JsonNode blocks = item.path("content");
            if (blocks.isTextual()) {
                content.append(blocks.asText());
            } else if (blocks.isArray()) {
                for (JsonNode block : blocks) {
                    if (block.path("text").isTextual()) {
                        content.append(block.path("text").asText());
                    }
                }
            }
        }
        return content.toString().trim();
    }

    private List<JsonNode> toList(JsonNode node) {
        List<JsonNode> nodes = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(nodes::add);
        } else if (node != null && !node.isMissingNode() && !node.isNull()) {
            nodes.add(node);
        }
        return nodes;
    }

    private String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = node.path(field).asText("").trim();
            if (!value.isEmpty()) {
                return value;
            }
        }
        return "";
    }

    private JsonNode firstPresent(JsonNode primary, String firstField, String secondField, JsonNode fallback) {
        JsonNode first = primary.path(firstField);
        if (!first.isMissingNode() && !first.isNull()) {
            return first;
        }
        JsonNode second = primary.path(secondField);
        if (!second.isMissingNode() && !second.isNull()) {
            return second;
        }
        return fallback;
    }

    private String normalizeToolArguments(JsonNode arguments) throws IOException {
        if (arguments == null || arguments.isMissingNode() || arguments.isNull()) {
            return "{}";
        }
        if (arguments.isTextual()) {
            String text = arguments.asText().trim();
            if (text.isEmpty()) {
                return "{}";
            }
            JsonNode parsed = objectMapper.readTree(text);
            if (parsed == null || !parsed.isObject()) {
                throw new IllegalArgumentException("工具参数不是 JSON 对象");
            }
            return objectMapper.writeValueAsString(parsed);
        }
        if (!arguments.isObject()) {
            throw new IllegalArgumentException("工具参数不是 JSON 对象");
        }
        return objectMapper.writeValueAsString(arguments);
    }

    private AgentTurn parseAnthropic(JsonNode root, AiModelRuntimeConfig runtimeConfig) {
        JsonNode blocks = root == null ? null : root.path("content");
        StringBuilder content = new StringBuilder();
        List<ToolCall> toolCalls = new ArrayList<>();
        boolean invalidStructuredCall = false;
        int generatedId = 0;
        if (blocks != null && blocks.isArray()) {
            for (JsonNode block : blocks) {
                String type = block.path("type").asText("");
                if ("text".equals(type)) {
                    String text = block.path("text").asText("");
                    if (!text.isBlank()) {
                        content.append(text);
                    }
                } else if ("tool_use".equals(type)) {
                    String name = normalizeToolName(block.path("name").asText(""));
                    if (name.isEmpty()) {
                        invalidStructuredCall = true;
                        continue;
                    }
                    String id = firstText(block, "id", "tool_use_id");
                    if (id.isEmpty()) {
                        id = "anthropic_tool_call_" + (++generatedId);
                    }
                    try {
                        toolCalls.add(new ToolCall(id, name, normalizeToolArguments(block.path("input"))));
                    } catch (IOException | IllegalArgumentException exception) {
                        invalidStructuredCall = true;
                    }
                }
            }
        } else if (blocks != null && blocks.isTextual()) {
            content.append(blocks.textValue());
        }
        ParsedTextToolCalls parsedTextToolCalls = parseTextToolCalls(content.toString(), toolCalls.size());
        if (parsedTextToolCalls.content().isEmpty() && toolCalls.isEmpty() && parsedTextToolCalls.toolCalls().isEmpty()) {
            if (invalidStructuredCall) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "千问 Agent 返回了无效工具调用");
            }
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
            ParsedTextToolCalls bareToolCall = parseBareJsonToolCall(content, existingCallCount);
            return bareToolCall == null ? new ParsedTextToolCalls(content, List.of()) : bareToolCall;
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
                String name = normalizeToolName(call.path("name").asText(""));
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
        String cleaned = cleanedContent.toString().trim();
        ParsedTextToolCalls bareToolCall = parseBareJsonToolCall(cleaned, generatedId);
        return bareToolCall == null
                ? new ParsedTextToolCalls(cleaned, List.copyOf(toolCalls))
                : new ParsedTextToolCalls(bareToolCall.content(), mergeToolCalls(toolCalls, bareToolCall.toolCalls()));
    }

    private ParsedTextToolCalls parseBareJsonToolCall(String content, int existingCallCount) {
        if (content == null || content.isBlank()) {
            return null;
        }
        String candidate = content.trim();
        if (candidate.startsWith("```") && candidate.endsWith("```")) {
            int firstLineBreak = candidate.indexOf('\n');
            if (firstLineBreak < 0) {
                return null;
            }
            candidate = candidate.substring(firstLineBreak + 1, candidate.length() - 3).trim();
        }
        if (!candidate.startsWith("{") || !candidate.endsWith("}")) {
            return null;
        }
        try {
            JsonNode call = objectMapper.readTree(candidate.replace("\\_", "_"));
            if (call == null || !call.isObject() || !call.has("name")) {
                return null;
            }
            String name = normalizeToolName(call.path("name").asText(""));
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
                id = "text_tool_call_" + (existingCallCount + 1);
            }
            return new ParsedTextToolCalls("", List.of(
                    new ToolCall(id, name, objectMapper.writeValueAsString(arguments))
            ));
        } catch (IOException | IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "模型返回了无效工具调用", exception);
        }
    }

    private List<ToolCall> mergeToolCalls(List<ToolCall> first, List<ToolCall> second) {
        List<ToolCall> merged = new ArrayList<>(first);
        merged.addAll(second);
        return List.copyOf(merged);
    }

    private String normalizeToolName(String name) {
        return name == null ? "" : name.replace("\\_", "_").trim();
    }

    public record AgentTurn(String content, List<ToolCall> toolCalls, String provider, String model) {
    }

    public record ToolCall(String id, String name, String arguments) {
    }

    private record ParsedTextToolCalls(String content, List<ToolCall> toolCalls) {
    }

    private record ParsedStructuredToolCalls(List<ToolCall> toolCalls, boolean invalid) {
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
