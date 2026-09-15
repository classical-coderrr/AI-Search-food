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
        Instant updatedAt,
        boolean intentResolutionAttempted,
        boolean recipeSaveIntent,
        String intentResolutionSource
) {
    public AgentState {
        messages = messages == null ? List.of() : List.copyOf(messages);
        pendingToolCalls = pendingToolCalls == null ? List.of() : List.copyOf(pendingToolCalls);
        intentResolutionSource = intentResolutionSource == null || intentResolutionSource.isBlank()
                ? null
                : intentResolutionSource.trim();
    }

    /**
     * Backward-compatible constructor for checkpoints written before intent
     * routing metadata was added.
     */
    public AgentState(
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
        this(
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
                lastError,
                updatedAt,
                false,
                false,
                null
        );
    }
}
