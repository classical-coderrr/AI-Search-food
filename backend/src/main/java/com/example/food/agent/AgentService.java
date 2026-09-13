package com.example.food.agent;

import com.example.food.ai.recipe.RecipeRecommendationService;
import com.example.food.ai.recipe.dto.RecipeGenerateRequest;
import com.example.food.ai.recipe.dto.RecipeGenerateResponse;
import com.example.food.ai.ingredient.IngredientRecognitionService;
import com.example.food.ai.ingredient.dto.IngredientRecognitionResponse;
import com.example.food.ai.qwen.QwenAgentClient;
import com.example.food.agent.dto.AgentChatRequest;
import com.example.food.notification.NotificationService;
import com.example.food.notification.dto.NotificationPageResponse;
import com.example.food.notification.dto.NotificationResponse;
import com.example.food.pantry.UserPantryService;
import com.example.food.pantry.dto.PantryExpirySummaryResponse;
import com.example.food.pantry.dto.PantryItemResponse;
import com.example.food.recipe.SavedRecipeService;
import com.example.food.recipe.dto.RecipeHistoryDetailResponse;
import com.example.food.recipe.dto.RecipeHistorySummaryResponse;
import com.example.food.security.AppRole;
import com.example.food.security.AuthPrincipal;
import com.example.food.user.health.UserHealthProfileService;
import com.example.food.user.healthnutrition.HealthNutritionService;
import com.example.food.user.nutrition.UserNutritionTargetService;
import com.example.food.user.preference.UserDietPreferenceService;
import com.example.food.user.preference.dto.DietPreferenceResponse;
import com.example.food.weekly.WeeklyMenuService;
import com.example.food.weekly.dto.WeeklyMenuItemResponse;
import com.example.food.weekly.dto.WeeklyMenuResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class AgentService {

    private static final long STREAM_TIMEOUT_MILLIS = 120_000L;
    private static final int MAX_MESSAGE_LENGTH = 1000;
    private static final int MAX_AGENT_ROUNDS = 5;
    private static final int MAX_TOOL_CALLS = 8;
    private static final int HISTORY_MESSAGE_LIMIT = 12;
    private static final String ROLE_USER = "USER";
    private static final String ROLE_ASSISTANT = "ASSISTANT";

    private final AgentConversationMapper conversationMapper;
    private final AgentMessageMapper messageMapper;
    private final AgentConfirmationMapper confirmationMapper;
    private final AgentToolRegistry toolRegistry;
    private final AgentKitchenToolService kitchenToolService;
    private final AgentWriteService writeService;
    private final IngredientRecognitionService ingredientRecognitionService;
    private final UserPantryService pantryService;
    private final NotificationService notificationService;
    private final WeeklyMenuService weeklyMenuService;
    private final SavedRecipeService savedRecipeService;
    private final UserHealthProfileService healthProfileService;
    private final UserNutritionTargetService nutritionTargetService;
    private final HealthNutritionService healthNutritionService;
    private final UserDietPreferenceService dietPreferenceService;
    private final RecipeRecommendationService recipeRecommendationService;
    private final QwenAgentClient qwenAgentClient;
    private final ObjectMapper objectMapper;
    private final ExecutorService workerExecutor = Executors.newCachedThreadPool(
            runnable -> {
                Thread thread = new Thread(runnable, "kitchen-agent-" + System.nanoTime());
                thread.setDaemon(true);
                return thread;
            }
    );

    public AgentService(
            AgentConversationMapper conversationMapper,
            AgentMessageMapper messageMapper,
            AgentConfirmationMapper confirmationMapper,
            AgentToolRegistry toolRegistry,
            AgentKitchenToolService kitchenToolService,
            AgentWriteService writeService,
            IngredientRecognitionService ingredientRecognitionService,
            UserPantryService pantryService,
            NotificationService notificationService,
            WeeklyMenuService weeklyMenuService,
            SavedRecipeService savedRecipeService,
            UserHealthProfileService healthProfileService,
            UserNutritionTargetService nutritionTargetService,
            HealthNutritionService healthNutritionService,
            UserDietPreferenceService dietPreferenceService,
            RecipeRecommendationService recipeRecommendationService,
            QwenAgentClient qwenAgentClient,
            ObjectMapper objectMapper
    ) {
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.confirmationMapper = confirmationMapper;
        this.toolRegistry = toolRegistry;
        this.kitchenToolService = kitchenToolService;
        this.writeService = writeService;
        this.ingredientRecognitionService = ingredientRecognitionService;
        this.pantryService = pantryService;
        this.notificationService = notificationService;
        this.weeklyMenuService = weeklyMenuService;
        this.savedRecipeService = savedRecipeService;
        this.healthProfileService = healthProfileService;
        this.nutritionTargetService = nutritionTargetService;
        this.healthNutritionService = healthNutritionService;
        this.dietPreferenceService = dietPreferenceService;
        this.recipeRecommendationService = recipeRecommendationService;
        this.qwenAgentClient = qwenAgentClient;
        this.objectMapper = objectMapper;
    }

    public SseEmitter stream(AgentChatRequest request, AuthPrincipal principal) {
        return stream(request, principal, null);
    }

    public SseEmitter stream(AgentChatRequest request, AuthPrincipal principal, MultipartFile image) {
        requireUser(principal);
        AgentAttachment attachment = AgentAttachment.from(image);
        validateRequest(request, attachment);
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MILLIS);
        AtomicBoolean cancelled = new AtomicBoolean(false);
        Future<?> worker = workerExecutor.submit(() -> run(emitter, cancelled, request, principal, attachment));
        Runnable cancel = () -> {
            cancelled.set(true);
            worker.cancel(true);
        };
        emitter.onCompletion(cancel);
        emitter.onTimeout(cancel);
        emitter.onError(error -> cancel.run());
        return emitter;
    }

    public void deleteConversation(Long userId, Long conversationId) {
        if (conversationMapper.deleteOwned(userId, conversationId) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在");
        }
    }

    private void run(
            SseEmitter emitter,
            AtomicBoolean cancelled,
            AgentChatRequest request,
            AuthPrincipal principal,
            AgentAttachment attachment
    ) {
        AgentConversation conversation = null;
        try {
            conversation = conversation(request, principal.id());
            sendOrCancel(emitter, cancelled, "conversation.ready", Map.of("conversationId", conversation.getId()));

            if (request.confirmationId() != null) {
                handleConfirmation(emitter, cancelled, principal, conversation, request);
                return;
            }

            String message = normalizedMessage(request.message(), attachment);
            String modelMessage = imageContext(emitter, cancelled, conversation.getId(), message, attachment);
            saveMessage(principal.id(), conversation.getId(), ROLE_USER, "text", modelMessage);
            runAgent(emitter, cancelled, principal, conversation.getId(), modelMessage, attachment);
            conversationMapper.touch(principal.id(), conversation.getId());
            sendOrCancel(emitter, cancelled, "done", Map.of("conversationId", conversation.getId()));
            emitter.complete();
        } catch (Throwable exception) {
            if (cancelled.get() || Thread.currentThread().isInterrupted()) {
                return;
            }
            String message = errorMessage(exception);
            if (conversation != null) {
                try {
                    saveMessage(principal.id(), conversation.getId(), ROLE_ASSISTANT, "text", message);
                } catch (RuntimeException ignored) {
                    // The stream error remains the source of truth for the client.
                }
            }
            send(emitter, cancelled, "error", Map.of("message", message));
            emitter.complete();
        }
    }

    private void runAgent(
            SseEmitter emitter,
            AtomicBoolean cancelled,
            AuthPrincipal principal,
            Long conversationId,
            String userMessage,
            AgentAttachment attachment
    ) {
        List<QwenAgentClient.ConversationMessage> messages = conversationHistory(principal.id(), conversationId);
        if (messages.isEmpty()) {
            messages.add(QwenAgentClient.ConversationMessage.user(userMessage));
        }
        int toolCallCount = 0;
        boolean confirmationRequested = false;
        sendToolStarted(emitter, cancelled, "小厨灵正在理解你的需求");

        for (int round = 0; round < MAX_AGENT_ROUNDS; round++) {
            QwenAgentClient.AgentTurn turn = qwenAgentClient.complete(
                    messages,
                    toolRegistry.functionDefinitions(userMessage, attachment != null)
            );
            messages.add(QwenAgentClient.ConversationMessage.assistant(turn));
            if (turn.toolCalls().isEmpty()) {
                sendText(emitter, cancelled, conversationId, limit(turn.content(), 12_000));
                return;
            }

            for (QwenAgentClient.ToolCall call : turn.toolCalls()) {
                toolCallCount++;
                if (toolCallCount > MAX_TOOL_CALLS) {
                    throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "小厨灵调用工具次数过多，请简化问题后重试");
                }
                AgentToolRegistry.Tool tool = toolRegistry.requireFunction(call.name());
                JsonNode arguments = toolArguments(call.arguments());
                sendToolStarted(emitter, cancelled, tool);
                ToolExecution execution;
                if ((tool == AgentToolRegistry.Tool.RECIPE_SAVE || kitchenToolService.isMutation(tool, arguments))
                        && confirmationRequested) {
                    execution = new ToolExecution(
                            Map.of("status", "confirmation_already_requested"),
                            "本轮已发起一项操作确认"
                    );
                } else if (kitchenToolService.isMutation(tool, arguments)) {
                    execution = requestActionConfirmation(
                            emitter, cancelled, principal.id(), conversationId, tool, arguments);
                } else {
                    execution = executeTool(
                            tool, arguments, emitter, cancelled, principal, conversationId, userMessage, attachment);
                }
                confirmationRequested = confirmationRequested || execution.confirmationRequested();
                sendToolResult(emitter, cancelled, tool, execution.summary());
                messages.add(QwenAgentClient.ConversationMessage.tool(call.id(), toolOutput(execution.output())));
            }
        }
        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "小厨灵尚未完成工具调用，请缩小问题范围后重试");
    }

    private ToolExecution executeTool(
            AgentToolRegistry.Tool tool,
            JsonNode arguments,
            SseEmitter emitter,
            AtomicBoolean cancelled,
            AuthPrincipal principal,
            Long conversationId,
            String userMessage,
            AgentAttachment attachment
    ) {
        return switch (tool) {
            case PANTRY_LIST -> pantry(emitter, cancelled, principal.id(), conversationId);
            case PANTRY_EXPIRY -> expiry(emitter, cancelled, principal.id(), conversationId);
            case NOTIFICATIONS -> reminders(emitter, cancelled, principal.id(), conversationId);
            case WEEKLY_MENU -> menu(emitter, cancelled, principal.id(), conversationId);
            case SAVED_RECIPES -> savedRecipes(emitter, cancelled, principal.id(), conversationId);
            case NUTRITION_PROFILE -> nutrition(emitter, cancelled, principal.id(), conversationId);
            case RECIPE_GENERATE -> recipe(emitter, cancelled, principal, conversationId, userMessage, arguments);
            case RECIPE_SAVE -> requestSave(emitter, cancelled, principal.id(), conversationId);
            case CURRENT_DATETIME, PANTRY_MANAGE, NOTIFICATION_MANAGE, MEAL_PLAN_MANAGE,
                    RECIPE_LIBRARY_MANAGE, PROFILE_MANAGE, FINISHED_DISH_MANAGE ->
                    kitchenTool(emitter, cancelled, principal, conversationId, tool, arguments, attachment);
        };
    }

    private List<QwenAgentClient.ConversationMessage> conversationHistory(Long userId, Long conversationId) {
        List<QwenAgentClient.ConversationMessage> messages = new ArrayList<>();
        for (AgentMessage message : messageMapper.findRecentTextMessages(userId, conversationId, HISTORY_MESSAGE_LIMIT)) {
            if (ROLE_USER.equals(message.getRole())) {
                messages.add(QwenAgentClient.ConversationMessage.user(message.getContent()));
            } else if (ROLE_ASSISTANT.equals(message.getRole())) {
                messages.add(QwenAgentClient.ConversationMessage.assistant(message.getContent()));
            }
        }
        return messages;
    }

    private JsonNode toolArguments(String rawArguments) {
        String value = rawArguments == null || rawArguments.isBlank() ? "{}" : rawArguments.trim();
        if (value.length() > 4000) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "千问 Agent 返回的工具参数过长");
        }
        try {
            JsonNode arguments = objectMapper.readTree(value);
            if (arguments == null || !arguments.isObject()) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "千问 Agent 返回的工具参数不是 JSON 对象");
            }
            return arguments;
        } catch (JsonProcessingException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "千问 Agent 返回的工具参数不是有效 JSON", exception);
        }
    }

    private String toolOutput(Object output) {
        try {
            return limit(objectMapper.writeValueAsString(output), 50_000);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("厨房助手工具结果序列化失败", exception);
        }
    }

    private ToolExecution kitchenTool(
            SseEmitter emitter,
            AtomicBoolean cancelled,
            AuthPrincipal principal,
            Long conversationId,
            AgentToolRegistry.Tool tool,
            JsonNode arguments,
            AgentAttachment attachment
    ) {
        AgentKitchenToolService.ToolResult result = kitchenToolService.execute(tool, arguments, principal, attachment);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", result.title());
        payload.put("summary", result.summary());
        payload.put("detail", result.payload());
        sendCard(emitter, cancelled, conversationId, result.cardType(), payload, "来自当前账号 · 刚刚查询");
        return new ToolExecution(payload, result.summary());
    }

    private ToolExecution requestActionConfirmation(
            SseEmitter emitter,
            AtomicBoolean cancelled,
            Long userId,
            Long conversationId,
            AgentToolRegistry.Tool tool,
            JsonNode arguments
    ) {
        String actionType = kitchenToolService.actionType(tool, arguments);
        JsonNode payload = kitchenToolService.actionPayload(arguments);
        AgentConfirmation confirmation = new AgentConfirmation();
        confirmation.setConversationId(conversationId);
        confirmation.setUserId(userId);
        confirmation.setActionType(actionType);
        confirmation.setIdempotencyKey(UUID.randomUUID().toString().replace("-", ""));
        confirmation.setPayloadJson(toolOutput(payload));
        confirmation.setStatus("PENDING");
        confirmation.setCreatedAt(LocalDateTime.now());
        confirmationMapper.insert(confirmation);

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("confirmationId", confirmation.getId());
        event.put("idempotencyKey", confirmation.getIdempotencyKey());
        event.put("actionType", actionType);
        event.put("title", tool.label());
        event.put("impact", kitchenToolService.impact(tool, arguments));
        event.put("actionLabel", "确认执行");
        event.put("cancelLabel", "暂不执行");
        event.put("expiresInMinutes", 30);
        sendOrCancel(emitter, cancelled, "confirmation.required", event);
        saveMessage(null, conversationId, ROLE_ASSISTANT, "confirmation-card", payloadForMessage("confirmation-card", event));
        return new ToolExecution(
                Map.of("status", "confirmation_required", "actionType", actionType, "expiresInMinutes", 30),
                "已发起操作确认",
                true
        );
    }

    private String imageContext(
            SseEmitter emitter,
            AtomicBoolean cancelled,
            Long conversationId,
            String message,
            AgentAttachment attachment
    ) {
        if (attachment == null) {
            return message;
        }
        String normalized = message.toLowerCase(Locale.ROOT);
        if (containsAny(normalized, "成品", "摆盘", "火候", "打分", "评价", "复盘", "做完")) {
            Map<String, Object> payload = Map.of(
                    "title", "已收到成品图片",
                    "summary", "图片会在本轮成品评价中使用",
                    "detail", Map.of("filename", attachment.originalFilename(), "contentType", attachment.contentType())
            );
            sendCard(emitter, cancelled, conversationId, "attachment-card", payload, "来自本轮对话附件");
            return message + "\n\n[系统补充：用户本轮附带了一张成品图片，可调用 finished_dish_manage 的 review 动作。]";
        }

        IngredientRecognitionResponse recognition = ingredientRecognitionService.recognize(attachment.asMultipartFile());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", "图片食材识别");
        payload.put("summary", recognition.description());
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("ingredients", recognition.ingredients());
        detail.put("provider", recognition.provider());
        detail.put("model", recognition.model());
        payload.put("detail", detail);
        sendCard(emitter, cancelled, conversationId, "image-recognition-card", payload, "来自本轮对话附件 · 刚刚识别");
        return message + "\n\n[图片识别结果：" + String.join("、", recognition.ingredients())
                + "。识别说明：" + recognition.description() + "]";
    }

    private void handleConfirmation(
            SseEmitter emitter,
            AtomicBoolean cancelled,
            AuthPrincipal principal,
            AgentConversation conversation,
            AgentChatRequest request
    ) {
        if (request.idempotencyKey() == null || request.idempotencyKey().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "确认操作需要幂等凭证");
        }
        AgentConfirmation confirmation = confirmationMapper.findOwned(principal.id(), request.confirmationId());
        if (confirmation == null || !conversation.getId().equals(confirmation.getConversationId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "操作确认已失效");
        }
        sendToolStatus(emitter, cancelled, "小厨灵正在执行已确认的操作");
        AgentWriteService.ConfirmationResult result = writeService.execute(
                principal,
                request.confirmationId(),
                request.idempotencyKey().trim()
        );
        sendText(emitter, cancelled, conversation.getId(), result.message());
        if (result.detail() instanceof RecipeHistoryDetailResponse detail) {
            sendRecipeCard(emitter, cancelled, conversation.getId(), detail.recipe(), "来自我的菜谱收藏 · 刚刚保存");
        } else {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("title", "操作完成");
            payload.put("summary", result.message());
            payload.put("detail", result.detail());
            sendCard(emitter, cancelled, conversation.getId(), "operation-result-card", payload, "来自当前账号 · 刚刚更新");
        }
        conversationMapper.touch(principal.id(), conversation.getId());
        sendOrCancel(emitter, cancelled, "done", Map.of("conversationId", conversation.getId()));
        emitter.complete();
    }

    private ToolExecution requestSave(
            SseEmitter emitter,
            AtomicBoolean cancelled,
            Long userId,
            Long conversationId
    ) {
        RecipeGenerateResponse recipe = latestRecipe(userId, conversationId);
        if (recipe == null) {
            return new ToolExecution(
                    Map.of("status", "no_recipe", "message", "当前会话还没有可保存的菜谱"),
                    "当前会话没有可保存的菜谱"
            );
        }
        AgentConfirmation confirmation = new AgentConfirmation();
        confirmation.setConversationId(conversationId);
        confirmation.setUserId(userId);
        confirmation.setActionType("SAVE_RECIPE");
        confirmation.setIdempotencyKey(UUID.randomUUID().toString().replace("-", ""));
        confirmation.setPayloadJson(payloadForMessage("recipe-card", Map.of("recipe", recipe)));
        confirmation.setStatus("PENDING");
        confirmation.setCreatedAt(LocalDateTime.now());
        confirmationMapper.insert(confirmation);

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("confirmationId", confirmation.getId());
        event.put("idempotencyKey", confirmation.getIdempotencyKey());
        event.put("actionType", "SAVE_RECIPE");
        event.put("title", recipe.title());
        event.put("impact", "会将“" + recipe.title() + "”保存到我的菜谱收藏，不会修改库存或周菜单。");
        event.put("actionLabel", "确认保存菜谱");
        event.put("expiresInMinutes", 30);
        sendOrCancel(emitter, cancelled, "confirmation.required", event);
        saveMessage(null, conversationId, ROLE_ASSISTANT, "confirmation-card", payloadForMessage("confirmation-card", event));
        return new ToolExecution(
                Map.of(
                        "status", "confirmation_required",
                        "title", recipe.title(),
                        "expiresInMinutes", 30,
                        "message", "已向用户展示保存确认，等待用户操作"
                ),
                "已发起保存确认",
                true
        );
    }

    private AgentConversation conversation(AgentChatRequest request, Long userId) {
        if (request.conversationId() != null) {
            AgentConversation existing = conversationMapper.findOwned(userId, request.conversationId());
            if (existing == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在");
            }
            return existing;
        }
        AgentConversation conversation = new AgentConversation();
        conversation.setUserId(userId);
        conversation.setTitle("厨房助手对话");
        conversation.setCreatedAt(LocalDateTime.now());
        conversation.setUpdatedAt(conversation.getCreatedAt());
        conversationMapper.insert(conversation);
        return conversation;
    }

    private ToolExecution pantry(SseEmitter emitter, AtomicBoolean cancelled, Long userId, Long conversationId) {
        List<PantryItemResponse> items = pantryService.list(userId);
        PantryExpirySummaryResponse expiry = pantryService.expirySummary(userId);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("count", items.size());
        payload.put("expiringSoonCount", expiry.expiringSoonItems().size());
        payload.put("expiredCount", expiry.expiredItems().size());
        payload.put("asOf", expiry.asOf());
        payload.put("items", items.stream().map(item -> pantryItem(item, expiry.asOf())).toList());
        sendCard(emitter, cancelled, conversationId, "inventory-card", payload, "来自我的食材库存 · 刚刚查询");
        return new ToolExecution(payload, "已找到 " + items.size() + " 种食材");
    }

    private ToolExecution expiry(SseEmitter emitter, AtomicBoolean cancelled, Long userId, Long conversationId) {
        PantryExpirySummaryResponse summary = pantryService.expirySummary(userId);
        List<Map<String, Object>> items = new ArrayList<>();
        summary.expiredItems().forEach(item -> items.add(pantryItem(item, "已过期", summary.asOf())));
        summary.expiringSoonItems().forEach(item -> items.add(pantryItem(item, "临期", summary.asOf())));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("count", items.size());
        payload.put("expiredCount", summary.expiredItems().size());
        payload.put("expiringSoonCount", summary.expiringSoonItems().size());
        payload.put("asOf", summary.asOf());
        payload.put("items", items);
        sendCard(emitter, cancelled, conversationId, "inventory-card", payload, "来自我的食材库存 · 刚刚查询");
        return new ToolExecution(payload,
                "已找到 " + (summary.expiredItems().size() + summary.expiringSoonItems().size()) + " 项到期提醒");
    }

    private ToolExecution reminders(SseEmitter emitter, AtomicBoolean cancelled, Long userId, Long conversationId) {
        NotificationPageResponse page = notificationService.list(userId, "UNREAD", 1, 8);
        long unread = notificationService.unreadCount(userId);
        List<Map<String, Object>> items = page.items().stream().map(this::notificationItem).toList();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("unreadCount", unread);
        payload.put("items", items);
        sendCard(emitter, cancelled, conversationId, "reminder-card", payload, "来自我的提醒中心 · 刚刚查询");
        return new ToolExecution(payload, "有 " + unread + " 条未读提醒");
    }

    private ToolExecution menu(SseEmitter emitter, AtomicBoolean cancelled, Long userId, Long conversationId) {
        WeeklyMenuResponse response = weeklyMenuService.get(userId, null);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("weekStart", response.weekStart());
        payload.put("weekEnd", response.weekEnd());
        payload.put("items", response.items().stream().map(this::menuItem).toList());
        payload.put("shoppingItems", response.shoppingItems());
        sendCard(emitter, cancelled, conversationId, "menu-card", payload, "来自我的周菜单 · 刚刚查询");
        return new ToolExecution(payload,
                response.items().isEmpty() ? "本周还没有安排菜单" : "已找到 " + response.items().size() + " 个菜单安排");
    }

    private ToolExecution nutrition(SseEmitter emitter, AtomicBoolean cancelled, Long userId, Long conversationId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("healthNutrition", healthNutritionService.get(userId));
        payload.put("healthProfile", healthProfileService.get(userId));
        payload.put("dietPreference", dietPreferenceService.get(userId));
        payload.put("nutritionTarget", nutritionTargetService.get(userId));
        sendCard(emitter, cancelled, conversationId, "nutrition-card", payload, "来自我的健康档案与营养设置 · 刚刚查询");
        return new ToolExecution(payload, "已读取健康档案、饮食偏好和每日营养目标");
    }

    private ToolExecution savedRecipes(SseEmitter emitter, AtomicBoolean cancelled, Long userId, Long conversationId) {
        List<RecipeHistorySummaryResponse> recipes = savedRecipeService.list(userId, 8, 0);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("count", recipes.size());
        payload.put("items", recipes);
        sendCard(emitter, cancelled, conversationId, "recipe-list-card", payload, "来自我的菜谱收藏 · 刚刚查询");
        return new ToolExecution(payload, "已找到 " + recipes.size() + " 道已保存菜谱");
    }

    private ToolExecution recipe(
            SseEmitter emitter,
            AtomicBoolean cancelled,
            AuthPrincipal principal,
            Long conversationId,
            String userMessage,
            JsonNode arguments
    ) {
        List<PantryItemResponse> pantryItems = pantryService.list(principal.id());
        List<String> pantryNames = pantryItems.stream().map(PantryItemResponse::ingredientName).distinct().toList();
        PantryExpirySummaryResponse expirySummary = pantryService.expirySummary(principal.id());
        List<String> expiringNames = expirySummary.expiringSoonItems().stream()
                .map(PantryItemResponse::ingredientName).distinct().toList();
        String requested = textArgument(arguments, "request", userMessage);
        boolean useExpiring = arguments.path("prioritize_expiring").asBoolean(false)
                || arguments.path("prefer_expiring").asBoolean(false)
                || containsAny(requested.toLowerCase(Locale.ROOT), "快过期", "临期", "用它们", "用这些");
        String requestedIngredients = recipeIngredientArgument(arguments, requested);
        requestedIngredients = useExpiring && !expiringNames.isEmpty()
                ? String.join("、", expiringNames)
                : requestedIngredients;
        if (!StringUtils.hasText(requestedIngredients) && pantryNames.isEmpty()) {
            return new ToolExecution(
                    Map.of("status", "missing_ingredients", "message", "库存为空且用户没有提供食材"),
                    "缺少可用于生成菜谱的食材"
            );
        }
        DietPreferenceResponse preference = dietPreferenceService.get(principal.id());
        RecipeGenerateRequest request = new RecipeGenerateRequest(
                limit(requestedIngredients, 240),
                mealTypeArgument(arguments, requested),
                goalArgument(arguments, preference.defaultGoal()),
                "agent",
                null,
                null,
                dietPreference(preference),
                true,
                true
        );
        RecipeGenerateResponse recipe = recipeRecommendationService.generate(request, principal, null);
        sendRecipeCard(emitter, cancelled, conversationId, recipe, "来自我的食材库存与阿灶菜谱生成 · 刚刚生成");
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("status", "generated");
        output.put("usedInventoryCount", pantryNames.size());
        output.put("preferredExpiring", useExpiring && !expiringNames.isEmpty());
        output.put("recipe", recipe);
        return new ToolExecution(output, "已生成菜谱“" + recipe.title() + "”");
    }

    private RecipeGenerateRequest.DietPreference dietPreference(DietPreferenceResponse preference) {
        return new RecipeGenerateRequest.DietPreference(
                preference.taste(),
                preference.defaultGoal(),
                preference.avoidIngredients(),
                preference.allergenIngredients()
        );
    }

    private void sendRecipeCard(
            SseEmitter emitter,
            AtomicBoolean cancelled,
            Long conversationId,
            RecipeGenerateResponse recipe,
            String source
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("recipe", recipe);
        payload.put("inventoryMatch", inventoryMatch(recipe));
        sendCard(emitter, cancelled, conversationId, "recipe-card", payload, source);
    }

    private int inventoryMatch(RecipeGenerateResponse recipe) {
        int total = recipe.ingredients().size();
        if (total == 0) {
            return 0;
        }
        return Math.max(0, Math.min(100, (total - recipe.missingIngredients().size()) * 100 / total));
    }

    private RecipeGenerateResponse latestRecipe(Long userId, Long conversationId) {
        List<AgentMessage> messages = messageMapper.findRecentByBlockType(userId, conversationId, "recipe-card", 1);
        if (messages.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.readValue(messages.get(0).getContent(), RecipeGenerateResponse.class);
        } catch (JsonProcessingException exception) {
            return null;
        }
    }

    private Map<String, Object> pantryItem(PantryItemResponse item, LocalDate asOf) {
        String status = item.expireDate() == null ? "未设置到期日"
                : item.expireDate().isBefore(asOf) ? "已过期"
                : !item.expireDate().isAfter(asOf.plusDays(UserPantryService.EXPIRY_WARNING_DAYS)) ? "临期" : "正常";
        return pantryItem(item, status, asOf);
    }

    private Map<String, Object> pantryItem(PantryItemResponse item, String status, LocalDate asOf) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("name", item.ingredientName());
        value.put("quantity", item.quantity());
        value.put("unit", item.unit());
        value.put("expireDate", item.expireDate());
        value.put("status", status);
        value.put("asOf", asOf);
        return value;
    }

    private Map<String, Object> notificationItem(NotificationResponse item) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("type", item.type());
        value.put("title", item.title());
        value.put("summary", item.summary());
        value.put("createdAt", item.createdAt());
        value.put("status", item.status());
        return value;
    }

    private Map<String, Object> menuItem(WeeklyMenuItemResponse item) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("date", item.menuDate());
        value.put("mealType", item.mealType());
        value.put("recipe", item.recipeTitle());
        return value;
    }

    private void sendToolStarted(SseEmitter emitter, AtomicBoolean cancelled, AgentToolRegistry.Tool tool) {
        sendToolStarted(emitter, cancelled, tool.label(), tool.toolName());
    }

    private void sendToolStarted(SseEmitter emitter, AtomicBoolean cancelled, String label) {
        sendToolStarted(emitter, cancelled, label, "agent.orchestrator");
    }

    private void sendToolStarted(SseEmitter emitter, AtomicBoolean cancelled, String label, String tool) {
        sendOrCancel(emitter, cancelled, "tool.started", Map.of("tool", tool, "label", label));
    }

    private void sendToolStatus(SseEmitter emitter, AtomicBoolean cancelled, String label) {
        sendToolStarted(emitter, cancelled, label, "agent.write");
    }

    private void sendToolResult(SseEmitter emitter, AtomicBoolean cancelled, AgentToolRegistry.Tool tool, String summary) {
        sendToolResult(emitter, cancelled, tool.toolName(), summary);
    }

    private void sendToolResult(SseEmitter emitter, AtomicBoolean cancelled, String tool, String summary) {
        sendOrCancel(emitter, cancelled, "tool.result", Map.of("tool", tool, "summary", summary));
    }

    private void sendText(SseEmitter emitter, AtomicBoolean cancelled, Long conversationId, String text) {
        sendOrCancel(emitter, cancelled, "message.delta", Map.of("content", text));
        saveMessage(null, conversationId, ROLE_ASSISTANT, "text", text);
    }

    private void sendCard(
            SseEmitter emitter,
            AtomicBoolean cancelled,
            Long conversationId,
            String cardType,
            Object payload,
            String source
    ) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("cardType", cardType);
        event.put("payload", payload);
        event.put("source", source);
        sendOrCancel(emitter, cancelled, "card", event);
        saveMessage(null, conversationId, ROLE_ASSISTANT, cardType, payloadForMessage(cardType, payload));
    }

    private String payloadForMessage(String cardType, Object payload) {
        Object value = payload;
        if ("recipe-card".equals(cardType) && payload instanceof Map<?, ?> map) {
            value = map.get("recipe");
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("助手卡片保存失败", exception);
        }
    }

    private void saveMessage(Long ignoredUserId, Long conversationId, String role, String blockType, String content) {
        AgentMessage message = new AgentMessage();
        message.setConversationId(conversationId);
        AgentConversation conversation = conversationMapper.selectById(conversationId);
        message.setUserId(conversation == null ? ignoredUserId : conversation.getUserId());
        message.setRole(role);
        message.setBlockType(blockType);
        message.setContent(limit(content, 100_000));
        message.setCreatedAt(LocalDateTime.now());
        messageMapper.insert(message);
    }

    private void sendOrCancel(SseEmitter emitter, AtomicBoolean cancelled, String event, Object data) {
        if (!send(emitter, cancelled, event, data)) {
            throw new StreamCancelledException();
        }
    }

    private boolean send(SseEmitter emitter, AtomicBoolean cancelled, String event, Object data) {
        if (cancelled.get()) {
            return false;
        }
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
            return true;
        } catch (IOException | IllegalStateException exception) {
            cancelled.set(true);
            return false;
        }
    }

    private String normalizedMessage(String value, AgentAttachment attachment) {
        if (!StringUtils.hasText(value)) {
            if (attachment != null) {
                return "请识别这张图片中的食材，并告诉我可以怎么处理";
            }
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请先输入想问的内容");
        }
        return limit(value.trim(), MAX_MESSAGE_LENGTH);
    }

    private void validateRequest(AgentChatRequest request, AgentAttachment attachment) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "助手请求不能为空");
        }
        if (request.confirmationId() == null && !StringUtils.hasText(request.message()) && attachment == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请先输入想问的内容");
        }
    }

    private void requireUser(AuthPrincipal principal) {
        if (principal == null || principal.role() != AppRole.USER) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录普通用户账号");
        }
    }

    private boolean containsAny(String value, String... terms) {
        for (String term : terms) {
            if (value.contains(term)) {
                return true;
            }
        }
        return false;
    }

    private String textArgument(JsonNode arguments, String field, String fallback) {
        JsonNode value = arguments.path(field);
        if (!value.isTextual() || !StringUtils.hasText(value.textValue())) {
            return fallback == null ? "" : fallback;
        }
        return limit(value.textValue().trim(), 240);
    }

    static String recipeIngredientArgument(JsonNode arguments, String fallback) {
        JsonNode ingredients = arguments == null ? null : arguments.path("ingredients");
        if (ingredients != null && ingredients.isArray()) {
            List<String> names = new ArrayList<>();
            for (JsonNode ingredient : ingredients) {
                if (ingredient != null && ingredient.isTextual() && StringUtils.hasText(ingredient.textValue())) {
                    names.add(ingredient.textValue().trim());
                }
            }
            if (!names.isEmpty()) {
                return limit(String.join("、", names), 240);
            }
        }
        return fallback == null ? "" : limit(fallback.trim(), 240);
    }

    private String mealTypeArgument(JsonNode arguments, String fallbackText) {
        String value = arguments.path("meal_type").asText("").trim().toLowerCase(Locale.ROOT);
        if ("breakfast".equals(value) || "lunch".equals(value) || "dinner".equals(value)) {
            return value;
        }
        return mealType(fallbackText == null ? "" : fallbackText);
    }

    private String goalArgument(JsonNode arguments, String fallback) {
        String value = arguments.path("goal").asText("").trim().toLowerCase(Locale.ROOT);
        if (Set.of("balanced", "fat_loss", "muscle_gain", "low_sugar").contains(value)) {
            return value;
        }
        return Set.of("balanced", "fat_loss", "muscle_gain", "low_sugar").contains(fallback)
                ? fallback
                : "balanced";
    }

    private String mealType(String message) {
        if (message.contains("早餐")) return "breakfast";
        if (message.contains("午餐")) return "lunch";
        return "dinner";
    }

    private String errorMessage(Throwable exception) {
        Throwable current = exception;
        while (current.getCause() != null && !(current instanceof ResponseStatusException)) {
            current = current.getCause();
        }
        if (current instanceof ResponseStatusException status && status.getReason() != null) {
            return status.getReason();
        }
        return "小厨灵暂时没有完成这次操作，请点击重试；如果是菜谱生成，请检查 AI 服务配置。";
    }

    private static String limit(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private record ToolExecution(Object output, String summary, boolean confirmationRequested) {
        private ToolExecution(Object output, String summary) {
            this(output, summary, false);
        }
    }

    private static final class StreamCancelledException extends RuntimeException {
    }
}
