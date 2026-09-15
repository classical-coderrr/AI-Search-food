package com.example.food.agent;

import com.example.food.ai.recipe.dto.RecipeGenerateResponse;
import com.example.food.agent.dto.AgentConfirmationStatusResponse;
import com.example.food.agent.dto.AgentWriteOperationStatusResponse;
import com.example.food.recipe.SavedRecipeService;
import com.example.food.recipe.dto.RecipeHistoryDetailResponse;
import com.example.food.recipe.dto.SaveRecipeRequest;
import com.example.food.security.AuthPrincipal;
import com.example.food.security.UserSecurityLogService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;

@Service
public class AgentWriteService {

    private static final String ACTION_SAVE_RECIPE = "SAVE_RECIPE";
    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_PROCESSING = "PROCESSING";
    private static final String STATUS_CONFIRMED = "CONFIRMED";
    private static final String STATUS_FAILED = "FAILED";
    private static final String STATUS_UNKNOWN_REVIEW = "UNKNOWN_REVIEW";
    private static final Duration PROCESSING_TIMEOUT = Duration.ofMinutes(20);

    private final AgentConfirmationMapper confirmationMapper;
    private final SavedRecipeService savedRecipeService;
    private final AgentKitchenActionService actionService;
    private final AgentWriteOperationService operationService;
    private final UserSecurityLogService securityLogService;
    private final ObjectMapper objectMapper;

