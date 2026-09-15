package com.example.food.agent;

import com.example.food.agent.dto.AgentWriteOperationStatusResponse;
import com.example.food.security.AuthPrincipal;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;

/**
 * Claims a write key once and stores its result for replay and status queries.
 */
@Service
public class AgentWriteOperationService {

    public static final String STATUS_PROCESSING = "PROCESSING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_UNKNOWN_REVIEW = "UNKNOWN_REVIEW";

    private final AgentWriteOperationMapper mapper;
    private final ObjectMapper objectMapper;

    public AgentWriteOperationService(AgentWriteOperationMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    /**
     * Commit the claim before the business write starts. This makes a
     * long-running operation visible as PROCESSING to a client that lost its
     * SSE connection, while the later completion update still joins the
     * business transaction.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ClaimResult claim(Long userId, Long confirmationId, String actionType, String idempotencyKey) {
        String key = requireKey(idempotencyKey);
        AgentWriteOperation existing = mapper.findOwnedByKey(userId, key);
        if (existing != null) {
            return ClaimResult.existing(existing);
        }

        LocalDateTime now = LocalDateTime.now();
        AgentWriteOperation operation = new AgentWriteOperation();
        operation.setUserId(userId);
        operation.setConfirmationId(confirmationId);
        operation.setActionType(actionType);
        operation.setIdempotencyKey(key);
        operation.setStatus(STATUS_PROCESSING);
        operation.setCreatedAt(now);
        operation.setStartedAt(now);
        operation.setUpdatedAt(now);
        try {
            mapper.insert(operation);
            return ClaimResult.acquired(operation);
        } catch (DuplicateKeyException duplicate) {
            AgentWriteOperation concurrent = mapper.findOwnedByKey(userId, key);
            if (concurrent == null) {
                throw duplicate;
            }
            return ClaimResult.existing(concurrent);
        }
    }

    @Transactional
    public void complete(Long userId, String idempotencyKey, String resultMessage, Object detail) {
        String resultJson = writeJson(detail);
        if (mapper.markCompleted(userId, requireKey(idempotencyKey), resultJson, resultMessage) != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "写操作结果已被其他请求更新");
        }
    }

    /**
     * Persist deterministic business failures after the claim has been
     * committed. Crash/connection-loss cases intentionally remain PROCESSING
     * and are handled by the recovery drill instead of being guessed as
     * failures.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(Long userId, String idempotencyKey, String errorCode, String errorMessage) {
        mapper.markFailed(userId, requireKey(idempotencyKey),
                StringUtils.hasText(errorCode) ? errorCode : "WRITE_FAILED",
                StringUtils.hasText(errorMessage) ? errorMessage : "写操作执行失败");
    }

    public AgentWriteOperationStatusResponse status(AuthPrincipal principal, String idempotencyKey) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
        }
        AgentWriteOperation operation = mapper.findOwnedByKey(principal.id(), requireKey(idempotencyKey));
        if (operation == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "写操作记录不存在");
        }
        return new AgentWriteOperationStatusResponse(
                operation.getId(),
                operation.getConfirmationId(),
                operation.getActionType(),
                operation.getIdempotencyKey(),
                operation.getStatus(),
                operation.getCreatedAt(),
                operation.getStartedAt(),
                operation.getCompletedAt(),
                operation.getResultMessage(),
                readJson(operation.getResultJson()),
                operation.getErrorCode(),
                operation.getErrorMessage()
        );
    }

    public AgentWriteService.ConfirmationResult replay(AgentWriteOperation operation) {
        return switch (operation.getStatus()) {
            case STATUS_COMPLETED -> AgentWriteService.ConfirmationResult.alreadyCompleted(operation.getResultMessage());
            case STATUS_FAILED -> AgentWriteService.ConfirmationResult.failed(operation.getErrorMessage());
            case STATUS_UNKNOWN_REVIEW -> AgentWriteService.ConfirmationResult.unknown(operation.getErrorMessage());
            default -> AgentWriteService.ConfirmationResult.processing();
        };
    }

    private String requireKey(String idempotencyKey) {
        if (!StringUtils.hasText(idempotencyKey) || idempotencyKey.trim().length() > 96) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少有效幂等键");
        }
        return idempotencyKey.trim();
    }

    private String writeJson(Object detail) {
        try {
            return objectMapper.writeValueAsString(detail);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("写操作结果序列化失败", exception);
        }
    }

    private JsonNode readJson(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            return objectMapper.nullNode();
        }
    }

    public record ClaimResult(boolean acquired, AgentWriteOperation operation) {
        static ClaimResult acquired(AgentWriteOperation operation) {
            return new ClaimResult(true, operation);
        }

        static ClaimResult existing(AgentWriteOperation operation) {
            return new ClaimResult(false, operation);
        }
    }
}
