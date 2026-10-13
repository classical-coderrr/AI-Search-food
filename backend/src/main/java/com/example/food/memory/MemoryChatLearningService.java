package com.example.food.memory;

import com.example.food.agent.AgentMessageMapper;
import com.example.food.ai.qwen.QwenRecipeClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class MemoryChatLearningService {
    public static final String EPISODE_TYPE = "USER_CHAT";
    private final MemoryBehaviorEpisodeRecorder recorder;
    private final MemoryEpisodeMapper episodeMapper;
    private final AgentMessageMapper messageMapper;
    private final MemoryPersonalizationService personalization;
    private final MemoryLearningGuard guard;
    private final QwenRecipeClient client;
    private final ObjectMapper mapper;
    private final TransactionTemplate transaction;

    public MemoryChatLearningService(MemoryBehaviorEpisodeRecorder recorder, MemoryEpisodeMapper episodeMapper,
                                     AgentMessageMapper messageMapper, MemoryPersonalizationService personalization,
                                     MemoryLearningGuard guard, QwenRecipeClient client, ObjectMapper mapper,
                                     PlatformTransactionManager transactionManager) {
        this.recorder = recorder;
        this.episodeMapper = episodeMapper;
        this.messageMapper = messageMapper;
        this.personalization = personalization;
        this.guard = guard;
        this.client = client;
        this.mapper = mapper;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public void record(Long userId, Long conversationId, Long messageId, String rawMessage) {
        if (messageId == null || !StringUtils.hasText(rawMessage)) return;
        MemoryPersonalizationState state = personalization.getState(userId);
        if (!state.enabled()) return;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("message", rawMessage.trim());
        payload.put("personalizationVersion", state.version());
        payload.put("context", messageMapper.findRecentTextMessages(userId, conversationId, 8).stream()
                .filter(message -> message.getId() != null && message.getId() < messageId)
                .map(message -> Map.of("role", message.getRole(), "content", bounded(message.getContent(), 1000)))
                .toList());
        recorder.record(new MemoryBehaviorEpisodeEvent(userId, null, conversationId, EPISODE_TYPE,
                "AGENT_USER_MESSAGE", String.valueOf(messageId), "chat:" + messageId, "chat:" + messageId,
                "用户聊天：" + bounded(rawMessage, 150), payload, LocalDateTime.now(), new BigDecimal("0.2500")));
    }

    /** The remote call happens before the short guarded persistence transaction. */
    public void prepare(Long userId, Long episodeId) {
        MemoryEpisode episode = episodeMapper.findOwned(userId, episodeId);
        if (episode == null || !EPISODE_TYPE.equals(episode.getEpisodeType())) return;
        ObjectNode payload = readPayload(episode);
        if (payload.has("chatFacts") || !isCurrent(userId, payload)) return;
        String rawMessage = payload.path("message").asText("");
        List<Map<String, Object>> facts = validate(rawMessage, client.extractChatMemory(payload.toString()));
        transaction.executeWithoutResult(status -> {
            guard.lock(userId);
            MemoryEpisode current = episodeMapper.findOwned(userId, episodeId);
            if (current == null || !isCurrent(userId, payload)) return;
            ObjectNode currentPayload = readPayload(current);
            if (currentPayload.has("chatFacts")) return;
            currentPayload.set("chatFacts", mapper.valueToTree(facts));
            episodeMapper.updateChatPayload(userId, episodeId, currentPayload.toString());
        });
    }

    public boolean isCurrent(Long userId, JsonNode payload) {
        MemoryPersonalizationState state = personalization.getState(userId);
        return state.enabled() && payload.path("personalizationVersion").asInt(-1) == state.version();
    }

    public List<Map<String, Object>> validate(String rawMessage, String result) {
        try {
            JsonNode root = mapper.readTree(result);
            if (root == null || !root.isObject() || !root.path("facts").isArray() || root.path("facts").size() > 5) {
                throw new IllegalArgumentException("聊天记忆提取结构无效");
            }
            List<Map<String, Object>> facts = new ArrayList<>();
            for (JsonNode fact : root.path("facts")) {
                String kind = text(fact, "kind");
                String type = text(fact, "candidateType");
                String entity = text(fact, "entity");
                String preference = text(fact, "preference");
                String quote = text(fact, "evidenceText");
                boolean request = "FOOD_REQUEST".equals(kind);
                if (!Set.of("PREFERENCE", "FOOD_REQUEST").contains(kind)
                        || !"USER".equals(text(fact, "scope")) || entity.isBlank() || entity.length() > 128
                        || quote.isBlank() || quote.length() > 512 || !rawMessage.contains(quote)
                        || !fact.path("correction").isBoolean()) continue;
                if (request) {
                    if (!"RECIPE_PREFERENCE".equals(type) || !"WANT".equals(preference)
                            || !"SESSION".equals(text(fact, "temporalType"))) continue;
                } else {
                    if (!Set.of("INGREDIENT_PREFERENCE", "RECIPE_PREFERENCE", "SKILL_PREFERENCE").contains(type)
                            || !"LONG_TERM".equals(text(fact, "temporalType"))) continue;
                    if ("SKILL_PREFERENCE".equals(type)) {
                        preference = SkillPreferenceCatalog.normalizeValue(entity, preference);
                        if (preference == null) continue;
                    } else if (!Set.of("LIKE", "DISLIKE").contains(preference)) continue;
                }
                Map<String, Object> accepted = new LinkedHashMap<>();
                accepted.put("kind", kind);
                accepted.put("candidateType", type);
                accepted.put("entity", entity);
                accepted.put("preference", preference);
                accepted.put("scope", "USER");
                accepted.put("temporalType", request ? "SESSION" : "LONG_TERM");
                accepted.put("evidenceText", quote);
                accepted.put("correction", fact.path("correction").asBoolean());
                List<String> ingredients = new ArrayList<>();
                if (request && fact.path("ingredients").isArray()) {
                    for (JsonNode ingredient : fact.path("ingredients")) {
                        if (ingredient.isTextual() && !ingredient.asText().isBlank()
                                && quote.contains(ingredient.asText()) && ingredient.asText().length() <= 48) {
                            ingredients.add(ingredient.asText());
                        }
                    }
                }
                accepted.put("ingredients", ingredients.stream().distinct().limit(2).toList());
                facts.add(accepted);
            }
            return List.copyOf(facts);
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalArgumentException("聊天记忆提取结果不是有效 JSON", failure);
        }
    }

    private ObjectNode readPayload(MemoryEpisode episode) {
        try {
            return (ObjectNode) mapper.readTree(episode.getPayloadJson());
        } catch (Exception failure) {
            throw new IllegalArgumentException("聊天记忆原始数据无效", failure);
        }
    }

    private String text(JsonNode node, String field) {
        return node.path(field).isTextual() ? node.path(field).asText().trim() : "";
    }

    private static String bounded(String value, int limit) {
        return value == null ? "" : value.substring(0, Math.min(value.length(), limit));
    }
}
