package com.example.food.memory;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.Objects;

@Service
public class MemorySessionService {

    private static final int MAX_SESSION_KEY_LENGTH = 96;
    private static final int MAX_TASK_LENGTH = 128;
    private static final int MAX_GOAL_LENGTH = 255;
    private final MemorySessionMapper mapper;

    @Autowired
    public MemorySessionService(MemorySessionMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional
    public MemorySession open(Long userId, MemorySessionOpenCommand command) {
        requireUser(userId);
        validateOpenCommand(command);
        String sessionKey = normalizeSessionKey(command.sessionKey());

        MemorySession existing = mapper.findOwnedByKey(userId, sessionKey);
        if (existing != null) {
            return updateExisting(userId, existing, new MemorySessionUpdate(
                    command.conversationId(),
                    command.currentTask(),
                    command.currentGoal(),
                    command.contextJson(),
                    command.selectedMemoryIdsJson(),
                    command.retrievedKnowledgeIdsJson(),
                    command.agentStateJson(),
                    command.expiresAt()
            ));
        }

        MemorySession session = new MemorySession();
        session.setUserId(userId);
        session.setConversationId(command.conversationId());
        session.setSessionKey(sessionKey);
        session.setStatus(MemorySessionStatus.ACTIVE.name());
        session.setCurrentTask(command.currentTask());
        session.setCurrentGoal(command.currentGoal());
        session.setContextJson(command.contextJson());
        session.setSelectedMemoryIdsJson(command.selectedMemoryIdsJson());
        session.setRetrievedKnowledgeIdsJson(command.retrievedKnowledgeIdsJson());
        session.setAgentStateJson(command.agentStateJson());
        session.setExpiresAt(command.expiresAt());
        session.setVersion(0);
        try {
            mapper.insert(session);
            return requireOwned(userId, session.getId());
        } catch (DuplicateKeyException duplicate) {
            MemorySession raced = mapper.findOwnedByKey(userId, sessionKey);
            if (raced == null) {
                throw duplicate;
            }
            return updateExisting(userId, raced, new MemorySessionUpdate(
                    command.conversationId(),
                    command.currentTask(),
                    command.currentGoal(),
                    command.contextJson(),
                    command.selectedMemoryIdsJson(),
                    command.retrievedKnowledgeIdsJson(),
                    command.agentStateJson(),
                    command.expiresAt()
            ));
        }
    }

    @Transactional
    public MemorySession touch(Long userId, Long sessionId, MemorySessionUpdate update) {
        requireUser(userId);
        Objects.requireNonNull(update, "update");
        MemorySession current = requireOwned(userId, sessionId);
        return updateExisting(userId, current, update);
    }

    @Transactional
    public MemorySession close(Long userId, Long sessionId) {
        requireUser(userId);
        MemorySession current = requireOwned(userId, sessionId);
        int updated = mapper.closeOwned(
                userId,
                sessionId,
                current.getVersion(),
                MemorySessionStatus.COMPLETED.name()
        );
        if (updated != 1) {
            throw concurrentUpdate();
        }
        return requireOwned(userId, sessionId);
    }

    @Transactional
    public void delete(Long userId, Long sessionId) {
        requireUser(userId);
        MemorySession current = requireOwned(userId, sessionId);
        if (mapper.softDeleteOwned(userId, sessionId, current.getVersion()) != 1) {
            throw concurrentUpdate();
        }
    }

    public MemorySession findOwned(Long userId, Long sessionId) {
        requireUser(userId);
        return requireOwned(userId, sessionId);
    }

    private MemorySession updateExisting(Long userId, MemorySession current, MemorySessionUpdate update) {
        if (mapper.updateOwned(
                userId,
                current.getId(),
                current.getVersion(),
                update.conversationId(),
                MemorySessionStatus.ACTIVE.name(),
                update.currentTask(),
                update.currentGoal(),
                update.contextJson(),
                update.selectedMemoryIdsJson(),
                update.retrievedKnowledgeIdsJson(),
                update.agentStateJson(),
                update.expiresAt()
        ) != 1) {
            throw concurrentUpdate();
        }
        return requireOwned(userId, current.getId());
    }

    private MemorySession requireOwned(Long userId, Long sessionId) {
        if (sessionId == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "记忆会话不存在");
        }
        MemorySession session = mapper.findOwned(userId, sessionId);
        if (session == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "记忆会话不存在");
        }
        return session;
    }

    private void validateOpenCommand(MemorySessionOpenCommand command) {
        Objects.requireNonNull(command, "command");
        normalizeSessionKey(command.sessionKey());
        if (tooLong(command.currentTask(), MAX_TASK_LENGTH) || tooLong(command.currentGoal(), MAX_GOAL_LENGTH)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "记忆会话字段超出长度限制");
        }
    }

    private String normalizeSessionKey(String value) {
        if (!StringUtils.hasText(value)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sessionKey 无效");
        }
        String normalized = value.trim();
        if (normalized.length() > MAX_SESSION_KEY_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sessionKey 无效");
        }
        return normalized;
    }

    private boolean tooLong(String value, int maxLength) {
        return value != null && value.length() > maxLength;
    }

    private void requireUser(Long userId) {
        if (userId == null || userId <= 0) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户身份无效");
        }
    }

    private ResponseStatusException concurrentUpdate() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "记忆会话已被其他请求更新，请重试");
    }
}
