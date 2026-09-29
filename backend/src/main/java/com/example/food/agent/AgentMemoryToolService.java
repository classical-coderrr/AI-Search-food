package com.example.food.agent;

import com.example.food.agent.AgentToolRegistry.Tool;
import com.example.food.memory.MemoryEpisode;
import com.example.food.memory.MemoryEpisodeCommand;
import com.example.food.memory.MemoryEpisodeService;
import com.example.food.memory.MemoryItemUpdateRequest;
import com.example.food.memory.MemoryManagementService;
import com.example.food.memory.MemoryManagementItemResponse;
import com.example.food.memory.MemoryPersonalizationService;
import com.example.food.memory.SkillPreferenceCatalog;
import com.example.food.memory.MemoryProfile;
import com.example.food.memory.MemoryProfileMapper;
import com.example.food.memory.MemoryProcessingJobService;
import com.example.food.memory.MemoryRetrievalResult;
import com.example.food.memory.MemoryRetriever;
import com.example.food.memory.MemorySearchCommand;
import com.example.food.memory.PersonalizedSkillService;
import com.example.food.memory.PersonalizedSkillProjectionService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class AgentMemoryToolService {

    private static final Set<String> DECLARABLE_TYPES = Set.of(
            "INGREDIENT_PREFERENCE", "DIET_GOAL", "SKILL_PREFERENCE");
    private static final Set<String> POSITIVE_WORDS = Set.of("喜欢", "爱吃", "偏好", "更喜欢");
    private static final Set<String> NEGATIVE_WORDS = Set.of("不吃", "不喜欢", "忌口", "避免", "过敏", "讨厌");
    private static final Set<String> DIET_GOAL_WORDS = Set.of("目标", "增肌", "减脂", "控糖", "高蛋白", "低脂");

    private final MemoryRetriever retriever;
    private final MemoryProfileMapper profileMapper;
    private final MemoryEpisodeService episodeService;
    private final MemoryManagementService managementService;
    private final MemoryPersonalizationService personalizationService;
    private final PersonalizedSkillService skillService;
    private final PersonalizedSkillProjectionService skillProjectionService;
    private final MemoryProcessingJobService jobService;
    private final ObjectMapper objectMapper;

    public AgentMemoryToolService(
            MemoryRetriever retriever,
            MemoryProfileMapper profileMapper,
            MemoryEpisodeService episodeService,
            MemoryManagementService managementService,
            MemoryPersonalizationService personalizationService,
            PersonalizedSkillService skillService,
            MemoryProcessingJobService jobService,
            ObjectMapper objectMapper
    ) {
        this(retriever, profileMapper, episodeService, managementService, personalizationService,
                skillService, jobService, objectMapper, null);
    }

    @Autowired
    public AgentMemoryToolService(
            MemoryRetriever retriever,
            MemoryProfileMapper profileMapper,
            MemoryEpisodeService episodeService,
            MemoryManagementService managementService,
            MemoryPersonalizationService personalizationService,
            PersonalizedSkillService skillService,
            MemoryProcessingJobService jobService,
            ObjectMapper objectMapper,
            PersonalizedSkillProjectionService skillProjectionService
    ) {
        this.retriever = retriever;
        this.profileMapper = profileMapper;
        this.episodeService = episodeService;
        this.managementService = managementService;
        this.personalizationService = personalizationService;
        this.skillService = skillService;
        this.skillProjectionService = skillProjectionService;
        this.jobService = jobService;
        this.objectMapper = objectMapper;
    }

    public boolean isMutation(Tool tool) {
        return tool == Tool.MEMORY_EPISODE_SAVE || tool == Tool.MEMORY_PREFERENCE_UPDATE;
    }

    public boolean isPersonalizationEnabled(Long userId) {
        requireUser(userId);
        return personalizationService.isEnabled(userId);
    }

    public String actionType(Tool tool) {
        return switch (tool) {
            case MEMORY_EPISODE_SAVE -> "MEMORY_PREFERENCE_DECLARATION";
            case MEMORY_PREFERENCE_UPDATE -> "MEMORY_PREFERENCE_UPDATE";
            default -> throw unsupported();
        };
    }

    public String impact(Tool tool) {
        return switch (tool) {
            case MEMORY_EPISODE_SAVE -> "将把本轮明确表达的偏好加入长期记忆，并在后台更新画像。";
            case MEMORY_PREFERENCE_UPDATE -> "将修改你已有的一条长期记忆，并同步更新画像。";
            default -> throw unsupported();
        };
    }

    public String impact(Tool tool, JsonNode arguments, Long userId) {
        if (tool == Tool.MEMORY_EPISODE_SAVE) {
            String entity = optionalText(arguments, "entity", "目标");
            String preference = optionalText(arguments, "preference", "LIKE").toUpperCase(Locale.ROOT);
            String evidence = optionalText(arguments, "evidence", "本轮明确表达");
            String type = optionalText(arguments, "candidateType", "INGREDIENT_PREFERENCE").toUpperCase(Locale.ROOT);
            if ("SKILL_PREFERENCE".equals(type)) {
                return "将根据你的原话“" + evidence + "”记录执行习惯："
                        + SkillPreferenceCatalog.displayName(entity) + "设为"
                        + SkillPreferenceCatalog.displayValue(entity, preference) + "。";
            }
            return "将根据你的原话“" + evidence + "”记录“" + entity + "”的 " + preference + " 偏好。";
        }
        if (tool == Tool.MEMORY_PREFERENCE_UPDATE) {
            Long memoryId = positiveLong(arguments, "memoryId");
            Integer version = nonNegativeInt(arguments, "version");
            MemoryManagementItemResponse current = managementService.requireActiveAgentMemory(userId, memoryId, version);
            String preference = requiredText(arguments, "preference", 32).toUpperCase(Locale.ROOT);
            return "将把“" + current.entity() + "”的记忆从“" + current.preference() + "”修改为“"
                    + preference + "”，强度 " + formatStrength(arguments, current) + "。";
        }
        return impact(tool);
    }

    public void validateDeclaration(JsonNode arguments, String userMessage) {
        String entity = requiredText(arguments, "entity", 120);
        String preference = requiredText(arguments, "preference", 32).toUpperCase(Locale.ROOT);
        String evidence = requiredText(arguments, "evidence", 240);
        String type = optionalText(arguments, "candidateType", "INGREDIENT_PREFERENCE").toUpperCase(Locale.ROOT);
        if (!DECLARABLE_TYPES.contains(type)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "当前不支持记录此类个人偏好");
        }
        if ("SKILL_PREFERENCE".equals(type)) {
            String key = SkillPreferenceCatalog.normalizeKey(entity);
            String value = SkillPreferenceCatalog.normalizeValue(key, preference);
            if (key == null || value == null || !StringUtils.hasText(userMessage)
                    || !userMessage.contains(evidence)
                    || !SkillPreferenceCatalog.evidenceSupports(key, value, evidence)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "执行习惯必须来自本轮原话，且设置值必须与原话一致");
            }
            return;
        }
        if (!StringUtils.hasText(userMessage) || !userMessage.contains(entity) || !userMessage.contains(evidence)
                || !evidence.contains(entity)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "记忆证据必须是本轮用户原话中的明确内容");
        }
        boolean positive = Set.of("LIKE", "PURSUE").contains(preference);
        boolean negative = Set.of("DISLIKE", "AVOID").contains(preference);
        boolean positiveEvidence = "DIET_GOAL".equals(type)
                ? DIET_GOAL_WORDS.stream().anyMatch(evidence::contains)
                : POSITIVE_WORDS.stream().anyMatch(evidence::contains);
        if ((!positive && !negative)
                || positive && !positiveEvidence
                || negative && NEGATIVE_WORDS.stream().noneMatch(evidence::contains)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "记忆偏好方向与用户原话不匹配");
        }
        if ("DIET_GOAL".equals(type) && !Set.of("PURSUE", "AVOID").contains(preference)
                || "INGREDIENT_PREFERENCE".equals(type) && !Set.of("LIKE", "DISLIKE", "AVOID").contains(preference)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "记忆类型与偏好方向不匹配");
        }
    }

    public MemoryManagementItemResponse validateUpdate(JsonNode arguments, Long userId) {
        Long memoryId = positiveLong(arguments, "memoryId");
        Integer version = nonNegativeInt(arguments, "version");
        String preference = requiredText(arguments, "preference", 32).toUpperCase(Locale.ROOT);
        MemoryManagementItemResponse current = managementService.requireActiveAgentMemory(userId, memoryId, version);
        if ("SKILL_PREFERENCE".equalsIgnoreCase(current.memoryType())
                && SkillPreferenceCatalog.normalizeValue(current.entity(), preference) != null) {
            if (arguments != null && arguments.hasNonNull("strength")) decimal(arguments, "strength");
            return current;
        }
        Set<String> allowed = switch (current.memoryType() == null ? "" : current.memoryType().toUpperCase(Locale.ROOT)) {
            case "INGREDIENT_PREFERENCE" -> Set.of("LIKE", "DISLIKE", "AVOID");
            case "RECIPE_PREFERENCE" -> Set.of("LIKE", "DISLIKE");
            case "DIET_GOAL" -> Set.of("PURSUE", "AVOID");
            default -> Set.of();
        };
        if (!allowed.contains(preference)) {
            throw invalid("记忆类型与偏好方向不匹配");
        }
        if (arguments != null && arguments.hasNonNull("strength")) decimal(arguments, "strength");
        return current;
    }

    public AgentKitchenToolService.ToolResult executeRead(Tool tool, JsonNode arguments, Long userId) {
        requireUser(userId);
        if (!personalizationService.isEnabled(userId)) {
            return result("个人记忆", "你已关闭个性化，本轮不会读取或使用个人长期记忆。", Map.of("enabled", false));
        }
        return switch (tool) {
            case MEMORY_SEARCH -> search(arguments, userId);
            case MEMORY_PROFILE_GET -> profile(userId);
            case MEMORY_EPISODES_LIST, MEMORY_RECIPE_HISTORY -> episodes(tool, arguments, userId);
            case MEMORY_SKILL_GET -> skill(arguments, userId);
            default -> throw unsupported();
        };
    }

    @Transactional
    public AgentKitchenActionService.ActionResult executeConfirmed(
            String actionType,
            JsonNode payload,
            Long userId,
            String idempotencyKey
    ) {
        requireUser(userId);
        if (isMemoryMutationAction(actionType) && !personalizationService.isEnabled(userId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "当前账号已关闭个性化，不能写入或修改个人长期记忆");
        }
        if ("MEMORY_PREFERENCE_DECLARATION".equals(actionType)) {
            String entity = requiredText(payload, "entity", 120);
            String preference = requiredText(payload, "preference", 32).toUpperCase(Locale.ROOT);
            String evidence = requiredText(payload, "evidence", 240);
            String candidateType = optionalText(payload, "candidateType", "INGREDIENT_PREFERENCE")
                    .toUpperCase(Locale.ROOT);
            validateDeclaration(payload, evidence);
            if (!StringUtils.hasText(idempotencyKey)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "记忆写入缺少幂等凭证");
            }
            Map<String, Object> episodePayload = Map.of(
                    "entity", entity,
                    "preference", preference,
                    "candidateType", candidateType,
                    "evidence", evidence
            );
            MemoryEpisodeService.RecordResult recorded;
            try {
                recorded = episodeService.record(userId, new MemoryEpisodeCommand(
                        null, null, "USER_PREFERENCE_DECLARED", "AGENT_DECLARATION",
                        idempotencyKey, idempotencyKey, idempotencyKey,
                        "用户明确表达偏好：" + entity,
                        objectMapper.writeValueAsString(episodePayload), LocalDateTime.now(), new BigDecimal("0.9500")
                ));
            } catch (JsonProcessingException exception) {
                throw new IllegalStateException("用户偏好事件序列化失败", exception);
            }
            jobService.enqueue(userId, recorded.episode().getId());
            return new AgentKitchenActionService.ActionResult(
                    recorded.duplicate() ? "这条偏好已经记过，不会重复增加证据" : "已记录这条偏好，画像将在后台更新",
                    Map.of("episodeId", recorded.episode().getId(), "duplicate", recorded.duplicate())
            );
        }
        if ("MEMORY_PREFERENCE_UPDATE".equals(actionType)) {
            MemoryManagementItemResponse current = validateUpdate(payload, userId);
            Long memoryId = positiveLong(payload, "memoryId");
            Integer version = nonNegativeInt(payload, "version");
            String preference = requiredText(payload, "preference", 32);
            BigDecimal strength = payload != null && payload.hasNonNull("strength")
                    ? decimal(payload, "strength") : current.strength();
            var updated = managementService.updateItem(userId, memoryId,
                    new MemoryItemUpdateRequest(preference, strength, version));
            return new AgentKitchenActionService.ActionResult("已修改这条长期记忆", updated);
        }
        throw unsupported();
    }

    private boolean isMemoryMutationAction(String actionType) {
        return "MEMORY_PREFERENCE_DECLARATION".equals(actionType)
                || "MEMORY_PREFERENCE_UPDATE".equals(actionType);
    }

    private AgentKitchenToolService.ToolResult search(JsonNode arguments, Long userId) {
        String query = optionalText(arguments, "query", "");
        if (!StringUtils.hasText(query)) throw invalid("query 不能为空");
        if (query.length() > 500) throw invalid("query 不能超过 500 个字符");
        int limit = boundedLimit(arguments, 8);
        MemorySearchCommand command = new MemorySearchCommand(query, null, null, null, null, null,
                null, null, null, null, null, null, limit);
        MemoryRetrievalResult retrieval = retriever.search(userId, command);
        return result("相关个人记忆", "已按当前问题检索个人记忆，不包含外部菜谱知识。", retrieval);
    }

    private AgentKitchenToolService.ToolResult profile(Long userId) {
        MemoryProfile profile = profileMapper.findOwned(userId);
        Map<String, Object> payload = Map.of(
                "profile", profile == null ? Map.of("available", false) : profile,
                "editableMemories", managementService.listAgentMemories(userId, 12)
        );
        return result("结构化画像", profile == null ? "当前还没有结构化画像，以下仅列出当前账号可管理的长期记忆。"
                : "已读取当前账号的结构化画像和可管理记忆。", payload);
    }

    private AgentKitchenToolService.ToolResult episodes(Tool tool, JsonNode arguments, Long userId) {
        int limit = boundedLimit(arguments, 10);
        String type = optionalText(arguments, "episodeType", null);
        if (tool == Tool.MEMORY_RECIPE_HISTORY) {
            List<MemoryEpisode> episodes = episodeService.listOwnedRecipeHistory(userId, Math.min(limit, 30));
            return result("菜谱行为历史", "仅返回当前登录用户的菜谱搜索、收藏、反馈、烹饪和成品评价事件。", episodes);
        }
        List<MemoryEpisode> episodes = episodeService.listOwned(userId, null, type, limit);
        return result("个人记忆事件", "仅返回当前登录用户的历史事件。", episodes);
    }

    private AgentKitchenToolService.ToolResult skill(JsonNode arguments, Long userId) {
        String task = requiredText(arguments, "task", 300);
        MemoryProfile profile = profileMapper.findOwned(userId);
        var skill = skillProjectionService == null
                ? skillService.resolve(task, profile == null ? null : profile.getProfileJson())
                : skillProjectionService.resolve(userId, task, profile);
        return result("个性化执行策略", skill == null ? "该任务暂无匹配的个性化技能。"
                : skill.personalized() ? "已读取适用于当前任务的个性化策略。" : "找到基础技能，但当前画像没有足够证据生成个性化策略。", skill);
    }

    private AgentKitchenToolService.ToolResult result(String title, String summary, Object payload) {
        return new AgentKitchenToolService.ToolResult(title, summary, payload, "operation-result-card");
    }

    private int boundedLimit(JsonNode node, int fallback) {
        JsonNode limit = node == null ? null : node.path("limit");
        int requested = limit != null && limit.canConvertToInt() ? limit.intValue() : fallback;
        return Math.max(1, Math.min(requested, 30));
    }

    private BigDecimal decimal(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.path(field);
        if (value == null || !value.isNumber() || value.decimalValue().compareTo(BigDecimal.ZERO) < 0
                || value.decimalValue().compareTo(BigDecimal.ONE) > 0) {
            throw invalid(field + " 必须在 0 到 1 之间");
        }
        return value.decimalValue();
    }

    private String formatStrength(JsonNode node, MemoryManagementItemResponse current) {
        BigDecimal strength = node != null && node.hasNonNull("strength")
                ? decimal(node, "strength") : current.strength();
        if (strength == null) strength = new BigDecimal("0.9500");
        return strength.stripTrailingZeros().toPlainString();
    }

    private Long positiveLong(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.path(field);
        if (value == null || !value.canConvertToLong() || value.longValue() <= 0) throw invalid(field + " 无效");
        return value.longValue();
    }

    private Integer nonNegativeInt(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.path(field);
        if (value == null || !value.canConvertToInt() || value.intValue() < 0) throw invalid(field + " 无效");
        return value.intValue();
    }

    private String requiredText(JsonNode node, String field, int maxLength) {
        String value = optionalText(node, field, null);
        if (!StringUtils.hasText(value) || value.length() > maxLength) throw invalid(field + " 无效");
        return value;
    }

    private String optionalText(JsonNode node, String field, String fallback) {
        JsonNode value = node == null ? null : node.path(field);
        return value != null && value.isTextual() && StringUtils.hasText(value.textValue())
                ? value.textValue().trim() : fallback;
    }

    private void requireUser(Long userId) {
        if (userId == null || userId <= 0) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户身份无效");
    }

    private ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private ResponseStatusException unsupported() {
        return invalid("不支持的记忆工具操作");
    }
}
