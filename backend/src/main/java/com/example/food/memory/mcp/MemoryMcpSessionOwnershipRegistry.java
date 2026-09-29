package com.example.food.memory.mcp;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
@ConditionalOnProperty(prefix = "app.memory.mcp", name = "enabled", havingValue = "true")
public class MemoryMcpSessionOwnershipRegistry {
    private final ConcurrentMap<String, Long> owners = new ConcurrentHashMap<>();

    public void register(String sessionId, Long userId) {
        if (sessionId != null && !sessionId.isBlank() && userId != null && userId > 0) {
            owners.put(sessionId, userId);
        }
    }

    public boolean isOwnedBy(String sessionId, Long userId) {
        return sessionId != null && userId != null && userId > 0
                && userId.equals(owners.get(sessionId));
    }

    public void remove(String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) {
            owners.remove(sessionId);
        }
    }
}
