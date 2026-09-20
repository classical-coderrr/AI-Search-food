package com.example.food.agent;

import com.example.food.ai.recipe.RecipeRecommendationService;
import com.example.food.ai.recipe.dto.RecipeGenerateRequest;
import com.example.food.ai.recipe.dto.RecipeGenerateResponse;
import com.example.food.ai.ingredient.IngredientRecognitionService;
import com.example.food.ai.ingredient.dto.IngredientRecognitionResponse;
import com.example.food.ai.qwen.QwenAgentClient;
import com.example.food.agent.dto.AgentChatRequest;
import com.example.food.agent.dto.AgentEventResponse;
import com.example.food.agent.dto.AgentRunStatusResponse;
import com.example.food.agent.dto.AgentConversationHistoryResponse;
import com.example.food.agent.dto.AgentConfirmationStatusResponse;
import com.example.food.agent.dto.AgentMessageResponse;
import com.example.food.agent.dto.AgentWriteOperationStatusResponse;
import com.example.food.agent.state.AgentCheckpoint;
import com.example.food.agent.state.AgentEvent;
import com.example.food.agent.state.AgentEventStore;
import com.example.food.agent.state.InMemoryAgentEventStore;
import com.example.food.agent.state.AgentFaultInjector;
import com.example.food.agent.state.AgentNode;
import com.example.food.agent.state.AgentRun;
import com.example.food.agent.state.AgentRunStore;
import com.example.food.agent.state.AgentStep;
import com.example.food.agent.state.AgentState;
import com.example.food.agent.state.AgentStatus;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class AgentService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgentService.class);

    private static final long STREAM_TIMEOUT_MILLIS = 120_000L;
    private static final int MAX_MESSAGE_LENGTH = 1000;
    private static final int MAX_AGENT_ROUNDS = 5;
    private static final int MAX_TOOL_CALLS = 8;
    private static final int HISTORY_MESSAGE_LIMIT = 12;
    private static final Duration RUN_LEASE_DURATION = Duration.ofMinutes(10);
    private static final String ROLE_USER = "USER";
    private static final String ROLE_ASSISTANT = "ASSISTANT";
    private static final String CONFIRMATION_PENDING = "PENDING";
    private static final String CONFIRMATION_PROCESSING = "PROCESSING";
    private static final String CONFIRMATION_CONFIRMED = "CONFIRMED";
    private static final String CONFIRMATION_UNKNOWN_REVIEW = "UNKNOWN_REVIEW";
    private static final String CONFIRMATION_FAILED = "FAILED";

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
    private final AgentIntentRecognizer intentRecognizer;
    private final AgentIntentAuditService intentAuditService;
    private final ObjectMapper objectMapper;
    private final AgentRunStore runStore;
    private final AgentEventStore eventStore;
    private final AgentMetrics metrics;
    private final AgentFaultInjector faultInjector;
    private final ThreadLocal<String> activeRunId = new ThreadLocal<>();
    private final ExecutorService workerExecutor = Executors.newCachedThreadPool(
            runnable -> {
                Thread thread = new Thread(runnable, "kitchen-agent-" + System.nanoTime());
                thread.setDaemon(true);
                return thread;
            }
    );

    /**
     * Compatibility constructor used by focused unit tests and integrations
     * that instantiate the service directly. Normal application wiring uses
     * the fault-injectable constructor below.
     */
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
            AgentIntentRecognizer intentRecognizer,
            AgentIntentAuditService intentAuditService,
            ObjectMapper objectMapper,
            AgentRunStore runStore
    ) {
        this(
                conversationMapper,
                messageMapper,
                confirmationMapper,
                toolRegistry,
                kitchenToolService,
                writeService,
                ingredientRecognitionService,
                pantryService,
                notificationService,
                weeklyMenuService,
                savedRecipeService,
                healthProfileService,
                nutritionTargetService,
                healthNutritionService,
                dietPreferenceService,
                recipeRecommendationService,
                qwenAgentClient,
                intentRecognizer,
                intentAuditService,
                objectMapper,
                runStore,
                new AgentFaultInjector(),
                new InMemoryAgentEventStore(objectMapper),
                AgentMetrics.disabled()
        );
    }

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
            AgentIntentRecognizer intentRecognizer,
            AgentIntentAuditService intentAuditService,
            ObjectMapper objectMapper,
            AgentRunStore runStore,
            AgentFaultInjector faultInjector
    ) {
        this(
                conversationMapper,
                messageMapper,
                confirmationMapper,
                toolRegistry,
                kitchenToolService,
                writeService,
                ingredientRecognitionService,
                pantryService,
                notificationService,
                weeklyMenuService,
                savedRecipeService,
                healthProfileService,
                nutritionTargetService,
                healthNutritionService,
                dietPreferenceService,
                recipeRecommendationService,
                qwenAgentClient,
                intentRecognizer,
                intentAuditService,
                objectMapper,
                runStore,
                faultInjector,
                new InMemoryAgentEventStore(objectMapper),
                AgentMetrics.disabled()
        );
    }

    @Autowired
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
            AgentIntentRecognizer intentRecognizer,
            AgentIntentAuditService intentAuditService,
            ObjectMapper objectMapper,
            AgentRunStore runStore,
            AgentFaultInjector faultInjector,
            AgentEventStore eventStore,
            AgentMetrics metrics
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
        this.intentRecognizer = intentRecognizer;
        this.intentAuditService = intentAuditService;
        this.objectMapper = objectMapper;
        this.runStore = runStore;
        this.eventStore = eventStore == null ? new InMemoryAgentEventStore(objectMapper) : eventStore;
        this.metrics = metrics == null ? AgentMetrics.disabled() : metrics;
        this.faultInjector = faultInjector == null ? new AgentFaultInjector() : faultInjector;
    }

    public SseEmitter stream(AgentChatRequest request, AuthPrincipal principal) {
        return stream(request, principal, null);
    }

    public SseEmitter stream(AgentChatRequest request, AuthPrincipal principal, MultipartFile image) {
        requireUser(principal);
        AgentAttachment attachment = AgentAttachment.from(image);
        validateRequest(request, attachment);
        String runId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        boolean recoverable = attachment == null;
        AgentRun initialRun = new AgentRun(
                runId,
                principal.id(),
                request.conversationId(),
                AgentStatus.RUNNING,
                AgentNode.CONVERSATION,
                AgentNode.CONVERSATION,
                recoverable,
                now,
                now,
                now,
                null,
                null
        );
        AgentState initialState = new AgentState(
                runId,
                principal.id(),
                request.conversationId(),
                request.message() == null ? "" : request.message().trim(),
                attachment != null,
                AgentStatus.RUNNING,
                AgentNode.CONVERSATION,
                AgentNode.CONVERSATION,
                0,
                0,
                0,
                false,
                List.of(),
                List.of(),
                0,
                null,
                now
        );
        runStore.create(initialRun, new AgentCheckpoint(runId, 0, initialState, now));
        metrics.runStarted();
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MILLIS);
        AtomicBoolean cancelled = new AtomicBoolean(false);
        Future<?> worker = workerExecutor.submit(() -> run(runId, emitter, cancelled, request, principal, attachment, null));
        // Closing an SSE connection must not cancel the durable Agent run. The
        // worker continues and persists events so a later reconnect can replay
        // them from Redis. Explicit cancellation is handled by the run state,
        // not by a transient browser connection.
        emitter.onCompletion(() -> { });
        emitter.onTimeout(() -> { });
        emitter.onError(error -> { });
        return emitter;
    }

    /**
     * Resume a stale run from its latest checkpoint. This method is called by
     * AgentRecoveryService without an HTTP connection; events are persisted by
     * the normal message flow and the next client request can inspect the run.
     */
    public void resume(String runId, String owner) {
        AgentRun run = runStore.findRun(runId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent 运行不存在"));
        AgentCheckpoint checkpoint = runStore.findCheckpoint(runId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Agent 缺少可恢复状态"));
        AgentState state = checkpoint.state();
        if (!run.recoverable() || state.hasAttachment()) {
            markFailed(runId, "ATTACHMENT_NOT_RECOVERABLE", "包含图片附件的任务无法在服务重启后自动恢复");
            return;
        }
        if (state.status() == AgentStatus.WAITING_CONFIRMATION
                || state.nextNode() == AgentNode.WAITING_CONFIRMATION) {
            updateRun(runId, AgentStatus.WAITING_CONFIRMATION, AgentNode.WAITING_CONFIRMATION,
                    AgentNode.WAITING_CONFIRMATION, null);
            return;
        }
        if (state.status() == AgentStatus.COMPLETED
                || state.status() == AgentStatus.FAILED
                || state.status() == AgentStatus.CANCELLED) {
            return;
        }
        if (state.currentNode() == AgentNode.FINALIZE || state.nextNode() == AgentNode.FINALIZE) {
            resumeFinalize(runId, state);
            return;
        }
        AuthPrincipal principal = new AuthPrincipal(state.userId(), "agent-recovery", AppRole.USER);
        AgentExecution execution = AgentExecution.from(state);
        AtomicBoolean cancelled = new AtomicBoolean(false);
        Instant recoveryStartedAt = Instant.now();
        metrics.runRecovered();
        activeRunId.set(runId);
        try {
            if (execution.conversationId == null) {
                AgentConversation conversation = newConversation(state.userId());
                execution.conversationId = conversation.getId();
                if (execution.messages.isEmpty()) {
                    execution.messages.add(QwenAgentClient.ConversationMessage.user(execution.userMessage));
                    saveMessage(state.userId(), conversation.getId(), ROLE_USER, "text", execution.userMessage);
                }
                persistCheckpoint(execution, AgentStatus.RECOVERING, AgentNode.CONVERSATION,
                        AgentNode.MODEL_DECISION, null);
            }
            updateRun(runId, AgentStatus.RECOVERING, state.currentNode(), state.nextNode(), null);
            driveAgent(null, cancelled, principal, execution.conversationId, execution, null);
            if (execution.confirmationRequested) {
                updateRun(runId, AgentStatus.WAITING_CONFIRMATION, AgentNode.WAITING_CONFIRMATION,
                        AgentNode.WAITING_CONFIRMATION, null);
            } else {
                updateRun(runId, AgentStatus.COMPLETED, AgentNode.FINALIZE, AgentNode.FINALIZE, null);
                metrics.runCompleted(Duration.between(recoveryStartedAt, Instant.now()));
            }
        } catch (AgentFaultInjector.AgentCrashException crash) {
            // A real process crash does not get converted to FAILED. Keeping
            // the last checkpoint lets the next instance resume it.
            throw crash;
        } catch (Throwable exception) {
            if (!cancelled.get() && !Thread.currentThread().isInterrupted()) {
                markFailed(runId, "RECOVERY_FAILED", errorMessage(exception));
                metrics.runFailed(Duration.between(recoveryStartedAt, Instant.now()));
            }
        } finally {
            activeRunId.remove();
        }
    }

    private void resumeFinalize(String runId, AgentState state) {
        AgentExecution execution = AgentExecution.from(state);
        String content = lastAssistantContent(state.messages());
        if (StringUtils.hasText(content)
                && !assistantMessageExists(state.userId(), state.conversationId(), content)) {
            saveMessage(state.userId(), state.conversationId(), ROLE_ASSISTANT, "text", content);
        }
        persistCheckpoint(execution, AgentStatus.COMPLETED, AgentNode.FINALIZE, AgentNode.FINALIZE, null);
        updateRun(runId, AgentStatus.COMPLETED, AgentNode.FINALIZE, AgentNode.FINALIZE, null);
    }

    private String lastAssistantContent(List<QwenAgentClient.ConversationMessage> messages) {
        if (messages == null) {
            return "";
        }
        for (int index = messages.size() - 1; index >= 0; index--) {
            QwenAgentClient.ConversationMessage message = messages.get(index);
            if (message != null
                    && "assistant".equalsIgnoreCase(message.role())
                    && (message.toolCalls() == null || message.toolCalls().isEmpty())
                    && StringUtils.hasText(message.content())) {
                return message.content();
            }
        }
        return "";
    }

    private boolean assistantMessageExists(Long userId, Long conversationId, String content) {
        if (messageMapper == null || userId == null || conversationId == null) {
            return false;
        }
        return messageMapper.findRecentByBlockType(userId, conversationId, "text", 20).stream()
                .anyMatch(message -> ROLE_ASSISTANT.equals(message.getRole())
                        && Objects.equals(message.getContent(), content));
    }

    public void deleteConversation(Long userId, Long conversationId) {
        if (conversationMapper.deleteOwned(userId, conversationId) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在");
        }
    }

    public AgentConversationHistoryResponse latestConversationHistory(Long userId) {
        AgentConversation conversation = conversationMapper.findLatest(userId);
        return conversation == null ? null : conversationHistoryResponse(userId, conversation);
    }

    public AgentConversationHistoryResponse conversationHistoryDetails(Long userId, Long conversationId) {
        AgentConversation conversation = conversationMapper.findOwned(userId, conversationId);
        if (conversation == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在");
        }
        return conversationHistoryResponse(userId, conversation);
    }

    private AgentConversationHistoryResponse conversationHistoryResponse(Long userId, AgentConversation conversation) {
        List<AgentMessageResponse> messages = messageMapper.findRecentMessages(
                        userId, conversation.getId(), 80
                ).stream()
                .map(message -> new AgentMessageResponse(
                        message.getId(),
                        message.getRole(),
                        message.getBlockType(),
                        message.getContent(),
                        message.getCreatedAt()
                ))
                .toList();
        AgentRunStatusResponse activeRun = runStore.findActiveForConversation(userId, conversation.getId())
                .map(run -> new AgentRunStatusResponse(
                        run.runId(),
                        run.conversationId(),
                        run.status(),
                        run.currentNode(),
                        run.nextNode(),
                        run.errorCode(),
                        run.errorMessage(),
                        run.updatedAt()
                ))
                .orElse(null);
        return new AgentConversationHistoryResponse(
                conversation.getId(),
                conversation.getTitle(),
                conversation.getCreatedAt(),
                conversation.getUpdatedAt(),
                messages,
                activeRun
        );
    }

    private void run(
            String runId,
            SseEmitter emitter,
            AtomicBoolean cancelled,
            AgentChatRequest request,
            AuthPrincipal principal,
            AgentAttachment attachment,
            String existingLeaseOwner
    ) {
        String leaseOwner = existingLeaseOwner == null ? runId + ":" + UUID.randomUUID() : existingLeaseOwner;
        if (existingLeaseOwner == null && !runStore.tryAcquireLease(runId, leaseOwner, RUN_LEASE_DURATION)) {
            return;
        }
        activeRunId.set(runId);
        Instant startedAt = Instant.now();
        AgentConversation conversation = null;
        try {
            conversation = conversation(request, principal.id());
            updateRun(runId, AgentStatus.RUNNING, AgentNode.CONVERSATION, AgentNode.CONVERSATION, null,
                    conversation.getId());
            sendOrCancel(emitter, cancelled, "conversation.ready", Map.of(
                    "conversationId", conversation.getId(),
                    "runId", runId
            ));

            if (request.confirmationId() != null) {
                handleConfirmation(emitter, cancelled, principal, conversation, request);
                updateRun(runId, AgentStatus.COMPLETED, AgentNode.FINALIZE, AgentNode.FINALIZE, null);
                metrics.runCompleted(Duration.between(startedAt, Instant.now()));
                return;
            }

            String message = normalizedMessage(request.message(), attachment);
            String modelMessage = imageContext(emitter, cancelled, conversation.getId(), message, attachment);
            saveMessage(principal.id(), conversation.getId(), ROLE_USER, "text", modelMessage);
            AgentExecution execution = new AgentExecution(
                    runId,
                    principal.id(),
                    conversation.getId(),
                    modelMessage,
                    attachment != null,
                    conversationHistory(principal.id(), conversation.getId()),
                    0,
                    0,
                    0,
                    false,
                    List.of(),
                    0,
                    AgentNode.MODEL_DECISION
            );
            if (execution.messages.isEmpty()) {
                execution.messages.add(QwenAgentClient.ConversationMessage.user(modelMessage));
            }
            persistCheckpoint(execution, AgentStatus.RUNNING, AgentNode.CONVERSATION, AgentNode.MODEL_DECISION, null);
            driveAgent(emitter, cancelled, principal, conversation.getId(), execution, attachment);
            conversationMapper.touch(principal.id(), conversation.getId());
            if (execution.confirmationRequested) {
                updateRun(runId, AgentStatus.WAITING_CONFIRMATION, AgentNode.WAITING_CONFIRMATION,
                        AgentNode.WAITING_CONFIRMATION, null);
            } else {
                updateRun(runId, AgentStatus.COMPLETED, AgentNode.FINALIZE, AgentNode.FINALIZE, null);
                metrics.runCompleted(Duration.between(startedAt, Instant.now()));
            }
            sendOrCancel(emitter, cancelled, "done", Map.of("conversationId", conversation.getId(), "runId", runId));
            completeEmitter(emitter);
        } catch (AgentFaultInjector.AgentCrashException crash) {
            // Leave the durable checkpoint untouched so the recovery scanner
            // can pick the run up after the next process starts.
            throw crash;
        } catch (Throwable exception) {
            if (cancelled.get() || Thread.currentThread().isInterrupted()) {
                updateRun(runId, AgentStatus.CANCELLED, AgentNode.FINALIZE, AgentNode.FINALIZE, null);
                metrics.runFailed(Duration.between(startedAt, Instant.now()));
                return;
            }
            LOGGER.error("Agent run failed, runId={}, userId={}, conversationId={}",
                    runId, principal.id(), conversation == null ? null : conversation.getId(), exception);
            String message = errorMessage(exception);
            markFailed(runId, "AGENT_RUN_FAILED", message);
            metrics.runFailed(Duration.between(startedAt, Instant.now()));
            if (conversation != null) {
                try {
                    saveMessage(principal.id(), conversation.getId(), ROLE_ASSISTANT, "text", message);
                } catch (RuntimeException ignored) {
                    // The stream error remains the source of truth for the client.
                }
            }
            send(emitter, cancelled, "error", Map.of("message", message));
            completeEmitter(emitter);
        } finally {
            if (existingLeaseOwner == null) {
                runStore.releaseLease(runId, leaseOwner);
            }
            activeRunId.remove();
        }
    }

    public AgentRunStatusResponse runStatus(Long userId, String runId) {
        AgentRun run = runStore.findRun(runId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent 运行不存在"));
        if (!Objects.equals(userId, run.userId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent 运行不存在");
        }
        return new AgentRunStatusResponse(
                run.runId(),
                run.conversationId(),
                run.status(),
                run.currentNode(),
                run.nextNode(),
                run.errorCode(),
                run.errorMessage(),
                run.updatedAt()
        );
    }

    public List<AgentEventResponse> eventHistory(Long userId, String runId, long afterEventSeq) {
        runStatus(userId, runId);
        return eventStore.findAfter(runId, Math.max(0L, afterEventSeq), 1000).stream()
                .map(this::eventResponse)
                .toList();
    }

    /**
     * Replays persisted events and keeps polling until the run reaches a
     * terminal state. A reconnect therefore receives the missed events first
     * and then any events produced while the new SSE connection is open.
     */
    public SseEmitter replayEvents(Long userId, String runId, long afterEventSeq) {
        runStatus(userId, runId);
        SseEmitter emitter = new SseEmitter(30_000L);
        AtomicBoolean stopped = new AtomicBoolean(false);
        Future<?> worker = workerExecutor.submit(() -> {
            long lastSequence = Math.max(0L, afterEventSeq);
            Instant deadline = Instant.now().plusSeconds(25);
            try {
                while (!stopped.get() && Instant.now().isBefore(deadline)) {
                    List<AgentEvent> pending = eventStore.findAfter(runId, lastSequence, 1000);
                    for (AgentEvent event : pending) {
                        if (stopped.get()) {
                            return;
                        }
                        if (!sendStoredEvent(emitter, event)) {
                            stopped.set(true);
                            return;
                        }
                        metrics.eventReplayed();
                        lastSequence = Math.max(lastSequence, event.sequence());
                    }
                    AgentRun run = runStore.findRun(runId).orElse(null);
                    if (run == null || isTerminal(run.status())) {
                        if (pending.isEmpty()) {
                            completeEmitter(emitter);
                            return;
                        }
                    }
                    Thread.sleep(300L);
                }
                if (!stopped.get()) {
                    completeEmitter(emitter);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (Throwable exception) {
                if (!stopped.get()) {
                    emitter.completeWithError(exception);
                }
            }
        });
        Runnable stop = () -> {
            stopped.set(true);
            worker.cancel(true);
        };
        emitter.onCompletion(stop);
        emitter.onTimeout(stop);
        emitter.onError(error -> stop.run());
        return emitter;
    }

    private boolean sendStoredEvent(SseEmitter emitter, AgentEvent event) {
        try {
            JsonNode data = objectMapper.readTree(event.dataJson());
            emitter.send(SseEmitter.event()
                    .id(Long.toString(event.sequence()))
                    .name(event.event())
                    .data(data));
            return true;
        } catch (IOException | IllegalStateException exception) {
            return false;
        }
    }

    private AgentEventResponse eventResponse(AgentEvent event) {
        try {
            return new AgentEventResponse(
                    event.runId(),
                    event.sequence(),
                    event.event(),
                    objectMapper.readTree(event.dataJson()),
                    event.createdAt()
            );
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Agent SSE 事件数据解析失败", exception);
        }
    }

    private boolean isTerminal(AgentStatus status) {
        return status == AgentStatus.COMPLETED
                || status == AgentStatus.FAILED
                || status == AgentStatus.CANCELLED;
    }

    public AgentConfirmationStatusResponse confirmationStatus(AuthPrincipal principal, Long confirmationId) {
        requireUser(principal);
        return writeService.status(principal, confirmationId);
    }

    public AgentWriteOperationStatusResponse operationStatus(AuthPrincipal principal, String idempotencyKey) {
        requireUser(principal);
        return writeService.operationStatus(principal, idempotencyKey);
    }

    private void driveAgent(
            SseEmitter emitter,
            AtomicBoolean cancelled,
            AuthPrincipal principal,
            Long conversationId,
            AgentExecution execution,
            AgentAttachment attachment
    ) {
        sendToolStarted(emitter, cancelled, "小厨灵正在理解你的需求");

        while (true) {
            if (execution.nextNode == AgentNode.TOOL_EXECUTE && !execution.pendingToolCalls.isEmpty()) {
                // A restart may leave a tool call checkpointed but not yet observed.
                // Resume that call before asking the model for a new decision.
            } else {
                if (execution.round >= MAX_AGENT_ROUNDS) {
                    throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "小厨灵尚未完成工具调用，请缩小问题范围后重试");
                }
                execution.currentNode = AgentNode.MODEL_DECISION;
                execution.nextNode = AgentNode.MODEL_DECISION;
                persistCheckpoint(execution, AgentStatus.RUNNING, AgentNode.MODEL_DECISION, AgentNode.MODEL_DECISION, null);
                List<Map<String, Object>> definitions = toolDefinitions(execution);
                String modelRequestJson = toolOutput(Map.of(
                        "round", execution.round + 1,
                        "messageCount", execution.messages.size(),
                        "toolCount", definitions.size()
                ));
                persistStep(execution, AgentNode.MODEL_DECISION, "model.started", "agent.model",
                        "RUNNING", modelRequestJson, null, null, null);
                faultInjector.hit(execution.runId, AgentFaultInjector.Point.MODEL_BEFORE);
                QwenAgentClient.AgentTurn turn;
                try {
                    turn = qwenAgentClient.complete(execution.messages, definitions);
                } catch (Throwable exception) {
                    persistStep(execution, AgentNode.MODEL_DECISION, "model.result", "agent.model",
                            "FAILED", modelRequestJson, null, null, errorMessage(exception));
                    throw exception;
                }
                execution.round++;
                execution.messages.add(QwenAgentClient.ConversationMessage.assistant(turn));
                execution.pendingToolCalls = turn.toolCalls();
                execution.pendingToolIndex = 0;
                persistStep(execution, AgentNode.MODEL_DECISION, "model.result", "agent.model",
                        "SUCCESS", modelRequestJson, modelResponseJson(turn), null, null);
                if (turn.toolCalls().isEmpty()) {
                    execution.currentNode = AgentNode.MODEL_DECISION;
                    execution.nextNode = AgentNode.FINALIZE;
                    persistCheckpoint(execution, AgentStatus.RUNNING, AgentNode.MODEL_DECISION, AgentNode.FINALIZE, null);
                    faultInjector.hit(execution.runId, AgentFaultInjector.Point.MODEL_AFTER);
                    faultInjector.hit(execution.runId, AgentFaultInjector.Point.FINALIZE_BEFORE);
                    sendText(emitter, cancelled, conversationId, limit(turn.content(), 12_000));
                    persistCheckpoint(execution, AgentStatus.COMPLETED, AgentNode.FINALIZE, AgentNode.FINALIZE, null);
                    faultInjector.hit(execution.runId, AgentFaultInjector.Point.FINALIZE_AFTER);
                    return;
                }
                execution.currentNode = AgentNode.TOOL_EXECUTE;
                execution.nextNode = AgentNode.TOOL_EXECUTE;
                persistCheckpoint(execution, AgentStatus.RUNNING, AgentNode.MODEL_DECISION, AgentNode.TOOL_EXECUTE, null);
                faultInjector.hit(execution.runId, AgentFaultInjector.Point.MODEL_AFTER);
            }

            while (execution.pendingToolIndex < execution.pendingToolCalls.size()) {
                QwenAgentClient.ToolCall call = execution.pendingToolCalls.get(execution.pendingToolIndex);
                execution.toolCallCount++;
                if (execution.toolCallCount > MAX_TOOL_CALLS) {
                    throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "小厨灵调用工具次数过多，请简化问题后重试");
                }
                AgentToolRegistry.Tool tool = toolRegistry.requireFunction(call.name());
                JsonNode arguments = toolArguments(call.arguments());
                String requestJson = toolOutput(arguments);
                persistStep(execution, AgentNode.TOOL_EXECUTE, "tool.started", tool.toolName(),
                        "RUNNING", requestJson, null, call.id(), null);
                persistCheckpoint(execution, AgentStatus.RUNNING, AgentNode.TOOL_EXECUTE, AgentNode.TOOL_EXECUTE, null);
                faultInjector.hit(execution.runId, AgentFaultInjector.Point.TOOL_BEFORE);
                sendToolStarted(emitter, cancelled, tool);
                ToolExecution toolExecution;
                if ((tool == AgentToolRegistry.Tool.RECIPE_SAVE || kitchenToolService.isMutation(tool, arguments))
                        && execution.confirmationRequested) {
                    toolExecution = new ToolExecution(
                            Map.of("status", "confirmation_already_requested"),
                            "本轮已发起一项操作确认"
                    );
                } else if (kitchenToolService.isMutation(tool, arguments)) {
                    toolExecution = requestActionConfirmation(
                            emitter, cancelled, principal.id(), conversationId, tool, arguments);
                } else {
                    toolExecution = executeTool(
                            tool, arguments, emitter, cancelled, principal, conversationId, execution.userMessage, attachment);
                }
                execution.confirmationRequested = execution.confirmationRequested || toolExecution.confirmationRequested();
                sendToolResult(emitter, cancelled, tool, toolExecution.summary());
                String output = toolOutput(toolExecution.output());
                execution.messages.add(QwenAgentClient.ConversationMessage.tool(call.id(), output));
                persistStep(execution, AgentNode.OBSERVE, "tool.result", tool.toolName(),
                        "SUCCESS", requestJson, output, call.id(), null);
                execution.pendingToolIndex++;
                execution.currentNode = AgentNode.OBSERVE;
                execution.nextNode = AgentNode.MODEL_DECISION;
                persistCheckpoint(execution, execution.confirmationRequested
                        ? AgentStatus.WAITING_CONFIRMATION : AgentStatus.RUNNING,
                        AgentNode.OBSERVE,
                        execution.confirmationRequested ? AgentNode.WAITING_CONFIRMATION : AgentNode.MODEL_DECISION,
                        null);
                faultInjector.hit(execution.runId, AgentFaultInjector.Point.TOOL_AFTER);
                if (execution.confirmationRequested) {
                    faultInjector.hit(execution.runId, AgentFaultInjector.Point.WAITING_CONFIRMATION);
                }
            }
            execution.pendingToolCalls = List.of();
            execution.pendingToolIndex = 0;
            if (execution.confirmationRequested) {
                return;
            }
        }
    }

    private List<Map<String, Object>> toolDefinitions(AgentExecution execution) {
        List<Map<String, Object>> definitions = toolRegistry.functionDefinitions(
                execution.userMessage,
                execution.hasAttachment
        );
        boolean saveToolAlreadyExposed = containsFunction(definitions, AgentToolRegistry.Tool.RECIPE_SAVE.functionName());
        if (execution.intentResolutionAttempted) {
            if (execution.recipeSaveIntent && !saveToolAlreadyExposed) {
                List<Map<String, Object>> enriched = new ArrayList<>(definitions);
                enriched.add(toolRegistry.functionDefinition(AgentToolRegistry.Tool.RECIPE_SAVE));
                return List.copyOf(enriched);
            }
            return definitions;
        }
        if (saveToolAlreadyExposed) {
            execution.intentResolutionAttempted = true;
            execution.recipeSaveIntent = true;
            execution.intentResolutionSource = AgentIntentAuditService.SOURCE_RULE;
            persistIntentResolution(
                    execution,
                    AgentIntentAuditService.SOURCE_RULE,
                    "SAVE_RECIPE",
                    1d,
                    "LATEST_GENERATED",
                    "规则命中保存动作与本次菜谱指代",
                    true,
                    false
            );
            return definitions;
        }
        AgentIntentRecognizer.RecognitionResult result = intentRecognizer.recognize(
                execution.userMessage,
                execution.messages
        );
        execution.intentResolutionAttempted = true;
        execution.recipeSaveIntent = result.isSaveRecipe();
        execution.intentResolutionSource = result.auditSource();
        persistIntentResolution(
                execution,
                result.auditSource(),
                result.intent(),
                result.confidence(),
                result.recipeReference(),
                result.reason(),
                result.isSaveRecipe(),
                result.modelCalled()
        );
        if (!result.isSaveRecipe()) {
            return definitions;
        }
        List<Map<String, Object>> enriched = new ArrayList<>(definitions);
        enriched.add(toolRegistry.functionDefinition(AgentToolRegistry.Tool.RECIPE_SAVE));
        return List.copyOf(enriched);
    }

    private void persistIntentResolution(
            AgentExecution execution,
            String source,
            String intent,
            double confidence,
            String recipeReference,
            String reason,
            boolean saveToolExposed,
            boolean modelCalled
    ) {
        intentAuditService.record(
                execution.runId,
                ++execution.stepNo,
                source,
                intent,
                confidence,
                recipeReference,
                reason,
                saveToolExposed,
                modelCalled,
                execution.userMessage.length(),
                execution.hasAttachment
        );
    }

    private boolean containsFunction(List<Map<String, Object>> definitions, String functionName) {
        for (Map<String, Object> definition : definitions) {
            Object function = definition == null ? null : definition.get("function");
            if (function instanceof Map<?, ?> functionMap
                    && functionName.equals(String.valueOf(functionMap.get("name")))) {
                return true;
            }
        }
        return false;
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

    private String modelResponseJson(QwenAgentClient.AgentTurn turn) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("provider", turn.provider());
        response.put("model", turn.model());
        response.put("toolCallCount", turn.toolCalls() == null ? 0 : turn.toolCalls().size());
        response.put("contentLength", turn.content() == null ? 0 : turn.content().length());
        return toolOutput(response);
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
        completeEmitter(emitter);
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
        String idempotencyKey = recipeSaveIdempotencyKey(recipe);
        AgentConfirmation existing = confirmationMapper.findOwnedByIdempotencyKey(userId, idempotencyKey);
        if (existing != null && !CONFIRMATION_FAILED.equals(existing.getStatus())
                && !CONFIRMATION_UNKNOWN_REVIEW.equals(existing.getStatus())) {
            return duplicateSaveExecution(existing);
        }
        AgentConfirmation confirmation = new AgentConfirmation();
        confirmation.setConversationId(conversationId);
        confirmation.setUserId(userId);
        confirmation.setActionType("SAVE_RECIPE");
        confirmation.setIdempotencyKey(existing == null
                ? idempotencyKey
                : UUID.randomUUID().toString().replace("-", ""));
        confirmation.setPayloadJson(payloadForMessage("recipe-card", Map.of("recipe", recipe)));
        confirmation.setStatus("PENDING");
        confirmation.setCreatedAt(LocalDateTime.now());
        try {
            confirmationMapper.insert(confirmation);
        } catch (DuplicateKeyException duplicate) {
            AgentConfirmation concurrent = confirmationMapper.findOwnedByIdempotencyKey(userId, idempotencyKey);
            if (concurrent == null) {
                throw duplicate;
            }
            return duplicateSaveExecution(concurrent);
        }

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

    private ToolExecution duplicateSaveExecution(AgentConfirmation existing) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("status", "save_already_requested");
        output.put("confirmationId", existing.getId());
        output.put("idempotencyKey", existing.getIdempotencyKey());
        String summary = switch (existing.getStatus()) {
            case CONFIRMATION_PENDING -> "这道菜已经发起保存确认，请点击已有的确认按钮";
            case CONFIRMATION_PROCESSING -> "这道菜正在保存处理中，请稍后查看结果";
            case CONFIRMATION_CONFIRMED -> "这道菜已经保存，不会重复保存";
            case CONFIRMATION_UNKNOWN_REVIEW -> "这道菜的保存结果需要人工复核，系统不会重复执行";
            default -> "这道菜的保存请求已经存在，请稍后查看结果";
        };
        output.put("confirmationStatus", existing.getStatus());
        return new ToolExecution(output, summary);
    }

    static String recipeSaveIdempotencyKey(RecipeGenerateResponse recipe) {
        if (recipe.searchLogId() != null) {
            return "recipe-save-" + recipe.searchLogId();
        }
        return "recipe-save-" + Integer.toHexString(Objects.hash(
                recipe.title(), recipe.summary(), recipe.ingredients(), recipe.steps()));
    }

    private AgentConversation conversation(AgentChatRequest request, Long userId) {
        if (request.conversationId() != null) {
            AgentConversation existing = conversationMapper.findOwned(userId, request.conversationId());
            if (existing == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在");
            }
            return existing;
        }
        return newConversation(userId);
    }

    private AgentConversation newConversation(Long userId) {
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

    private void persistCheckpoint(
            AgentExecution execution,
            AgentStatus status,
            AgentNode currentNode,
            AgentNode nextNode,
            String error
    ) {
        Instant now = Instant.now();
        AgentState state = execution.snapshot(status, currentNode, nextNode, error, now);
        long version = runStore.findCheckpoint(execution.runId)
                .map(checkpoint -> checkpoint.version() + 1)
                .orElse(0L);
        runStore.saveCheckpoint(new AgentCheckpoint(execution.runId, version, state, now));
        updateRun(execution.runId, status, currentNode, nextNode, error, execution.conversationId);
    }

    private void persistStep(
            AgentExecution execution,
            AgentNode node,
            String action,
            String toolName,
            String status,
            String requestJson,
            String responseJson,
            String idempotencyKey,
            String errorMessage
    ) {
        Instant now = Instant.now();
        long stepNo = ++execution.stepNo;
        runStore.appendStep(new AgentStep(
                execution.runId,
                stepNo,
                node,
                action,
                toolName,
                status,
                AgentAuditSanitizer.sanitize(requestJson),
                AgentAuditSanitizer.sanitize(responseJson),
                idempotencyKey,
                now,
                now,
                errorMessage
        ));
    }

    private void updateRun(
            String runId,
            AgentStatus status,
            AgentNode currentNode,
            AgentNode nextNode,
            String errorMessage
    ) {
        updateRun(runId, status, currentNode, nextNode, errorMessage, null);
    }

    private void updateRun(
            String runId,
            AgentStatus status,
            AgentNode currentNode,
            AgentNode nextNode,
            String errorMessage,
            Long conversationId
    ) {
        runStore.findRun(runId).ifPresent(existing -> {
            Instant now = Instant.now();
            runStore.saveRun(new AgentRun(
                    existing.runId(),
                    existing.userId(),
                    conversationId == null ? existing.conversationId() : conversationId,
                    status,
                    currentNode,
                    nextNode,
                    existing.recoverable(),
                    now,
                    existing.createdAt() == null ? now : existing.createdAt(),
                    now,
                    existing.errorCode(),
                    errorMessage
            ));
        });
    }

    private void markFailed(String runId, String errorCode, String errorMessage) {
        Instant now = Instant.now();
        runStore.findRun(runId).ifPresent(existing -> runStore.saveRun(new AgentRun(
                existing.runId(),
                existing.userId(),
                existing.conversationId(),
                AgentStatus.FAILED,
                AgentNode.FINALIZE,
                AgentNode.FINALIZE,
                existing.recoverable(),
                now,
                existing.createdAt() == null ? now : existing.createdAt(),
                now,
                errorCode,
                errorMessage
        )));
        runStore.findCheckpoint(runId).ifPresent(checkpoint -> {
            AgentState state = checkpoint.state();
            AgentState failed = new AgentState(
                    state.runId(),
                    state.userId(),
                    state.conversationId(),
                    state.userMessage(),
                    state.hasAttachment(),
                    AgentStatus.FAILED,
                    AgentNode.FINALIZE,
                    AgentNode.FINALIZE,
                    state.round(),
                    state.toolCallCount(),
                    state.stepNo(),
                    state.confirmationRequested(),
                    state.messages(),
                    state.pendingToolCalls(),
                    state.pendingToolIndex(),
                    errorMessage,
                    now,
                    state.intentResolutionAttempted(),
                    state.recipeSaveIntent(),
                    state.intentResolutionSource()
            );
            runStore.saveCheckpoint(new AgentCheckpoint(runId, checkpoint.version() + 1, failed, now));
        });
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
        String runId = activeRunId.get();
        if (!StringUtils.hasText(runId)) {
            throw new IllegalStateException("Agent SSE 事件缺少 runId");
        }
        AgentEvent persisted = eventStore.append(runId, event, data);
        metrics.eventPersisted();
        if (emitter == null) {
            return true;
        }
        try {
            emitter.send(SseEmitter.event()
                    .id(Long.toString(persisted.sequence()))
                    .name(event)
                    .data(data));
            return true;
        } catch (IOException | IllegalStateException exception) {
            // The event is already durable. Keep driving the Agent without a
            // live socket; the reconnect endpoint will replay it later.
            return true;
        }
    }

    private void completeEmitter(SseEmitter emitter) {
        if (emitter == null) {
            return;
        }
        try {
            emitter.complete();
        } catch (IllegalStateException ignored) {
            // The browser may have closed the connection after events were
            // persisted; the durable run remains valid in that case.
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

    private static final class AgentExecution {

        private final String runId;
        private final Long userId;
        private Long conversationId;
        private final String userMessage;
        private final boolean hasAttachment;
        private final List<QwenAgentClient.ConversationMessage> messages;
        private int round;
        private int toolCallCount;
        private long stepNo;
        private boolean confirmationRequested;
        private List<QwenAgentClient.ToolCall> pendingToolCalls;
        private int pendingToolIndex;
        private AgentNode currentNode;
        private AgentNode nextNode;
        private boolean intentResolutionAttempted;
        private boolean recipeSaveIntent;
        private String intentResolutionSource;

        private AgentExecution(
                String runId,
                Long userId,
                Long conversationId,
                String userMessage,
                boolean hasAttachment,
                List<QwenAgentClient.ConversationMessage> messages,
                int round,
                int toolCallCount,
                long stepNo,
                boolean confirmationRequested,
                List<QwenAgentClient.ToolCall> pendingToolCalls,
                int pendingToolIndex,
                AgentNode nextNode
        ) {
            this.runId = runId;
            this.userId = userId;
            this.conversationId = conversationId;
            this.userMessage = userMessage == null ? "" : userMessage;
            this.hasAttachment = hasAttachment;
            this.messages = new ArrayList<>(messages == null ? List.of() : messages);
            this.round = round;
            this.toolCallCount = toolCallCount;
            this.stepNo = stepNo;
            this.confirmationRequested = confirmationRequested;
            this.pendingToolCalls = pendingToolCalls == null ? List.of() : List.copyOf(pendingToolCalls);
            this.pendingToolIndex = pendingToolIndex;
            this.currentNode = nextNode;
            this.nextNode = nextNode;
        }

        private static AgentExecution from(AgentState state) {
            AgentExecution execution = new AgentExecution(
                    state.runId(),
                    state.userId(),
                    state.conversationId(),
                    state.userMessage(),
                    state.hasAttachment(),
                    state.messages(),
                    state.round(),
                    state.toolCallCount(),
                    state.stepNo(),
                    state.confirmationRequested(),
                    state.pendingToolCalls(),
                    state.pendingToolIndex(),
                    state.nextNode()
            );
            execution.currentNode = state.currentNode();
            execution.intentResolutionAttempted = state.intentResolutionAttempted();
            execution.recipeSaveIntent = state.recipeSaveIntent();
            execution.intentResolutionSource = state.intentResolutionSource();
            return execution;
        }

        private AgentState snapshot(
                AgentStatus status,
                AgentNode currentNode,
                AgentNode nextNode,
                String error,
                Instant updatedAt
        ) {
            this.currentNode = currentNode;
            this.nextNode = nextNode;
            return new AgentState(
                    runId,
                    userId,
                    conversationId,
                    userMessage,
                    hasAttachment,
                    status,
                    currentNode,
                    nextNode,
                    round,
                    toolCallCount,
                    stepNo,
                    confirmationRequested,
                    messages,
                    pendingToolCalls,
                    pendingToolIndex,
                    error,
                    updatedAt,
                    intentResolutionAttempted,
                    recipeSaveIntent,
                    intentResolutionSource
            );
        }
    }

    private static final class StreamCancelledException extends RuntimeException {
    }
}
