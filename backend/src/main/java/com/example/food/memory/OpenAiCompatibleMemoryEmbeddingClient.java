package com.example.food.memory;

import com.example.food.ai.config.AiModelConfigService;
import com.example.food.ai.config.AiModelRuntimeConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Uses the configured Qwen OpenAI-compatible endpoint; never logs request text or credentials. */
@Component
public class OpenAiCompatibleMemoryEmbeddingClient implements MemoryEmbeddingClient {
    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleMemoryEmbeddingClient.class);
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final AiModelConfigService modelConfigService;
    private final MemoryEmbeddingProperties properties;

    @org.springframework.beans.factory.annotation.Autowired
    public OpenAiCompatibleMemoryEmbeddingClient(RestTemplateBuilder builder, ObjectMapper objectMapper,
                                                 AiModelConfigService modelConfigService,
                                                 MemoryEmbeddingProperties properties) {
        this(builder.setConnectTimeout(Duration.ofSeconds(3)).setReadTimeout(Duration.ofSeconds(5)).build(),
                objectMapper, modelConfigService, properties);
    }

    OpenAiCompatibleMemoryEmbeddingClient(RestTemplate restTemplate, ObjectMapper objectMapper,
                                          AiModelConfigService modelConfigService,
                                          MemoryEmbeddingProperties properties) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.modelConfigService = modelConfigService;
        this.properties = properties;
    }

    @Override
    public boolean isAvailable() {
        if (!properties.enabled()) return false;
        AiModelRuntimeConfig config = modelConfigService.textRecipeRuntimeConfig();
        return "qwen".equalsIgnoreCase(config.provider())
                && "openai".equalsIgnoreCase(config.protocol())
                && hasText(config.apiKey())
                && hasText(config.endpoint())
                && hasText(properties.model());
    }

    @Override
    public String model() {
        return properties.model().trim();
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        if (texts == null || texts.isEmpty() || !isAvailable()) return List.of();
        List<String> bounded = texts.stream().map(this::boundText).toList();
        if (bounded.stream().anyMatch(String::isBlank)) return List.of();
        AiModelRuntimeConfig config = modelConfigService.textRecipeRuntimeConfig();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model());
        body.put("input", bounded.size() == 1 ? bounded.get(0) : bounded);
        body.put("dimensions", properties.dimensions());
        body.put("encoding_format", "float");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(config.apiKey().trim());
        try {
            JsonNode response = restTemplate.postForObject(embeddingEndpoint(config.endpoint()),
                    new HttpEntity<>(body, headers), JsonNode.class);
            JsonNode data = response == null ? null : response.path("data");
            if (data == null || !data.isArray() || data.size() != bounded.size()) return List.of();
            List<JsonNode> ordered = new ArrayList<>();
            data.forEach(ordered::add);
            ordered.sort(Comparator.comparingInt(node -> node.path("index").asInt(Integer.MAX_VALUE)));
            List<float[]> vectors = new ArrayList<>(ordered.size());
            for (JsonNode entry : ordered) {
                JsonNode embedding = entry.path("embedding");
                if (!embedding.isArray() || embedding.isEmpty()) return List.of();
                float[] vector = new float[embedding.size()];
                for (int i = 0; i < embedding.size(); i++) {
                    JsonNode component = embedding.get(i);
                    if (!component.isNumber()) return List.of();
                    vector[i] = (float) component.doubleValue();
                    if (!Float.isFinite(vector[i])) return List.of();
                }
                if (vector.length != properties.dimensions()) return List.of();
                vectors.add(vector);
            }
            return List.copyOf(vectors);
        } catch (RestClientException | IllegalArgumentException failure) {
            log.warn("Memory embedding request failed; lexical retrieval remains available ({})",
                    failure.getClass().getSimpleName());
            return List.of();
        }
    }

    static String embeddingEndpoint(String endpoint) {
        String base = endpoint.trim();
        for (String suffix : List.of("/chat/completions", "/embeddings")) {
            if (base.endsWith(suffix)) base = base.substring(0, base.length() - suffix.length());
        }
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base + "/embeddings";
    }

    private String boundText(String text) {
        if (text == null) return "";
        String normalized = text.trim();
        return normalized.length() > 8000 ? normalized.substring(0, 8000) : normalized;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
