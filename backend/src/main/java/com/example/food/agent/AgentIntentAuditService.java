package com.example.food.agent;

import com.example.food.agent.state.AgentNode;
import com.example.food.agent.state.AgentRunStore;
import com.example.food.agent.state.AgentStep;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Persists the intent-routing decision as an auditable Agent step.
 *
 * <p>The audit deliberately stores routing metadata rather than the complete
 * user message. This keeps the step useful for diagnosing tool exposure while
 * avoiding another copy of potentially sensitive conversation content.</p>
 */
@Component
public class AgentIntentAuditService {

    public static final String ACTION = "intent.resolved";
    public static final String SOURCE_RULE = "RULE";
    public static final String SOURCE_MODEL = "MODEL";
    public static final String SOURCE_MODEL_FALLBACK = "MODEL_FALLBACK";
    public static final String SOURCE_RULE_GATE = "RULE_GATE";
    public static final String SOURCE_CONFIG = "CONFIG";

    private final AgentRunStore runStore;
    private final ObjectMapper objectMapper;

    public AgentIntentAuditService(AgentRunStore runStore, ObjectMapper objectMapper) {
        this.runStore = runStore;
        this.objectMapper = objectMapper;
    }

    public void record(
            String runId,
            long stepNo,
            String source,
            String intent,
            double confidence,
            String recipeReference,
            String reason,
            boolean saveToolExposed,
            boolean modelCalled,
            int messageLength,
            boolean hasAttachment
    ) {
        String normalizedSource = normalize(source, SOURCE_RULE_GATE);
        String normalizedIntent = normalize(intent, "OTHER");
        String normalizedReference = normalize(recipeReference, "NONE");
        double normalizedConfidence = Math.max(0d, Math.min(1d, confidence));

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("source", normalizedSource);
        request.put("modelCalled", modelCalled);
        request.put("messageLength", Math.max(0, messageLength));
        request.put("hasAttachment", hasAttachment);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("intent", normalizedIntent);
        response.put("confidence", normalizedConfidence);
        response.put("recipeReference", normalizedReference);
        response.put("source", normalizedSource);
        response.put("route", saveToolExposed ? "SAVE_TOOL_EXPOSED" : "NORMAL_DIALOGUE");
        if (reason != null && !reason.isBlank()) {
            response.put("reason", reason.trim());
        }

        Instant now = Instant.now();
        runStore.appendStep(new AgentStep(
                runId,
                stepNo,
                AgentNode.MODEL_DECISION,
                ACTION,
                toolName(normalizedSource),
                status(normalizedSource),
                json(request),
                json(response),
                runId + ":intent-resolution",
                now,
                now,
                null
        ));
    }

    private String toolName(String source) {
        return switch (source) {
            case SOURCE_RULE -> "agent_intent_rule";
            case SOURCE_MODEL, SOURCE_MODEL_FALLBACK -> "agent_intent_classify";
            default -> "agent_intent_router";
        };
    }

    private String status(String source) {
        return switch (source) {
            case SOURCE_RULE, SOURCE_MODEL -> "SUCCESS";
            case SOURCE_MODEL_FALLBACK -> "FALLBACK";
            default -> "SKIPPED";
        };
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Agent 意图审计序列化失败", exception);
        }
    }

    private String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim().toUpperCase(Locale.ROOT);
    }
}
