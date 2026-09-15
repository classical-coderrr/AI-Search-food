package com.example.food.agent;

import com.example.food.ai.qwen.QwenAgentClient;
import com.example.food.agent.state.AgentCheckpoint;
import com.example.food.agent.state.AgentNode;
import com.example.food.agent.state.AgentRun;
import com.example.food.agent.state.AgentRunStore;
import com.example.food.agent.state.AgentState;
import com.example.food.agent.state.AgentStatus;
import com.example.food.agent.state.InMemoryAgentRunStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentServiceRecoveryTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void resumesModelDecisionCheckpointAndCompletesWithoutReplayingTheUserMessage() {
        AgentRunStore runStore = new InMemoryAgentRunStore();
        String runId = "recovery-model-decision";
        Instant now = Instant.now();
        AgentRun run = run(runId, AgentStatus.RUNNING, AgentNode.MODEL_DECISION, AgentNode.MODEL_DECISION, now);
        AgentState state = new AgentState(
                runId,
                7L,
                42L,
                "给我一个简单晚餐建议",
                false,
                AgentStatus.RUNNING,
                AgentNode.MODEL_DECISION,
                AgentNode.MODEL_DECISION,
                0,
                0,
                0,
                false,
                List.of(QwenAgentClient.ConversationMessage.user("给我一个简单晚餐建议")),
                List.of(),
                0,
                null,
                now
        );
        runStore.create(run, new AgentCheckpoint(runId, 0, state, now));

        AgentConversationMapper conversationMapper = mock(AgentConversationMapper.class);
        AgentMessageMapper messageMapper = mock(AgentMessageMapper.class);
        QwenAgentClient qwenAgentClient = mock(QwenAgentClient.class);
        AgentIntentRecognizer intentRecognizer = mock(AgentIntentRecognizer.class);
        when(intentRecognizer.recognize(eq(state.userMessage()), anyList())).thenReturn(
                new AgentIntentRecognizer.RecognitionResult(
                        false, false, "OTHER", 0.99d, "NONE", "not_candidate"
                )
        );
        when(qwenAgentClient.complete(anyList(), anyList())).thenReturn(
                new QwenAgentClient.AgentTurn("建议番茄鸡蛋面", List.of(), "qwen", "qwen-plus")
        );
        AgentService service = service(
                conversationMapper,
                messageMapper,
                qwenAgentClient,
                intentRecognizer,
                runStore
        );

        service.resume(runId, "recovery-worker");

        AgentRun recovered = runStore.findRun(runId).orElseThrow();
        AgentCheckpoint checkpoint = runStore.findCheckpoint(runId).orElseThrow();
        assertThat(recovered.status()).isEqualTo(AgentStatus.COMPLETED);
        assertThat(recovered.currentNode()).isEqualTo(AgentNode.FINALIZE);
        assertThat(checkpoint.state().nextNode()).isEqualTo(AgentNode.FINALIZE);
        verify(qwenAgentClient).complete(anyList(), anyList());
    }

    @Test
    void resumesPendingReadOnlyToolFromToolExecuteCheckpoint() {
        AgentRunStore runStore = new InMemoryAgentRunStore();
        String runId = "recovery-tool-execute";
        Instant now = Instant.now();
        AgentRun run = run(runId, AgentStatus.RUNNING, AgentNode.TOOL_EXECUTE, AgentNode.TOOL_EXECUTE, now);
        AgentState state = new AgentState(
                runId,
                7L,
                42L,
                "现在几点",
                false,
                AgentStatus.RUNNING,
                AgentNode.TOOL_EXECUTE,
                AgentNode.TOOL_EXECUTE,
                1,
                0,
                0,
                false,
                List.of(QwenAgentClient.ConversationMessage.user("现在几点")),
                List.of(new QwenAgentClient.ToolCall("call-1", "current_datetime", "{}")),
                0,
                null,
                now
        );
        runStore.create(run, new AgentCheckpoint(runId, 0, state, now));

        AgentConversationMapper conversationMapper = mock(AgentConversationMapper.class);
        AgentMessageMapper messageMapper = mock(AgentMessageMapper.class);
        AgentKitchenToolService kitchenToolService = mock(AgentKitchenToolService.class);
        QwenAgentClient qwenAgentClient = mock(QwenAgentClient.class);
        when(kitchenToolService.isMutation(any(), any())).thenReturn(false);
        when(kitchenToolService.execute(eq(AgentToolRegistry.Tool.CURRENT_DATETIME), any(), any(), any()))
                .thenReturn(new AgentKitchenToolService.ToolResult(
                        "当前时间", "已读取当前时间", java.util.Map.of("datetime", "2026-09-15T20:00:00+08:00"), "datetime-card"
                ));
        when(qwenAgentClient.complete(anyList(), anyList())).thenReturn(
                new QwenAgentClient.AgentTurn("现在是晚上八点。", List.of(), "qwen", "qwen-plus")
        );
        AgentIntentRecognizer intentRecognizer = mock(AgentIntentRecognizer.class);
        when(intentRecognizer.recognize(any(), anyList())).thenReturn(
                new AgentIntentRecognizer.RecognitionResult(
                        false, false, "OTHER", 0.99d, "NONE", "not_candidate"
                )
        );
        AgentService service = service(
                conversationMapper,
                messageMapper,
                qwenAgentClient,
                intentRecognizer,
                runStore,
                kitchenToolService
        );

        service.resume(runId, "recovery-worker");

        assertThat(runStore.findRun(runId).orElseThrow().status()).isEqualTo(AgentStatus.COMPLETED);
        verify(kitchenToolService).execute(eq(AgentToolRegistry.Tool.CURRENT_DATETIME), any(), any(), any());
        verify(qwenAgentClient).complete(anyList(), anyList());
    }

    private AgentService service(
            AgentConversationMapper conversationMapper,
            AgentMessageMapper messageMapper,
            QwenAgentClient qwenAgentClient,
            AgentIntentRecognizer intentRecognizer,
            AgentRunStore runStore
    ) {
        return service(
                conversationMapper,
                messageMapper,
                qwenAgentClient,
                intentRecognizer,
                runStore,
                mock(AgentKitchenToolService.class)
        );
    }

    private AgentService service(
            AgentConversationMapper conversationMapper,
            AgentMessageMapper messageMapper,
            QwenAgentClient qwenAgentClient,
            AgentIntentRecognizer intentRecognizer,
            AgentRunStore runStore,
            AgentKitchenToolService kitchenToolService
    ) {
        AgentIntentAuditService auditService = new AgentIntentAuditService(runStore, objectMapper);
        return new AgentService(
                conversationMapper,
                messageMapper,
                null,
                new AgentToolRegistry(),
                kitchenToolService,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                qwenAgentClient,
                intentRecognizer,
                auditService,
                objectMapper,
                runStore
        );
    }

    private AgentRun run(
            String runId,
            AgentStatus status,
            AgentNode currentNode,
            AgentNode nextNode,
            Instant now
    ) {
        return new AgentRun(
                runId,
                7L,
                42L,
                status,
                currentNode,
                nextNode,
                true,
                now,
                now,
                now,
                null,
                null
        );
    }
}
