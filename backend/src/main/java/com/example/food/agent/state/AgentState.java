package com.example.food.agent.state;

import com.example.food.ai.qwen.QwenAgentClient;

import java.time.Instant;
import java.util.List;

/**
 * Serializable execution state for one Agent run. The state is deliberately
 * independent from the HTTP/SSE connection so that a new application process
 * can resume the run after a restart.
 */
public record AgentState(
        String runId,
        Long userId,
        Long conversationId,
        String userMessage,
        boolean hasAttachment,
        AgentStatus status,
        AgentNode currentNode,
        AgentNode nextNode,
        int round,
        int toolCallCount,
        long stepNo,
        boolean confirmationRequested,
        List<QwenAgentClient.ConversationMessage> messages,
        List<QwenAgentClient.ToolCall> pendingToolCalls,
        int pendingToolIndex,
        String lastError,
        Instant updatedAt
) {
    public AgentState {
        messages = messages == null ? List.of() : List.copyOf(messages);
        pendingToolCalls = pendingToolCalls == null ? List.of() : List.copyOf(pendingToolCalls);
    }
}
