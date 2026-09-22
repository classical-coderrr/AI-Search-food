package com.example.food.memory;

import java.time.LocalDateTime;

public record MemorySessionOpenCommand(
        Long conversationId,
        String sessionKey,
        String currentTask,
        String currentGoal,
        String contextJson,
        String selectedMemoryIdsJson,
        String retrievedKnowledgeIdsJson,
        String agentStateJson,
        LocalDateTime expiresAt
) {
}
