package com.example.food.agent;

import com.example.food.ai.qwen.QwenAgentClient;
import com.example.food.agent.state.AgentCheckpoint;
import com.example.food.agent.state.AgentFaultInjector;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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

    @Test
    void crashBeforeModelCallLeavesModelDecisionCheckpointForTheNextProcess() {
        AgentRunStore runStore = new InMemoryAgentRunStore();
        String runId = "recovery-model-before";
        Instant now = Instant.now();
        AgentState state = state(runId, AgentNode.MODEL_DECISION, AgentNode.MODEL_DECISION,
                List.of(QwenAgentClient.ConversationMessage.user("给我一个简单晚餐建议")), List.of(), now);
        runStore.create(run(runId, AgentStatus.RUNNING, AgentNode.MODEL_DECISION, AgentNode.MODEL_DECISION, now),
                new AgentCheckpoint(runId, 0, state, now));

        QwenAgentClient qwenAgentClient = mock(QwenAgentClient.class);
        when(qwenAgentClient.complete(anyList(), anyList())).thenReturn(
                new QwenAgentClient.AgentTurn("建议番茄鸡蛋面", List.of(), "qwen", "qwen-plus"));
        AgentIntentRecognizer intentRecognizer = otherIntentRecognizer();
        AgentFaultInjector crashingInjector = mock(AgentFaultInjector.class);
        doThrow(new AgentFaultInjector.AgentCrashException(runId, AgentFaultInjector.Point.MODEL_BEFORE))
                .when(crashingInjector).hit(runId, AgentFaultInjector.Point.MODEL_BEFORE);

        AgentService crashingService = service(mock(AgentConversationMapper.class), mock(AgentMessageMapper.class),
                qwenAgentClient, intentRecognizer, runStore, mock(AgentKitchenToolService.class), crashingInjector);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> crashingService.resume(runId, "process-1"))
                .isInstanceOf(AgentFaultInjector.AgentCrashException.class);
        assertThat(runStore.findCheckpoint(runId).orElseThrow().state().nextNode())
                .isEqualTo(AgentNode.MODEL_DECISION);

        AgentService recoveredService = service(mock(AgentConversationMapper.class), mock(AgentMessageMapper.class),
                qwenAgentClient, intentRecognizer, runStore, mock(AgentKitchenToolService.class));
        recoveredService.resume(runId, "process-2");

        assertThat(runStore.findRun(runId).orElseThrow().status()).isEqualTo(AgentStatus.COMPLETED);
        verify(qwenAgentClient).complete(anyList(), anyList());
    }

    @Test
    void crashAfterModelResultResumesFinalizeWithoutCallingModelAgain() {
        AgentRunStore runStore = new InMemoryAgentRunStore();
        String runId = "recovery-model-after";
        Instant now = Instant.now();
        AgentState state = state(runId, AgentNode.MODEL_DECISION, AgentNode.MODEL_DECISION,
                List.of(QwenAgentClient.ConversationMessage.user("给我一个简单晚餐建议")), List.of(), now);
        runStore.create(run(runId, AgentStatus.RUNNING, AgentNode.MODEL_DECISION, AgentNode.MODEL_DECISION, now),
                new AgentCheckpoint(runId, 0, state, now));

        AgentConversationMapper conversationMapper = mock(AgentConversationMapper.class);
        AgentMessageMapper messageMapper = mock(AgentMessageMapper.class);
        QwenAgentClient qwenAgentClient = mock(QwenAgentClient.class);
        when(qwenAgentClient.complete(anyList(), anyList())).thenReturn(
                new QwenAgentClient.AgentTurn("建议番茄鸡蛋面", List.of(), "qwen", "qwen-plus"));
        AgentFaultInjector crashingInjector = mock(AgentFaultInjector.class);
        doThrow(new AgentFaultInjector.AgentCrashException(runId, AgentFaultInjector.Point.MODEL_AFTER))
                .when(crashingInjector).hit(runId, AgentFaultInjector.Point.MODEL_AFTER);

        AgentService crashingService = service(conversationMapper, messageMapper, qwenAgentClient,
                otherIntentRecognizer(), runStore, mock(AgentKitchenToolService.class), crashingInjector);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> crashingService.resume(runId, "process-1"))
                .isInstanceOf(AgentFaultInjector.AgentCrashException.class);
        assertThat(runStore.findCheckpoint(runId).orElseThrow().state().nextNode())
                .isEqualTo(AgentNode.FINALIZE);

        AgentService recoveredService = service(conversationMapper, messageMapper, qwenAgentClient,
                otherIntentRecognizer(), runStore, mock(AgentKitchenToolService.class));
        recoveredService.resume(runId, "process-2");

        assertThat(runStore.findRun(runId).orElseThrow().status()).isEqualTo(AgentStatus.COMPLETED);
        verify(qwenAgentClient, times(1)).complete(anyList(), anyList());
        verify(messageMapper).insert(any(AgentMessage.class));
    }

    @Test
    void crashBeforeFinalizeUsesTheCheckpointedAssistantResult() {
        AgentRunStore runStore = new InMemoryAgentRunStore();
        String runId = "recovery-finalize-before";
        Instant now = Instant.now();
        AgentState state = state(runId, AgentNode.MODEL_DECISION, AgentNode.MODEL_DECISION,
                List.of(QwenAgentClient.ConversationMessage.user("给我一个简单晚餐建议")), List.of(), now);
        runStore.create(run(runId, AgentStatus.RUNNING, AgentNode.MODEL_DECISION, AgentNode.MODEL_DECISION, now),
                new AgentCheckpoint(runId, 0, state, now));

        AgentConversationMapper conversationMapper = mock(AgentConversationMapper.class);
        AgentMessageMapper messageMapper = mock(AgentMessageMapper.class);
        QwenAgentClient qwenAgentClient = mock(QwenAgentClient.class);
        when(qwenAgentClient.complete(anyList(), anyList())).thenReturn(
                new QwenAgentClient.AgentTurn("建议番茄鸡蛋面", List.of(), "qwen", "qwen-plus"));
        AgentFaultInjector crashingInjector = mock(AgentFaultInjector.class);
        doThrow(new AgentFaultInjector.AgentCrashException(runId, AgentFaultInjector.Point.FINALIZE_BEFORE))
                .when(crashingInjector).hit(runId, AgentFaultInjector.Point.FINALIZE_BEFORE);

        AgentService crashingService = service(conversationMapper, messageMapper, qwenAgentClient,
                otherIntentRecognizer(), runStore, mock(AgentKitchenToolService.class), crashingInjector);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> crashingService.resume(runId, "process-1"))
                .isInstanceOf(AgentFaultInjector.AgentCrashException.class);
        assertThat(runStore.findCheckpoint(runId).orElseThrow().state().nextNode())
                .isEqualTo(AgentNode.FINALIZE);

        AgentService recoveredService = service(conversationMapper, messageMapper, qwenAgentClient,
                otherIntentRecognizer(), runStore, mock(AgentKitchenToolService.class));
        recoveredService.resume(runId, "process-2");

        assertThat(runStore.findRun(runId).orElseThrow().status()).isEqualTo(AgentStatus.COMPLETED);
        verify(qwenAgentClient, times(1)).complete(anyList(), anyList());
        verify(messageMapper).insert(any(AgentMessage.class));
    }

    @Test
    void crashAfterToolResultDoesNotReplayTheCompletedReadOnlyTool() {
        AgentRunStore runStore = new InMemoryAgentRunStore();
        String runId = "recovery-tool-after";
        Instant now = Instant.now();
        AgentState state = state(runId, AgentNode.TOOL_EXECUTE, AgentNode.TOOL_EXECUTE,
                List.of(QwenAgentClient.ConversationMessage.user("现在几点")),
                List.of(new QwenAgentClient.ToolCall("call-1", "current_datetime", "{}")), now);
        runStore.create(run(runId, AgentStatus.RUNNING, AgentNode.TOOL_EXECUTE, AgentNode.TOOL_EXECUTE, now),
                new AgentCheckpoint(runId, 0, state, now));

        AgentKitchenToolService kitchenToolService = mock(AgentKitchenToolService.class);
        when(kitchenToolService.isMutation(any(), any())).thenReturn(false);
        when(kitchenToolService.execute(eq(AgentToolRegistry.Tool.CURRENT_DATETIME), any(), any(), any()))
                .thenReturn(new AgentKitchenToolService.ToolResult(
                        "当前时间", "已读取当前时间", java.util.Map.of("datetime", "2026-09-15T20:00:00+08:00"), "datetime-card"));
        QwenAgentClient qwenAgentClient = mock(QwenAgentClient.class);
        when(qwenAgentClient.complete(anyList(), anyList())).thenReturn(
                new QwenAgentClient.AgentTurn("现在是晚上八点。", List.of(), "qwen", "qwen-plus"));
        AgentFaultInjector crashingInjector = mock(AgentFaultInjector.class);
        doThrow(new AgentFaultInjector.AgentCrashException(runId, AgentFaultInjector.Point.TOOL_AFTER))
                .when(crashingInjector).hit(runId, AgentFaultInjector.Point.TOOL_AFTER);

        AgentService crashingService = service(mock(AgentConversationMapper.class), mock(AgentMessageMapper.class),
                qwenAgentClient, otherIntentRecognizer(), runStore, kitchenToolService, crashingInjector);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> crashingService.resume(runId, "process-1"))
                .isInstanceOf(AgentFaultInjector.AgentCrashException.class);
        assertThat(runStore.findCheckpoint(runId).orElseThrow().state().currentNode())
                .isEqualTo(AgentNode.OBSERVE);

        AgentService recoveredService = service(mock(AgentConversationMapper.class), mock(AgentMessageMapper.class),
                qwenAgentClient, otherIntentRecognizer(), runStore, kitchenToolService);
        recoveredService.resume(runId, "process-2");

        assertThat(runStore.findRun(runId).orElseThrow().status()).isEqualTo(AgentStatus.COMPLETED);
        verify(kitchenToolService, times(1)).execute(eq(AgentToolRegistry.Tool.CURRENT_DATETIME), any(), any(), any());
        verify(qwenAgentClient, times(1)).complete(anyList(), anyList());
    }

    @Test
    void crashBeforeToolExecutionLeavesTheToolCheckpointForTheNextProcess() {
        AgentRunStore runStore = new InMemoryAgentRunStore();
        String runId = "recovery-tool-before";
        Instant now = Instant.now();
        AgentState state = state(runId, AgentNode.TOOL_EXECUTE, AgentNode.TOOL_EXECUTE,
                List.of(QwenAgentClient.ConversationMessage.user("现在几点")),
                List.of(new QwenAgentClient.ToolCall("call-1", "current_datetime", "{}")), now);
        runStore.create(run(runId, AgentStatus.RUNNING, AgentNode.TOOL_EXECUTE, AgentNode.TOOL_EXECUTE, now),
                new AgentCheckpoint(runId, 0, state, now));

        AgentKitchenToolService kitchenToolService = mock(AgentKitchenToolService.class);
        when(kitchenToolService.isMutation(any(), any())).thenReturn(false);
        when(kitchenToolService.execute(eq(AgentToolRegistry.Tool.CURRENT_DATETIME), any(), any(), any()))
                .thenReturn(new AgentKitchenToolService.ToolResult(
                        "当前时间", "已读取当前时间", java.util.Map.of("datetime", "2026-09-15T20:00:00+08:00"), "datetime-card"));
        QwenAgentClient qwenAgentClient = mock(QwenAgentClient.class);
        when(qwenAgentClient.complete(anyList(), anyList())).thenReturn(
                new QwenAgentClient.AgentTurn("现在是晚上八点。", List.of(), "qwen", "qwen-plus"));
        AgentFaultInjector crashingInjector = mock(AgentFaultInjector.class);
        doThrow(new AgentFaultInjector.AgentCrashException(runId, AgentFaultInjector.Point.TOOL_BEFORE))
                .when(crashingInjector).hit(runId, AgentFaultInjector.Point.TOOL_BEFORE);

        AgentService crashingService = service(mock(AgentConversationMapper.class), mock(AgentMessageMapper.class),
                qwenAgentClient, otherIntentRecognizer(), runStore, kitchenToolService, crashingInjector);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> crashingService.resume(runId, "process-1"))
                .isInstanceOf(AgentFaultInjector.AgentCrashException.class);
        assertThat(runStore.findCheckpoint(runId).orElseThrow().state().nextNode())
                .isEqualTo(AgentNode.TOOL_EXECUTE);

        AgentService recoveredService = service(mock(AgentConversationMapper.class), mock(AgentMessageMapper.class),
                qwenAgentClient, otherIntentRecognizer(), runStore, kitchenToolService);
        recoveredService.resume(runId, "process-2");

        assertThat(runStore.findRun(runId).orElseThrow().status()).isEqualTo(AgentStatus.COMPLETED);
        verify(kitchenToolService, times(1)).execute(eq(AgentToolRegistry.Tool.CURRENT_DATETIME), any(), any(), any());
        verify(qwenAgentClient, times(1)).complete(anyList(), anyList());
    }

    @Test
    void crashAfterFinalizeIsAlreadyDurableAndDoesNotReopenTheRun() {
        AgentRunStore runStore = new InMemoryAgentRunStore();
        String runId = "recovery-finalize-after";
        Instant now = Instant.now();
        AgentState state = state(runId, AgentNode.MODEL_DECISION, AgentNode.MODEL_DECISION,
                List.of(QwenAgentClient.ConversationMessage.user("给我一个简单晚餐建议")), List.of(), now);
        runStore.create(run(runId, AgentStatus.RUNNING, AgentNode.MODEL_DECISION, AgentNode.MODEL_DECISION, now),
                new AgentCheckpoint(runId, 0, state, now));

        QwenAgentClient qwenAgentClient = mock(QwenAgentClient.class);
        when(qwenAgentClient.complete(anyList(), anyList())).thenReturn(
                new QwenAgentClient.AgentTurn("建议番茄鸡蛋面", List.of(), "qwen", "qwen-plus"));
        AgentFaultInjector crashingInjector = mock(AgentFaultInjector.class);
        doThrow(new AgentFaultInjector.AgentCrashException(runId, AgentFaultInjector.Point.FINALIZE_AFTER))
                .when(crashingInjector).hit(runId, AgentFaultInjector.Point.FINALIZE_AFTER);

        AgentService crashingService = service(mock(AgentConversationMapper.class), mock(AgentMessageMapper.class),
                qwenAgentClient, otherIntentRecognizer(), runStore, mock(AgentKitchenToolService.class), crashingInjector);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> crashingService.resume(runId, "process-1"))
                .isInstanceOf(AgentFaultInjector.AgentCrashException.class);
        assertThat(runStore.findRun(runId).orElseThrow().status()).isEqualTo(AgentStatus.COMPLETED);

        AgentService recoveredService = service(mock(AgentConversationMapper.class), mock(AgentMessageMapper.class),
                qwenAgentClient, otherIntentRecognizer(), runStore, mock(AgentKitchenToolService.class));
        recoveredService.resume(runId, "process-2");

        verify(qwenAgentClient, times(1)).complete(anyList(), anyList());
    }

    @Test
    void crashWhileWaitingForConfirmationNeverExecutesTheMutationDuringRecovery() {
        AgentRunStore runStore = new InMemoryAgentRunStore();
        String runId = "recovery-waiting-confirmation";
        Instant now = Instant.now();
        AgentState state = state(runId, AgentNode.TOOL_EXECUTE, AgentNode.TOOL_EXECUTE,
                List.of(QwenAgentClient.ConversationMessage.user("把鸡蛋加入库存")),
                List.of(new QwenAgentClient.ToolCall("call-1", "pantry_manage",
                        "{\"action\":\"add\",\"name\":\"鸡蛋\",\"quantity\":\"3\"}")), now);
        runStore.create(run(runId, AgentStatus.RUNNING, AgentNode.TOOL_EXECUTE, AgentNode.TOOL_EXECUTE, now),
                new AgentCheckpoint(runId, 0, state, now));

        AgentKitchenToolService kitchenToolService = mock(AgentKitchenToolService.class);
        when(kitchenToolService.isMutation(any(), any())).thenReturn(true);
        when(kitchenToolService.actionType(any(), any())).thenReturn("PANTRY_ADD");
        when(kitchenToolService.actionPayload(any())).thenReturn(com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode());
        when(kitchenToolService.impact(any(), any())).thenReturn("会新增一条库存记录");
        AgentConfirmationMapper confirmationMapper = mock(AgentConfirmationMapper.class);
        AgentFaultInjector crashingInjector = mock(AgentFaultInjector.class);
        doThrow(new AgentFaultInjector.AgentCrashException(runId, AgentFaultInjector.Point.WAITING_CONFIRMATION))
                .when(crashingInjector).hit(runId, AgentFaultInjector.Point.WAITING_CONFIRMATION);

        AgentService crashingService = service(mock(AgentConversationMapper.class), mock(AgentMessageMapper.class),
                confirmationMapper, mock(QwenAgentClient.class), otherIntentRecognizer(), runStore,
                kitchenToolService, crashingInjector);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> crashingService.resume(runId, "process-1"))
                .isInstanceOf(AgentFaultInjector.AgentCrashException.class);
        assertThat(runStore.findCheckpoint(runId).orElseThrow().state().nextNode())
                .isEqualTo(AgentNode.WAITING_CONFIRMATION);

        AgentService recoveredService = service(mock(AgentConversationMapper.class), mock(AgentMessageMapper.class),
                confirmationMapper, mock(QwenAgentClient.class), otherIntentRecognizer(), runStore,
                kitchenToolService, new AgentFaultInjector());
        recoveredService.resume(runId, "process-2");

        assertThat(runStore.findRun(runId).orElseThrow().status()).isEqualTo(AgentStatus.WAITING_CONFIRMATION);
        verify(confirmationMapper, times(1)).insert(any(AgentConfirmation.class));
        verify(kitchenToolService, never()).execute(any(), any(), any(), any());
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
                mock(AgentKitchenToolService.class),
                new AgentFaultInjector()
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
        return service(conversationMapper, messageMapper, qwenAgentClient, intentRecognizer, runStore,
                kitchenToolService, new AgentFaultInjector());
    }

    private AgentService service(
            AgentConversationMapper conversationMapper,
            AgentMessageMapper messageMapper,
            QwenAgentClient qwenAgentClient,
            AgentIntentRecognizer intentRecognizer,
            AgentRunStore runStore,
            AgentKitchenToolService kitchenToolService,
            AgentFaultInjector faultInjector
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
                runStore,
                faultInjector
        );
    }

    private AgentService service(
            AgentConversationMapper conversationMapper,
            AgentMessageMapper messageMapper,
            AgentConfirmationMapper confirmationMapper,
            QwenAgentClient qwenAgentClient,
            AgentIntentRecognizer intentRecognizer,
            AgentRunStore runStore,
            AgentKitchenToolService kitchenToolService,
            AgentFaultInjector faultInjector
    ) {
        AgentIntentAuditService auditService = new AgentIntentAuditService(runStore, objectMapper);
        return new AgentService(
                conversationMapper,
                messageMapper,
                confirmationMapper,
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
                runStore,
                faultInjector
        );
    }

    private AgentIntentRecognizer otherIntentRecognizer() {
        AgentIntentRecognizer intentRecognizer = mock(AgentIntentRecognizer.class);
        when(intentRecognizer.recognize(any(), anyList())).thenReturn(
                new AgentIntentRecognizer.RecognitionResult(false, false, "OTHER", 0.99d, "NONE", "not_candidate"));
        return intentRecognizer;
    }

    private AgentState state(
            String runId,
            AgentNode currentNode,
            AgentNode nextNode,
            List<QwenAgentClient.ConversationMessage> messages,
            List<QwenAgentClient.ToolCall> pendingToolCalls,
            Instant now
    ) {
        return new AgentState(
                runId,
                7L,
                42L,
                "测试 Agent 恢复",
                false,
                AgentStatus.RUNNING,
                currentNode,
                nextNode,
                pendingToolCalls.isEmpty() ? 0 : 1,
                0,
                0,
                false,
                messages,
                pendingToolCalls,
                0,
                null,
                now
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
