package com.example.food.ai.qwen;

import com.example.food.ai.config.AiModelConfigService;
import com.example.food.ai.config.AiModelRuntimeConfig;
import com.example.food.ai.config.dto.AiModelConnectionTestResponse;
import com.example.food.ai.recipe.dto.RecipeGenerateResponse;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Component
public class QwenRecipeClient {

    private static final Duration AI_READ_TIMEOUT = Duration.ofSeconds(180);

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final QwenProperties properties;
    private final AiModelConfigService aiModelConfigService;

    @Autowired
    public QwenRecipeClient(
            RestTemplateBuilder restTemplateBuilder,
            ObjectMapper objectMapper,
            QwenProperties properties,
            AiModelConfigService aiModelConfigService
    ) {
        this(
                restTemplateBuilder
                        .setConnectTimeout(Duration.ofSeconds(5))
                        .setReadTimeout(AI_READ_TIMEOUT)
                        .build(),
                objectMapper,
                properties,
                aiModelConfigService
        );
    }

    public QwenRecipeClient(RestTemplate restTemplate, ObjectMapper objectMapper, QwenProperties properties) {
        this(restTemplate, objectMapper, properties, null);
    }

    public QwenRecipeClient(
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

    public RecipeGenerateResponse generateRecipe(String prompt) {
        AiModelRuntimeConfig runtimeConfig = runtimeConfig();
        if (runtimeConfig.apiKey() == null || runtimeConfig.apiKey().isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "AI API Key 未配置，请先在管理后台设置");
        }

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    requestUrl(runtimeConfig),
                    HttpMethod.POST,
                    new HttpEntity<>(requestBody(prompt, runtimeConfig), headers(runtimeConfig)),
                    String.class
            );
            return parseRecipe(response.getBody(), runtimeConfig);
        } catch (RestClientException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI 服务调用失败，请稍后重试", exception);
        }
    }

    /**
     * Uses a short planning call before recipe generation. Qwen3 models can
     * spend their reasoning budget on choosing a standard dish and ingredient
     * pairing, while the final recipe call remains a fast non-thinking call.
     */
    public List<RecipePlan> planRecipeSelection(String prompt) {
        if (!properties.planningEnabled()) {
            return List.of();
        }
        AiModelRuntimeConfig runtimeConfig = runtimeConfig();
        requireApiKey(runtimeConfig);
        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    requestUrl(runtimeConfig),
                    HttpMethod.POST,
                    new HttpEntity<>(planningRequestBody(prompt, runtimeConfig), headers(runtimeConfig)),
                    String.class
            );
            return parseRecipePlans(response.getBody(), runtimeConfig);
        } catch (RestClientException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI 菜谱规划失败，请稍后重试", exception);
        }
    }

    public RecipeStreamResult streamRecipe(
            String prompt,
            Consumer<String> onDelta,
            Runnable onFirstChunk
    ) {
        AiModelRuntimeConfig runtimeConfig = runtimeConfig();
        if (runtimeConfig.apiKey() == null || runtimeConfig.apiKey().isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "AI API Key 未配置，请先在管理后台设置");
        }

        try {
            return restTemplate.execute(
                    requestUrl(runtimeConfig),
                    HttpMethod.POST,
                    request -> {
                        request.getHeaders().putAll(headers(runtimeConfig, true));
                        objectMapper.writeValue(request.getBody(), requestBody(prompt, runtimeConfig, true));
                    },
                    response -> readStreamResponse(response, runtimeConfig, onDelta, onFirstChunk)
            );
        } catch (org.springframework.web.client.RestClientResponseException exception) {
            if (isNativeStreamingUnsupported(exception.getStatusCode().value())) {
                return new RecipeStreamResult(
                        "",
                        generateRecipe(prompt),
                        runtimeConfig.provider(),
                        runtimeConfig.modelName(),
                        false
                );
            }
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI 流式服务调用失败，请稍后重试", exception);
        } catch (RestClientException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI 流式服务调用失败，请稍后重试", exception);
        }
    }

    public RecipeGenerateResponse parseRecipeContent(String content, AiModelRuntimeConfig runtimeConfig) {
        return parseRecipePayload(content, runtimeConfig);
    }

    public List<WeeklyMenuSelection> generateWeeklyMenu(String prompt) {
        AiModelRuntimeConfig runtimeConfig = runtimeConfig();
        if (runtimeConfig.apiKey() == null || runtimeConfig.apiKey().isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "AI API Key 未配置，请先在管理后台设置");
        }

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    requestUrl(runtimeConfig),
                    HttpMethod.POST,
                    new HttpEntity<>(requestBody(prompt, runtimeConfig), headers(runtimeConfig)),
                    String.class
            );
            return parseWeeklyMenu(response.getBody(), runtimeConfig);
        } catch (RestClientException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI 服务调用失败，请稍后重试", exception);
        }
    }

    public List<IndependentWeeklyMenuRecipe> generateIndependentWeeklyMenu(String prompt) {
        AiModelRuntimeConfig runtimeConfig = runtimeConfig();
        if (runtimeConfig.apiKey() == null || runtimeConfig.apiKey().isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "AI API Key 未配置，请先在管理后台设置");
        }

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    requestUrl(runtimeConfig),
                    HttpMethod.POST,
                    new HttpEntity<>(independentWeeklyMenuRequestBody(prompt, runtimeConfig), headers(runtimeConfig)),
                    String.class
            );
            return parseIndependentWeeklyMenu(response.getBody(), runtimeConfig);
        } catch (RestClientException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI 独立菜单生成失败，请稍后重试", exception);
        }
    }

    private AiModelRuntimeConfig runtimeConfig() {
        if (aiModelConfigService != null) {
            return aiModelConfigService.textRecipeRuntimeConfig();
        }
        return new AiModelRuntimeConfig("qwen", "openai", properties.model(), properties.endpoint(), properties.apiKey());
    }

    public AiModelRuntimeConfig currentRuntimeConfig() {
        return runtimeConfig();
    }

    public AiModelConnectionTestResponse testConnection(AiModelRuntimeConfig runtimeConfig) {
        requireApiKey(runtimeConfig);
        try {
            restTemplate.exchange(
                    requestUrl(runtimeConfig),
                    HttpMethod.POST,
                    new HttpEntity<>(connectionTestBody(runtimeConfig), headers(runtimeConfig)),
                    String.class
            );
            return new AiModelConnectionTestResponse(
                    true,
                    runtimeConfig.provider(),
                    runtimeConfig.protocol(),
                    runtimeConfig.modelName()
            );
        } catch (RestClientException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "连接测试失败，请检查接口协议、地址和 API Key", exception);
        }
    }

    private Map<String, Object> requestBody(String prompt, AiModelRuntimeConfig runtimeConfig) {
        return requestBody(prompt, runtimeConfig, false);
    }

    private Map<String, Object> requestBody(
            String prompt,
            AiModelRuntimeConfig runtimeConfig,
            boolean stream
    ) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", runtimeConfig.modelName());
        if (isAnthropic(runtimeConfig)) {
            body.put("max_tokens", 4096);
            body.put("system", "你是专业的中文营养与家庭烹饪助手。只输出可解析的 JSON，不要输出 Markdown、代码块或额外说明。");
            body.put("messages", List.of(Map.of("role", "user", "content", prompt)));
        } else {
            body.put("messages", List.of(
                    Map.of(
                            "role", "system",
                            "content", "你是专业的中文营养与家庭烹饪助手。只输出可解析的 JSON，不要输出 Markdown、代码块或额外说明。"
                    ),
                    Map.of(
                            "role", "user",
                            "content", prompt
                    )
            ));
            body.put("max_tokens", 2400);
            if (isFastQwen3Model(runtimeConfig.modelName())) {
                body.put("enable_thinking", false);
            }
        }
        body.put("temperature", 0.7);
        if (stream) {
            body.put("stream", true);
        }
        return body;
    }

    private HttpHeaders headers(AiModelRuntimeConfig runtimeConfig) {
        return headers(runtimeConfig, false);
    }

    private HttpHeaders headers(AiModelRuntimeConfig runtimeConfig, boolean stream) {
        HttpHeaders headers = new HttpHeaders();
        if (isAnthropic(runtimeConfig)) {
            headers.set("x-api-key", runtimeConfig.apiKey());
            headers.set("anthropic-version", "2023-06-01");
        } else {
            headers.setBearerAuth(runtimeConfig.apiKey());
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (stream) {
            headers.setAccept(List.of(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON));
        }
        return headers;
    }

    private RecipeGenerateResponse parseRecipe(String responseBody, AiModelRuntimeConfig runtimeConfig) {
        String content = firstContent(responseBody, runtimeConfig);
        return parseRecipePayload(content, runtimeConfig);
    }

    private List<RecipePlan> parseRecipePlans(String responseBody, AiModelRuntimeConfig runtimeConfig) {
        String content = firstContent(responseBody, runtimeConfig);
        try {
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(stripJsonFence(content));
            if (node == null || node.isNull()) {
                return List.of();
            }
            if (node.isArray()) {
                return List.copyOf(objectMapper.convertValue(node, objectMapper.getTypeFactory()
                        .constructCollectionType(List.class, RecipePlan.class)));
            }
            com.fasterxml.jackson.databind.JsonNode recipes = node.path("recipes");
            if (recipes.isArray()) {
                return List.copyOf(objectMapper.convertValue(recipes, objectMapper.getTypeFactory()
                        .constructCollectionType(List.class, RecipePlan.class)));
            }
            if (node.isObject() && node.has("title")) {
                return List.of(objectMapper.treeToValue(node, RecipePlan.class));
            }
            return List.of();
        } catch (IOException | IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI 菜谱规划结果不是有效 JSON", exception);
        }
    }

    private Map<String, Object> connectionTestBody(AiModelRuntimeConfig runtimeConfig) {
        return requestBody("请只回复 OK", runtimeConfig, false);
    }

    private Map<String, Object> planningRequestBody(String prompt, AiModelRuntimeConfig runtimeConfig) {
        Map<String, Object> body = requestBody(prompt, runtimeConfig, false);
        body.put("max_tokens", 1800);
        if (!isAnthropic(runtimeConfig) && isFastQwen3Model(runtimeConfig.modelName())) {
            body.put("enable_thinking", true);
            body.put("thinking_budget", 768);
        }
        return body;
    }

    private Map<String, Object> independentWeeklyMenuRequestBody(
            String prompt,
            AiModelRuntimeConfig runtimeConfig
    ) {
        Map<String, Object> body = requestBody(prompt, runtimeConfig, false);
        body.put("max_tokens", 7000);
        if (!isAnthropic(runtimeConfig) && isFastQwen3Model(runtimeConfig.modelName())) {
            body.put("enable_thinking", true);
            body.put("thinking_budget", 768);
        }
        return body;
    }

    private void requireApiKey(AiModelRuntimeConfig runtimeConfig) {
        if (runtimeConfig == null || runtimeConfig.apiKey() == null || runtimeConfig.apiKey().isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "AI API Key 未配置，请先填写或保存 API Key");
        }
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

    private boolean isFastQwen3Model(String modelName) {
        return modelName != null && modelName.trim().toLowerCase().contains("qwen3");
    }

    private RecipeGenerateResponse parseRecipePayload(String content, AiModelRuntimeConfig runtimeConfig) {
        RecipePayload payload = readPayload(content);
        return new RecipeGenerateResponse(
                payload.title(),
                payload.summary(),
                payload.effects(),
                payload.ingredients(),
                payload.missingIngredients(),
                payload.steps(),
                payload.tips(),
            payload.videoKeywords(),
            payload.explanation(),
                RecipeGenerateResponse.NutritionEstimate.from(payload.nutritionEstimate()),
                runtimeConfig.provider(),
                runtimeConfig.modelName()
        );
    }

    private RecipeStreamResult readStreamResponse(
            org.springframework.http.client.ClientHttpResponse response,
            AiModelRuntimeConfig runtimeConfig,
            Consumer<String> onDelta,
            Runnable onFirstChunk
    ) throws IOException {
        MediaType contentType = response.getHeaders().getContentType();
        boolean sse = contentType != null && contentType.isCompatibleWith(MediaType.TEXT_EVENT_STREAM);
        if (!sse) {
            byte[] body = response.getBody().readAllBytes();
            String text = new String(body, StandardCharsets.UTF_8).trim();
            sse = text.startsWith("data:") || text.startsWith(":");
            if (!sse) {
                return new RecipeStreamResult(
                        "",
                        parseRecipe(new String(body, StandardCharsets.UTF_8), runtimeConfig),
                        runtimeConfig.provider(),
                        runtimeConfig.modelName(),
                        false
                );
            }
            return readSse(text, runtimeConfig, onDelta, onFirstChunk);
        }
        return readSse(
                new BufferedReader(new InputStreamReader(response.getBody(), StandardCharsets.UTF_8)),
                runtimeConfig,
                onDelta,
                onFirstChunk
        );
    }

    private RecipeStreamResult readSse(
            String text,
            AiModelRuntimeConfig runtimeConfig,
            Consumer<String> onDelta,
            Runnable onFirstChunk
    ) throws IOException {
        return readSse(
                new BufferedReader(new java.io.StringReader(text)),
                runtimeConfig,
                onDelta,
                onFirstChunk
        );
    }

    private RecipeStreamResult readSse(
            BufferedReader reader,
            AiModelRuntimeConfig runtimeConfig,
            Consumer<String> onDelta,
            Runnable onFirstChunk
    ) throws IOException {
        StringBuilder content = new StringBuilder();
        List<String> dataLines = new ArrayList<>();
        boolean done = false;
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                done = processSseEvent(dataLines, content, onDelta, onFirstChunk) || done;
                dataLines.clear();
                continue;
            }
            if (line.startsWith(":")) {
                continue;
            }
            if (line.startsWith("data:")) {
                String data = line.substring(5);
                dataLines.add(data.startsWith(" ") ? data.substring(1) : data);
            }
        }
        done = processSseEvent(dataLines, content, onDelta, onFirstChunk) || done;
        if (!done) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI 流式响应中断，请点击重试");
        }
        return new RecipeStreamResult(
                content.toString(),
                null,
                runtimeConfig.provider(),
                runtimeConfig.modelName(),
                true
        );
    }

    private boolean processSseEvent(
            List<String> dataLines,
            StringBuilder content,
            Consumer<String> onDelta,
            Runnable onFirstChunk
    ) throws IOException {
        if (dataLines.isEmpty()) {
            return false;
        }
        String data = String.join("\n", dataLines).trim();
        if ("[DONE]".equals(data)) {
            return true;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(data);
            if ("message_stop".equals(root.path("type").asText())) {
                return true;
            }
            String delta = streamContent(root);
            if (delta != null && !delta.isEmpty()) {
                if (content.isEmpty()) {
                    onFirstChunk.run();
                }
                content.append(delta);
                onDelta.accept(delta);
            }
            return false;
        } catch (IOException | IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI 流式响应格式无效，请点击重试", exception);
        }
    }

    private String streamContent(com.fasterxml.jackson.databind.JsonNode root) {
        if (root == null || root.isNull()) {
            return null;
        }
        com.fasterxml.jackson.databind.JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            choices = root.path("output").path("choices");
        }
        if (!choices.isArray() || choices.isEmpty()) {
            com.fasterxml.jackson.databind.JsonNode delta = root.path("delta");
            String anthropicDelta = textContent(delta.path("text"));
            if (anthropicDelta != null) {
                return anthropicDelta;
            }
            return null;
        }
        com.fasterxml.jackson.databind.JsonNode choice = choices.get(0);
        String delta = textContent(choice.path("delta").path("content"));
        if (delta != null) {
            return delta;
        }
        return textContent(choice.path("message").path("content"));
    }

    private String textContent(com.fasterxml.jackson.databind.JsonNode node) {
        return node != null && node.isTextual() ? node.textValue() : null;
    }

    private boolean isNativeStreamingUnsupported(int status) {
        return status == 400 || status == 404 || status == 405 || status == 406
                || status == 415 || status == 422;
    }

    public record RecipeStreamResult(
            String content,
            RecipeGenerateResponse fallbackResponse,
            String provider,
            String model,
            boolean nativeStreaming
    ) {
    }

    private String firstContent(String responseBody, AiModelRuntimeConfig runtimeConfig) {
        try {
            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(responseBody);
            String content = responseContent(root, runtimeConfig);
            if (content == null || content.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI 服务未返回菜谱内容");
            }
            return content;
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI 返回内容不是有效 JSON", exception);
        }
    }

    private RecipePayload readPayload(String content) {
        try {
            return objectMapper.treeToValue(objectMapper.readTree(stripJsonFence(content)), RecipePayload.class);
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI 返回内容不是有效菜谱 JSON", exception);
        }
    }

    private List<WeeklyMenuSelection> parseWeeklyMenu(String responseBody, AiModelRuntimeConfig runtimeConfig) {
        String content = firstContent(responseBody, runtimeConfig);
        try {
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(stripJsonFence(content));
            if (node == null || node.isNull() || (!node.isArray() && !node.isObject())) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI 返回的周菜单不是有效 JSON");
            }
            if (node.isArray()) {
                return objectMapper.convertValue(node, objectMapper.getTypeFactory()
                        .constructCollectionType(List.class, WeeklyMenuSelection.class));
            }
            WeeklyMenuPayload payload = objectMapper.treeToValue(node, WeeklyMenuPayload.class);
            return payload.items() == null ? List.of() : List.copyOf(payload.items());
        } catch (IOException | IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI 返回的周菜单不是有效 JSON", exception);
        }
    }

    private List<IndependentWeeklyMenuRecipe> parseIndependentWeeklyMenu(
            String responseBody,
            AiModelRuntimeConfig runtimeConfig
    ) {
        String content = firstContent(responseBody, runtimeConfig);
        try {
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(stripJsonFence(content));
            if (node == null || node.isNull() || (!node.isArray() && !node.isObject())) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI 返回的独立菜单不是有效 JSON");
            }
            if (node.isArray()) {
                return objectMapper.convertValue(node, objectMapper.getTypeFactory()
                        .constructCollectionType(List.class, IndependentWeeklyMenuRecipe.class));
            }
            com.fasterxml.jackson.databind.JsonNode items = node.path("items");
            if (!items.isArray()) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI 返回的独立菜单缺少 items");
            }
            return objectMapper.convertValue(items, objectMapper.getTypeFactory()
                    .constructCollectionType(List.class, IndependentWeeklyMenuRecipe.class));
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (IOException | IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI 返回的独立菜单不是有效 JSON", exception);
        }
    }

    private String responseContent(com.fasterxml.jackson.databind.JsonNode root,
                                   AiModelRuntimeConfig runtimeConfig) {
        if (root == null || root.isNull()) {
            return null;
        }
        if (isAnthropic(runtimeConfig)) {
            return contentText(root.path("content"));
        }
        com.fasterxml.jackson.databind.JsonNode choices = root.path("choices");
        if (choices.isArray() && !choices.isEmpty()) {
            String content = contentText(choices.get(0).path("message").path("content"));
            if (content != null) {
                return content;
            }
            return contentText(choices.get(0).path("text"));
        }
        return contentText(root.path("output").path("choices").path(0).path("message").path("content"));
    }

    private String contentText(com.fasterxml.jackson.databind.JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return node.textValue();
        }
        if (node.isArray()) {
            StringBuilder text = new StringBuilder();
            for (com.fasterxml.jackson.databind.JsonNode item : node) {
                String value = textContent(item.path("text"));
                if (value != null) {
                    text.append(value);
                }
            }
            return text.isEmpty() ? null : text.toString();
        }
        return null;
    }

    private String stripJsonFence(String content) {
        String trimmed = content.trim();
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.replaceFirst("^```(?:json)?\\s*", "");
            trimmed = trimmed.replaceFirst("\\s*```$", "");
        }
        return trimmed.trim();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RecipePayload(
            String title,
            String summary,
            List<String> effects,
            List<RecipeGenerateResponse.Ingredient> ingredients,
            List<RecipeGenerateResponse.MissingIngredient> missingIngredients,
            List<RecipeGenerateResponse.Step> steps,
            List<String> tips,
            List<String> videoKeywords,
            RecipeGenerateResponse.Explanation explanation,
            com.fasterxml.jackson.databind.JsonNode nutritionEstimate
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RecipePlan(
            String title,
            List<String> coreIngredients,
            List<String> videoSearchKeywords
    ) {
        public RecipePlan {
            coreIngredients = coreIngredients == null ? List.of() : List.copyOf(coreIngredients);
            videoSearchKeywords = videoSearchKeywords == null ? List.of() : List.copyOf(videoSearchKeywords);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record WeeklyMenuPayload(List<WeeklyMenuSelection> items) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record IndependentWeeklyMenuRecipe(
            String menuDate,
            String mealType,
            String title,
            String summary,
            List<RecipeGenerateResponse.Ingredient> ingredients,
            List<RecipeGenerateResponse.Step> steps,
            List<String> tips,
            List<String> videoKeywords,
            RecipeGenerateResponse.NutritionEstimate nutritionEstimate
    ) {
        public IndependentWeeklyMenuRecipe {
            ingredients = ingredients == null ? List.of() : List.copyOf(ingredients);
            steps = steps == null ? List.of() : List.copyOf(steps);
            tips = tips == null ? List.of() : List.copyOf(tips);
            videoKeywords = videoKeywords == null ? List.of() : List.copyOf(videoKeywords);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record WeeklyMenuSelection(
            String menuDate,
            String mealType,
            Long recipeId
    ) {
    }
}