    public AgentWriteService(
            AgentConfirmationMapper confirmationMapper,
            SavedRecipeService savedRecipeService,
            AgentKitchenActionService actionService,
            AgentWriteOperationService operationService,
            UserSecurityLogService securityLogService,
            ObjectMapper objectMapper
    ) {
        this.confirmationMapper = confirmationMapper;
        this.savedRecipeService = savedRecipeService;
        this.actionService = actionService;
        this.operationService = operationService;
        this.securityLogService = securityLogService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ConfirmationResult execute(AuthPrincipal principal, Long confirmationId, String idempotencyKey) {
        String normalizedIdempotencyKey = idempotencyKey == null ? null : idempotencyKey.trim();
        AgentConfirmation confirmation = ownedConfirmation(principal, confirmationId, normalizedIdempotencyKey);
        confirmation = markStaleProcessingForReview(principal.id(), confirmation);
        if (STATUS_CONFIRMED.equals(confirmation.getStatus())) {
            return ConfirmationResult.alreadyCompleted(confirmation.getResultMessage());
        }
        if (STATUS_UNKNOWN_REVIEW.equals(confirmation.getStatus())) {
            return ConfirmationResult.unknown(confirmation.getErrorMessage());
        }
        if (STATUS_FAILED.equals(confirmation.getStatus())) {
            return ConfirmationResult.failed(confirmation.getErrorMessage());
        }
        if (STATUS_PROCESSING.equals(confirmation.getStatus())) {
            return ConfirmationResult.processing();
        }
        AgentWriteOperationService.ClaimResult operation = operationService.claim(
                principal.id(), confirmationId, confirmation.getActionType(), normalizedIdempotencyKey);
        if (!operation.acquired()) {
            return operationService.replay(operation.operation());
        }

        // Claim the durable idempotency ledger before locking the confirmation row.
        // The ledger uses a REQUIRES_NEW transaction and references the confirmation
        // by foreign key; reversing this order makes MySQL wait on our own outer
        // transaction until innodb_lock_wait_timeout is reached.
        if (!STATUS_PENDING.equals(confirmation.getStatus())
                || confirmationMapper.claim(principal.id(), confirmationId) != 1) {
            AgentConfirmation current = confirmationMapper.findOwned(principal.id(), confirmationId);
            operationService.fail(
                    principal.id(),
                    normalizedIdempotencyKey,
                    "CONFIRMATION_CLAIM_LOST",
                    "确认状态已被其他请求更新，未执行写操作"
            );
            return current == null ? ConfirmationResult.unknown("操作确认已失效，请重新发起") : resultForStatus(current);
        }

        ConfirmationResult result;
        try {
            if (ACTION_SAVE_RECIPE.equals(confirmation.getActionType())) {
                RecipeHistoryDetailResponse detail = saveRecipePayload(
                        principal, confirmation.getPayloadJson(), normalizedIdempotencyKey);
                result = ConfirmationResult.completed(detail, "菜谱已保存到我的菜谱");
            } else {
                AgentKitchenActionService.ActionResult action = actionService.execute(
                        confirmation.getActionType(), readPayload(confirmation.getPayloadJson()), principal, normalizedIdempotencyKey);
                result = ConfirmationResult.completed(action.detail(), action.message());
            }

            operationService.complete(principal.id(), normalizedIdempotencyKey, result.message(), result.detail());
        } catch (ResponseStatusException exception) {
            operationService.fail(
                    principal.id(),
                    normalizedIdempotencyKey,
                    String.valueOf(exception.getStatusCode().value()),
                    exception.getReason()
            );
            throw exception;
        } catch (RuntimeException exception) {
            operationService.fail(principal.id(), normalizedIdempotencyKey, "WRITE_FAILED", exception.getMessage());
            throw exception;
        }

        if (confirmationMapper.markConfirmed(principal.id(), confirmationId, result.message()) != 1) {
            return ConfirmationResult.processing();
        }
        securityLogService.record(
                principal.id(),
                "AGENT_" + confirmation.getActionType(),
                "/api/agent/chat/stream",
                "confirmationId=" + confirmationId
        );
        return result;
    }

    @Transactional
    public AgentConfirmationStatusResponse status(AuthPrincipal principal, Long confirmationId) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
        }
        AgentConfirmation confirmation = confirmationMapper.findOwned(principal.id(), confirmationId);
        if (confirmation == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "操作确认已失效");
        }
        confirmation = markStaleProcessingForReview(principal.id(), confirmation);
        return new AgentConfirmationStatusResponse(
                confirmation.getId(),
                confirmation.getConversationId(),
                confirmation.getActionType(),
                confirmation.getStatus(),
                confirmation.getCreatedAt(),
                confirmation.getProcessingAt(),
                confirmation.getConfirmedAt(),
                confirmation.getResultMessage(),
                confirmation.getErrorCode(),
                confirmation.getErrorMessage()
        );
    }

    public AgentWriteOperationStatusResponse operationStatus(AuthPrincipal principal, String idempotencyKey) {
        return operationService.status(principal, idempotencyKey);
    }

    public ConfirmationResult saveRecipe(AuthPrincipal principal, Long confirmationId, String idempotencyKey) {
        AgentConfirmation confirmation = confirmationMapper.findOwned(principal.id(), confirmationId);
        if (confirmation == null || !ACTION_SAVE_RECIPE.equals(confirmation.getActionType())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "保存确认已失效");
        }
        return execute(principal, confirmationId, idempotencyKey);
    }

    private AgentConfirmation ownedConfirmation(AuthPrincipal principal, Long confirmationId, String idempotencyKey) {
        AgentConfirmation confirmation = confirmationMapper.findOwned(principal.id(), confirmationId);
        if (confirmation == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "操作确认已失效");
        }
        if (!Objects.equals(confirmation.getIdempotencyKey(), idempotencyKey)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "确认凭证不匹配，请重新发起操作");
        }
        if (confirmation.getCreatedAt() != null
                && confirmation.getCreatedAt().isBefore(LocalDateTime.now().minusMinutes(30))) {
            throw new ResponseStatusException(HttpStatus.GONE, "操作确认已过期，请重新发起");
        }
        return confirmation;
    }

    private AgentConfirmation markStaleProcessingForReview(Long userId, AgentConfirmation confirmation) {
        if (!STATUS_PROCESSING.equals(confirmation.getStatus()) || confirmation.getProcessingAt() == null) {
            return confirmation;
        }
        LocalDateTime staleBefore = LocalDateTime.now().minus(PROCESSING_TIMEOUT);
        if (confirmation.getProcessingAt().isAfter(staleBefore)) {
            return confirmation;
        }
        if (confirmationMapper.markUnknownIfStale(userId, confirmation.getId(), staleBefore) == 1) {
            confirmation.setStatus(STATUS_UNKNOWN_REVIEW);
            confirmation.setErrorCode("PROCESSING_TIMEOUT");
            confirmation.setErrorMessage("操作长时间处于处理中，无法确认是否已完成，请人工复核");
        }
        return confirmation;
    }

    private ConfirmationResult resultForStatus(AgentConfirmation confirmation) {
        return switch (confirmation.getStatus()) {
            case STATUS_CONFIRMED -> ConfirmationResult.alreadyCompleted(confirmation.getResultMessage());
            case STATUS_PROCESSING -> ConfirmationResult.processing();
            case STATUS_UNKNOWN_REVIEW -> ConfirmationResult.unknown(confirmation.getErrorMessage());
            case STATUS_FAILED -> ConfirmationResult.failed(confirmation.getErrorMessage());
            default -> ConfirmationResult.processing();
        };
    }

    private RecipeHistoryDetailResponse saveRecipePayload(
            AuthPrincipal principal,
            String payloadJson,
            String idempotencyKey
    ) {
        RecipeGenerateResponse recipe = readRecipe(payloadJson);
        if (recipe.searchLogId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "本次菜谱缺少搜索记录，暂时无法安全保存");
        }
        SaveRecipeRequest request = new SaveRecipeRequest(
                recipe.searchLogId(), recipe.title(), recipe.summary(), recipe.effects(), recipe.ingredients(),
                recipe.missingIngredients(), recipe.steps(), recipe.tips(), recipe.videoKeywords(), recipe.explanation(),
                recipe.nutritionEstimate(), valueOrDefault(recipe.provider(), "qwen"), valueOrDefault(recipe.model(), "unknown")
        );
        return savedRecipeService.save(request, principal, null, idempotencyKey);
    }

    private RecipeGenerateResponse readRecipe(String payloadJson) {
        try {
            return objectMapper.readValue(payloadJson, RecipeGenerateResponse.class);
        } catch (JsonProcessingException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "保存内容已损坏，请重新生成菜谱", exception);
        }
    }

    private JsonNode readPayload(String payloadJson) {
        try {
            JsonNode payload = objectMapper.readTree(payloadJson);
            if (payload == null || !payload.isObject()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "确认操作参数已损坏");
            }
            return payload;
        } catch (JsonProcessingException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "确认操作参数已损坏", exception);
        }
    }

    private String valueOrDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    public record ConfirmationResult(String status, Object detail, String message) {
        static ConfirmationResult completed(Object detail, String message) {
            return new ConfirmationResult("completed", detail, message);
        }

        static ConfirmationResult alreadyCompleted(String resultMessage) {
            return new ConfirmationResult(
                    "already-completed",
                    null,
                    resultMessage == null || resultMessage.isBlank()
                            ? "这项操作已经执行过了，不会重复处理"
                            : resultMessage + "（已完成，不会重复处理）"
            );
        }

        static ConfirmationResult processing() {
            return new ConfirmationResult("processing", null, "操作正在处理中，请稍后查看结果");
        }

        static ConfirmationResult unknown(String message) {
            return new ConfirmationResult(
                    "unknown-review",
                    null,
                    message == null || message.isBlank()
                            ? "操作结果暂时无法确认，请人工复核；系统不会自动重试"
                            : message
            );
        }

        static ConfirmationResult failed(String message) {
            return new ConfirmationResult(
                    "failed",
                    null,
                    message == null || message.isBlank() ? "操作执行失败，请重新发起" : message
            );
        }
    }
}
