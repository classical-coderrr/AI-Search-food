package com.example.food.ai.recipe;

import com.example.food.admin.error.AdminErrorLogService;
import com.example.food.ai.qwen.QwenRecipeClient;
import com.example.food.ai.recipe.dto.RecipeGenerateRequest;
import com.example.food.ai.recipe.dto.RecipeGenerateResponse;
import com.example.food.ai.recipe.dto.RecipeRecommendationBatch;
import com.example.food.security.AuthPrincipal;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class RecipeStreamingService {

    private static final long STREAM_TIMEOUT_MILLIS = 300_000L;
    private static final int MAX_RECIPE_ATTEMPTS = 2;

    private final RecipeRecommendationService recipeRecommendationService;
    private final QwenRecipeClient qwenRecipeClient;
    private final AdminErrorLogService errorLogService;
    private final ExecutorService workerExecutor = Executors.newCachedThreadPool(daemonThreadFactory("recipe-stream"));
    private final ScheduledExecutorService heartbeatExecutor = Executors.newScheduledThreadPool(
            1,
            daemonThreadFactory("recipe-heartbeat")
    );

    @Autowired
    public RecipeStreamingService(
            RecipeRecommendationService recipeRecommendationService,
            QwenRecipeClient qwenRecipeClient,
            AdminErrorLogService errorLogService
    ) {
        this.recipeRecommendationService = recipeRecommendationService;
        this.qwenRecipeClient = qwenRecipeClient;
        this.errorLogService = errorLogService;
    }

    public RecipeStreamingService(
            RecipeRecommendationService recipeRecommendationService,
            QwenRecipeClient qwenRecipeClient
    ) {
        this(recipeRecommendationService, qwenRecipeClient, null);
    }

    public SseEmitter generate(
            RecipeGenerateRequest request,
            AuthPrincipal principal,
            String anonymousId
    ) {
        int recommendationCount = normalizedRecommendationCount(request);
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MILLIS);
        AtomicBoolean cancelled = new AtomicBoolean(false);
        AtomicReference<Future<?>> worker = new AtomicReference<>();
        AtomicReference<ScheduledFuture<?>> heartbeat = new AtomicReference<>();
        Runnable cancel = () -> {
            cancelled.set(true);
            Future<?> currentWorker = worker.get();
            if (currentWorker != null) {
                currentWorker.cancel(true);
            }
            ScheduledFuture<?> currentHeartbeat = heartbeat.get();
            if (currentHeartbeat != null) {
                currentHeartbeat.cancel(false);
            }
        };
        emitter.onCompletion(cancel);
        emitter.onTimeout(cancel);
        emitter.onError(error -> cancel.run());

        if (!sendStatus(emitter, cancelled, "preparing", "正在准备" + recommendationCount + "道菜谱推荐")) {
            cancel.run();
            return emitter;
        }

        ScheduledFuture<?> heartbeatTask = heartbeatExecutor.scheduleAtFixedRate(
                () -> sendHeartbeat(emitter, cancelled),
                15,
                15,
                TimeUnit.SECONDS
        );
        heartbeat.set(heartbeatTask);

        Future<?> task = workerExecutor.submit(() -> runGeneration(
                emitter,
                cancelled,
                request,
                principal,
                anonymousId,
                heartbeatTask,
                recommendationCount
        ));
        worker.set(task);
        if (cancelled.get()) {
            task.cancel(true);
        }
        return emitter;
    }

    private void runGeneration(
            SseEmitter emitter,
            AtomicBoolean cancelled,
            RecipeGenerateRequest request,
            AuthPrincipal principal,
            String anonymousId,
            ScheduledFuture<?> heartbeat,
            int recommendationCount
    ) {
        try {
            requireActive(cancelled);
            RecipeRecommendationService.PreparedPrompt prepared = recipeRecommendationService.preparePrompt(request, principal);
            requireActive(cancelled);
            sendStatusOrCancel(emitter, cancelled, "planning", "正在分析真实菜名和食材搭配");
            List<QwenRecipeClient.RecipePlan> recipePlans = recipeRecommendationService.planRecipeSelections(
                    request,
                    recommendationCount,
                    prepared
            );
            recipePlans = recipePlans == null ? List.of() : recipePlans;
            sendStatusOrCancel(
                    emitter,
                    cancelled,
                    "planning",
                    recipePlans.isEmpty()
                            ? "未取得可靠的视频菜名参考，按常见家常菜规则生成"
                            : "标准菜名和食材搭配规划完成"
            );
            sendStatusOrCancel(emitter, cancelled, "generating", "正在连接 AI 生成" + recommendationCount + "道菜谱");

            String batchId = UUID.randomUUID().toString();
            String mode = recipeRecommendationService.recommendationBatchMode(request);
            sendOrCancel(emitter, cancelled, "batch-start", Map.of(
                    "batchId", batchId,
                    "mode", mode,
                    "total", recommendationCount
            ));

            List<GeneratedRecipe> generatedRecipes = new ArrayList<>(recommendationCount);
            for (int index = 0; index < recommendationCount; index++) {
                requireActive(cancelled);
                final int recipeIndex = index;
                String recipeId = batchId + "-recipe-" + (recipeIndex + 1);
                sendOrCancel(emitter, cancelled, "recipe-start", Map.of(
                        "batchId", batchId,
                        "recipeId", recipeId,
                        "index", recipeIndex,
                        "label", recipeLabel(mode, recipeIndex)
                ));

                List<RecipeGenerateResponse> previousResponses = generatedRecipes.stream()
                        .map(GeneratedRecipe::response)
                        .toList();
                RecipeGenerateResponse response = null;
                String retryMessage = null;
                String retryInstruction = null;
                for (int attempt = 0; attempt < MAX_RECIPE_ATTEMPTS; attempt++) {
                    requireActive(cancelled);
                    if (attempt > 0) {
                        sendStatusOrCancel(
                                emitter,
                                cancelled,
                                "retrying",
                                retryMessage == null
                                        ? "检测到重复菜谱，正在换一种做法生成第 " + (recipeIndex + 1) + " 道菜谱"
                                        : retryMessage
                        );
                    }
                    RecipeStreamFieldParser parser = new RecipeStreamFieldParser(new com.fasterxml.jackson.databind.ObjectMapper());
                    QwenRecipeClient.RecipePlan recipePlan = recipeIndex < recipePlans.size()
                            ? recipePlans.get(recipeIndex)
                            : null;
                    String recipePrompt = recipePlan == null
                            ? recipeRecommendationService.batchRecipePrompt(
                                    prepared.prompt(), request, recipeIndex, recommendationCount, previousResponses
                            )
                            : recipeRecommendationService.batchRecipePrompt(
                                    prepared.prompt(), request, recipeIndex, recommendationCount, previousResponses, recipePlan
                            );
                    if (attempt > 0) {
                        recipePrompt += retryInstruction == null
                                ? duplicateRetryInstruction(recipeIndex)
                                : retryInstruction;
                    }
                    QwenRecipeClient.RecipeStreamResult streamResult = qwenRecipeClient.streamRecipe(
                            recipePrompt,
                            delta -> {
                                requireActive(cancelled);
                                Map<String, JsonNode> fields = parser.accept(delta);
                                sendFieldGroups(emitter, cancelled, recipeId, recipeIndex, fields);
                            },
                            () -> sendStatusOrCancel(cancelled, emitter, "receiving", recipeIndex)
                    );
                    requireActive(cancelled);
                    sendStatusOrCancel(emitter, cancelled, "parsing", "正在整理第 " + (recipeIndex + 1) + " 道菜谱");

                    response = streamResult.fallbackResponse();
                    if (response == null) {
                        response = qwenRecipeClient.parseRecipeContent(
                                streamResult.content(),
                                qwenRecipeClient.currentRuntimeConfig()
                        );
                    }
                    validateRecipe(response);
                    try {
                        recipeRecommendationService.validatePantryCompatibility(request, response, prepared);
                    } catch (ResponseStatusException exception) {
                        if (attempt + 1 >= MAX_RECIPE_ATTEMPTS) {
                            throw exception;
                        }
                        retryMessage = "当前库存食材不可参考，正在按原输入食材重新生成第 " + (recipeIndex + 1) + " 道菜谱";
                        retryInstruction = recipeRecommendationService.pantryFallbackRetryInstruction();
                        response = null;
                        continue;
                    }
                    recipeRecommendationService.validateVideoGrounding(response, prepared);
                    try {
                        recipeRecommendationService.validateRequiredIngredientCoverage(
                                request,
                                response,
                                recipeIndex,
                                recommendationCount,
                                recipePlan
                        );
                    } catch (ResponseStatusException exception) {
                        if (attempt + 1 >= MAX_RECIPE_ATTEMPTS) {
                            throw exception;
                        }
                        retryMessage = "第 " + (recipeIndex + 1) + " 道菜谱未覆盖指定食材，正在按核心食材重新生成";
                        retryInstruction = recipeRecommendationService.ingredientCoverageRetryInstruction(
                                request,
                                recipeIndex,
                                recommendationCount,
                                recipePlan
                        );
                        response = null;
                        continue;
                    }
                    try {
                        recipeRecommendationService.validateRecipeIngredientPair(
                                request,
                                response,
                                recipeIndex,
                                recommendationCount
                        );
                    } catch (ResponseStatusException exception) {
                        if (attempt + 1 >= MAX_RECIPE_ATTEMPTS) {
                            throw exception;
                        }
                        retryMessage = "食材搭配超出两种限制，正在更换第 " + (recipeIndex + 1) + " 道菜谱的做法";
                        retryInstruction = ingredientConstraintRetryInstruction(recipeIndex);
                        response = null;
                        continue;
                    }
                    if (!recipeRecommendationService.isDuplicateRecipe(response, previousResponses)) {
                        break;
                    }
                    retryMessage = "检测到重复菜谱，正在换一种做法生成第 " + (recipeIndex + 1) + " 道菜谱";
                    retryInstruction = duplicateRetryInstruction(recipeIndex);
                    response = null;
                }
                if (response == null) {
                    throw new ResponseStatusException(
                            HttpStatus.BAD_GATEWAY,
                            "AI 返回了重复菜谱，请点击重试"
                    );
                }
                generatedRecipes.add(new GeneratedRecipe(recipeId, recipeIndex, response));
            }

            recipeRecommendationService.validateBatchIngredientAlignment(
                    request,
                    generatedRecipes.stream().map(GeneratedRecipe::response).toList()
            );
            requireActive(cancelled);
            sendStatusOrCancel(emitter, cancelled, "saving", "正在保存本次推荐记录");

            List<RecipeGenerateResponse> persistedRecipes = new ArrayList<>(recommendationCount);
            for (GeneratedRecipe generated : generatedRecipes) {
                requireActive(cancelled);
                RecipeGenerateResponse persisted = recipeRecommendationService.applyGenerationContextFlags(
                        request,
                        generated.response(),
                        prepared
                );
                persisted = recipeRecommendationService.persist(request, persisted, principal, anonymousId);
                persistedRecipes.add(persisted);
                sendOrCancel(emitter, cancelled, "recipe-complete", Map.of(
                        "batchId", batchId,
                        "recipeId", generated.recipeId(),
                        "index", generated.index(),
                        "recipe", persisted
                ));
            }
            requireActive(cancelled);
            sendOrCancel(emitter, cancelled, "complete", new RecipeRecommendationBatch(
                    batchId,
                    mode,
                    persistedRecipes.size(),
                    persistedRecipes
            ));
            emitter.complete();
        } catch (StreamCancelledException | CancellationException exception) {
            // The client has left or a newer request has superseded this stream.
        } catch (Throwable exception) {
            if (cancelled.get() || Thread.currentThread().isInterrupted()) {
                return;
            }
            if (errorLogService != null) {
                errorLogService.recordException(
                        exception,
                        principal,
                        "POST",
                        "/api/ai/recipes/generate/stream",
                        HttpStatus.BAD_GATEWAY.value(),
                        null
                );
            }
            sendError(emitter, cancelled, errorMessage(exception));
            emitter.complete();
        } finally {
            heartbeat.cancel(false);
        }
    }

    private void sendStatusOrCancel(
            AtomicBoolean cancelled,
            SseEmitter emitter,
            String stage,
            int recipeIndex
    ) {
        sendStatusOrCancel(emitter, cancelled, stage, "AI 正在生成第 " + (recipeIndex + 1) + " 道菜谱");
    }

    private void sendFieldGroups(
            SseEmitter emitter,
            AtomicBoolean cancelled,
            String recipeId,
            int index,
            Map<String, JsonNode> fields
    ) {
        if (fields.isEmpty()) {
            return;
        }
        sendGroup(emitter, cancelled, recipeId, index, "overview", fields, "title", "summary", "effects");
        sendGroup(emitter, cancelled, recipeId, index, "ingredients", fields, "ingredients", "missingIngredients");
        sendGroup(emitter, cancelled, recipeId, index, "steps", fields, "steps", "tips", "videoKeywords");
        sendGroup(emitter, cancelled, recipeId, index, "details", fields, "explanation", "nutritionEstimate");
    }

    private void sendGroup(
            SseEmitter emitter,
            AtomicBoolean cancelled,
            String recipeId,
            int index,
            String event,
            Map<String, JsonNode> fields,
            String... fieldNames
    ) {
        Map<String, Object> group = new LinkedHashMap<>();
        group.put("recipeId", recipeId);
        group.put("index", index);
        for (String fieldName : fieldNames) {
            JsonNode value = fields.get(fieldName);
            if (value != null) {
                group.put(fieldName, value);
            }
        }
        if (group.size() > 2) {
            sendOrCancel(emitter, cancelled, event, group);
        }
    }

    private boolean sendStatus(
            SseEmitter emitter,
            AtomicBoolean cancelled,
            String stage,
            String message
    ) {
        Map<String, String> data = Map.of("stage", stage, "message", message);
        return send(emitter, cancelled, "status", data);
    }

    private void sendStatusOrCancel(
            SseEmitter emitter,
            AtomicBoolean cancelled,
            String stage,
            String message
    ) {
        if (!sendStatus(emitter, cancelled, stage, message)) {
            throw new StreamCancelledException();
        }
    }

    private void sendHeartbeat(SseEmitter emitter, AtomicBoolean cancelled) {
        if (cancelled.get()) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().comment("recipe-stream-heartbeat"));
        } catch (IOException | IllegalStateException exception) {
            cancelled.set(true);
        }
    }

    private void sendError(SseEmitter emitter, AtomicBoolean cancelled, String message) {
        if (!cancelled.get()) {
            send(emitter, cancelled, "error", Map.of("message", message));
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

    private void sendOrCancel(
            SseEmitter emitter,
            AtomicBoolean cancelled,
            String event,
            Object data
    ) {
        if (!send(emitter, cancelled, event, data)) {
            throw new StreamCancelledException();
        }
    }

    private void requireActive(AtomicBoolean cancelled) {
        if (cancelled.get() || Thread.currentThread().isInterrupted()) {
            throw new StreamCancelledException();
        }
    }

    private void validateRecipe(RecipeGenerateResponse response) {
        boolean validTitle = response != null && hasText(response.title()) && hasText(response.summary());
        boolean validIngredient = response != null && response.ingredients().stream()
                .anyMatch(item -> item != null && hasText(item.name()));
        boolean validStep = response != null && response.steps().stream()
                .anyMatch(item -> item != null && (hasText(item.title()) || hasText(item.description())));
        if (!validTitle || !validIngredient || !validStep) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI 返回的菜谱内容不完整，请点击重试");
        }
    }

    private String errorMessage(Throwable exception) {
        Throwable current = exception;
        while (current.getCause() != null && !(current instanceof ResponseStatusException)) {
            current = current.getCause();
        }
        if (current instanceof ResponseStatusException statusException
                && statusException.getReason() != null
                && !statusException.getReason().isBlank()) {
            return statusException.getReason();
        }
        if (current instanceof SocketTimeoutException) {
            return "AI 生成超时，请检查网络后重试";
        }
        return "AI 菜谱生成暂时失败，请检查网络后重试";
    }

    private String duplicateRetryInstruction(int recipeIndex) {
        return """


                【重复校正】
                上一次生成的第 %d 道菜谱与前面结果重复，本次必须完全换一种菜名和做法。
                禁止复制前面菜谱的标题、简介、食材清单或步骤；请输出同一输入食材下另一种真实可执行的家常做法。
                """.formatted(recipeIndex + 1);
    }

    private String ingredientConstraintRetryInstruction(int recipeIndex) {
        return """


                【食材约束校正】
                上一次生成的第 %d 道菜谱违反了“每道菜最多两种输入食材”的硬性规则。
                本次必须严格使用上方【多菜谱组合生成规则】中列出的固定核心食材，只能使用这一至两种输入食材。
                严禁把【本次其他指定食材（禁止使用）】中的任何食材写入菜名、ingredients、steps、简介或营养说明；特别是不能加入第三种输入食材。
                只允许补充葱、姜、蒜、食用油、盐、糖、清水等常见调味辅料。请改用固定核心食材对应的真实、常见、可执行家常做法，并返回完整 JSON，不要返回空数据。
                """.formatted(recipeIndex + 1);
    }

    private String recipeLabel(String mode, int index) {
        if ("MEAL_COMBO".equals(mode)) {
            return switch (index) {
                case 0 -> "主菜搭配";
                case 1 -> "清爽配菜";
                case 2 -> "风味配菜";
                case 3 -> "蒸煮焖烧";
                default -> "经典家常";
            };
        }
        return switch (index) {
            case 0 -> "家常风味";
            case 1 -> "清爽低油";
            case 2 -> "快速省时";
            case 3 -> "蒸煮焖烧";
            default -> "经典家常";
        };
    }

    private int normalizedRecommendationCount(RecipeGenerateRequest request) {
        int count = recipeRecommendationService.recommendationCount(request);
        return count >= 3 ? count : 3;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static java.util.concurrent.ThreadFactory daemonThreadFactory(String prefix) {
        return runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName(prefix + "-" + thread.getId());
            thread.setDaemon(true);
            return thread;
        };
    }

    private static final class StreamCancelledException extends RuntimeException {
    }

    private record GeneratedRecipe(String recipeId, int index, RecipeGenerateResponse response) {
    }
}
