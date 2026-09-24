package com.example.food.agent;

import com.example.food.ai.qwen.QwenAgentClient;
import com.example.food.ai.recipe.dto.RecipeGenerateResponse;
import com.example.food.agent.dto.AgentChatRequest;
import com.example.food.agent.state.AgentEvent;
import com.example.food.agent.state.AgentNode;
import com.example.food.agent.state.AgentState;
import com.example.food.agent.state.AgentStatus;
import com.example.food.agent.state.AgentStep;
import com.example.food.agent.state.InMemoryAgentEventStore;
import com.example.food.agent.state.InMemoryAgentRunStore;
import com.example.food.security.AppRole;
import com.example.food.security.AuthPrincipal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import static org.assertj.core.api.Assertions.assertThat;

class AgentServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void prefersIngredientArrayWhenRecipeToolProvidesIt() throws Exception {
        JsonNode arguments = objectMapper.readTree("""
                {
                  "ingredients": ["番茄", "茄子", "鸡肉", "牛肉", "螃蟹"],
                  "meal_type": "dinner",
                  "servings": 3
                }
                """);

        assertThat(AgentService.recipeIngredientArgument(arguments, "今晚能做什么"))
                .isEqualTo("番茄、茄子、鸡肉、牛肉、螃蟹");
    }

    @Test
    void fallsBackToRequestTextWhenIngredientArrayIsMissing() throws Exception {
        JsonNode arguments = objectMapper.readTree("{\"meal_type\":\"dinner\"}");

        assertThat(AgentService.recipeIngredientArgument(arguments, "番茄和鸡蛋"))
                .isEqualTo("番茄和鸡蛋");
    }

    @Test
    void keepsIntentRoutingFlagsWhenCheckpointIsSerializedAndRestored() throws Exception {
        AgentState state = new AgentState(
                "run-state", 7L, 42L, "收藏一下", false,
                AgentStatus.RUNNING, AgentNode.MODEL_DECISION, AgentNode.MODEL_DECISION,
                1, 0, 1, false, List.of(), List.of(), 0, null, null,
                true, true, AgentIntentAuditService.SOURCE_MODEL,
                false, false, null,
                123L, "番茄炒蛋"
        );

        AgentState restored = objectMapper.readValue(
                objectMapper.writeValueAsString(state),
                AgentState.class
        );

        assertThat(restored.intentResolutionAttempted()).isTrue();
        assertThat(restored.recipeSaveIntent()).isTrue();
        assertThat(restored.intentResolutionSource()).isEqualTo(AgentIntentAuditService.SOURCE_MODEL);
        assertThat(restored.targetRecipeSearchLogId()).isEqualTo(123L);
        assertThat(restored.previousRecipeTitle()).isEqualTo("番茄炒蛋");
    }

    @Test
    void usesStableSaveKeyForTheSameGeneratedRecipe() throws Exception {
        RecipeGenerateResponse recipe = new RecipeGenerateResponse(
                "番茄炒蛋",
                "家常快手菜",
                List.of(),
                List.of(new RecipeGenerateResponse.Ingredient("番茄", "2个")),
                List.of(new RecipeGenerateResponse.Step(1, "翻炒", "翻炒至熟", 5)),
                List.of(),
                List.of(),
                "qwen",
                "qwen-plus",
                42L
        );

        assertThat(AgentService.recipeSaveIdempotencyKey(recipe)).isEqualTo("recipe-save-42");
    }

    @Test
    void persistsModelIntentResolutionAsTheFirstAgentStep() throws Exception {
        AgentConversationMapper conversationMapper = mock(AgentConversationMapper.class);
        AgentMessageMapper messageMapper = mock(AgentMessageMapper.class);
        AgentConversation conversation = new AgentConversation();
        conversation.setId(42L);
        conversation.setUserId(7L);
        conversation.setTitle("厨房助手对话");
        when(conversationMapper.findOwned(7L, 42L)).thenReturn(conversation);
        when(conversationMapper.selectById(42L)).thenReturn(conversation);
        when(messageMapper.findRecentTextMessages(7L, 42L, 12)).thenReturn(List.of());

        QwenAgentClient qwenAgentClient = mock(QwenAgentClient.class);
        when(qwenAgentClient.complete(anyList(), anyList())).thenReturn(new QwenAgentClient.AgentTurn(
                "可以的",
                List.of(),
                "qwen",
                "qwen-plus"
        ));
        AgentIntentRecognizer intentRecognizer = mock(AgentIntentRecognizer.class);
        when(intentRecognizer.recognize(eq("收藏一下"), anyList())).thenReturn(
                new AgentIntentRecognizer.RecognitionResult(
                        true, true, "SAVE_RECIPE", 0.96d,
                        "LATEST_GENERATED", "指代刚生成的菜"
                )
        );

        RecordingRunStore runStore = new RecordingRunStore();
        AgentService service = new AgentService(
                conversationMapper,
                messageMapper,
                null,
                new AgentToolRegistry(),
                mock(AgentKitchenToolService.class),
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
                new AgentIntentAuditService(runStore, objectMapper),
                objectMapper,
                runStore
        );

        service.stream(
                new AgentChatRequest(42L, "收藏一下", null, null),
                new AuthPrincipal(7L, "13800138000", AppRole.USER)
        );

        long deadline = System.currentTimeMillis() + 3000L;
        while (runStore.steps.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20L);
        }

        assertThat(runStore.steps).isNotEmpty();
        AgentStep intentStep = runStore.steps.get(0);
        assertThat(intentStep.action()).isEqualTo(AgentIntentAuditService.ACTION);
        assertThat(intentStep.toolName()).isEqualTo("agent_intent_classify");
        assertThat(intentStep.status()).isEqualTo("SUCCESS");
        assertThat(objectMapper.readTree(intentStep.responseJson()).path("confidence").asDouble())
                .isEqualTo(0.96d);
    }

    @Test
    void injectsRetrievedMemoryIntoModelContextAndPersistsTheUsedMemoryTrace() throws Exception {
        String query = "今晚推荐一道清淡的鸡肉晚餐";
        AgentConversationMapper conversationMapper = mock(AgentConversationMapper.class);
        AgentMessageMapper messageMapper = mock(AgentMessageMapper.class);
        AgentConversation conversation = new AgentConversation();
        conversation.setId(42L);
        conversation.setUserId(7L);
        conversation.setTitle("厨房助手对话");
        when(conversationMapper.findOwned(7L, 42L)).thenReturn(conversation);
        when(conversationMapper.selectById(42L)).thenReturn(conversation);
        when(messageMapper.findRecentTextMessages(7L, 42L, 12)).thenReturn(List.of());

        QwenAgentClient qwenAgentClient = mock(QwenAgentClient.class);
        when(qwenAgentClient.complete(anyList(), anyList())).thenReturn(new QwenAgentClient.AgentTurn(
                "我会避开香菜，并按你过去明确表达的偏好推荐。", List.of(), "qwen", "qwen-plus"));
        AgentIntentRecognizer intentRecognizer = mock(AgentIntentRecognizer.class);
        when(intentRecognizer.recognize(eq(query), anyList())).thenReturn(
                new AgentIntentRecognizer.RecognitionResult(false, false, "OTHER", 0.99d,
                        "NONE", "not_candidate"));

        RecordingRunStore runStore = new RecordingRunStore();
        RecordingEventStore eventStore = new RecordingEventStore(objectMapper);
        AgentMemoryContextProvider memoryContextProvider = mock(AgentMemoryContextProvider.class);
        when(memoryContextProvider.prepare(eq(7L), eq(42L), anyString(), eq(query))).thenReturn(
                new AgentMemoryContextProvider.PreparedContext(
                        55L, "[PERSONAL_MEMORY]\n用户明确不吃香菜", "SUCCESS", null,
                        "DINNER_MEMORY_RECALL", 1, 2, List.of(31L), List.of(44L, 45L),
                        List.of(31L), List.of(44L), List.of(), List.of("PERSONAL_MEMORY"),
                        List.of("长期偏好：不喜欢香菜", "历史行为：收藏过清淡鸡肉晚餐"),
                        38, 1800, false));

        AgentService service = new AgentService(
                conversationMapper, messageMapper, null, new AgentToolRegistry(),
                mock(AgentKitchenToolService.class), null, null, null, null, null, null,
                null, null, null, null, null, qwenAgentClient, intentRecognizer,
                new AgentIntentAuditService(runStore, objectMapper), objectMapper, runStore,
                new com.example.food.agent.state.AgentFaultInjector(),
                eventStore,
                AgentMetrics.disabled(), memoryContextProvider);

        service.stream(new AgentChatRequest(42L, query, null, null),
                new AuthPrincipal(7L, "13800138000", AppRole.USER));

        long deadline = System.currentTimeMillis() + 3000L;
        while (!(runStore.steps.stream().anyMatch(step -> "memory.context".equals(step.action()))
                && runStore.steps.stream().anyMatch(step -> "model.result".equals(step.action())))
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(20L);
        }

        org.mockito.ArgumentCaptor<List<QwenAgentClient.ConversationMessage>> messages =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(qwenAgentClient).complete(messages.capture(), anyList());
        assertThat(messages.getValue()).anyMatch(message -> "system".equals(message.role())
                && message.content().contains("用户明确不吃香菜"));
        verify(memoryContextProvider).prepare(eq(7L), eq(42L), anyString(), eq(query));
        assertThat(runStore.steps).filteredOn(step -> "memory.context".equals(step.action()))
                .singleElement().satisfies(step -> {
            assertThat(step.status()).isEqualTo("SUCCESS");
            assertThat(step.responseJson()).contains("31", "44", "DINNER_MEMORY_RECALL");
        });
        assertThat(eventStore.captured).anySatisfy(event -> {
            assertThat(event.event()).isEqualTo("tool.result");
            assertThat(event.dataJson()).contains("长期偏好：不喜欢香菜");
        });
    }

    private static final class RecordingEventStore extends InMemoryAgentEventStore {
        private final List<AgentEvent> captured = new CopyOnWriteArrayList<>();

        private RecordingEventStore(ObjectMapper objectMapper) {
            super(objectMapper);
        }

        @Override
        public AgentEvent append(String runId, String event, Object data) {
            AgentEvent saved = super.append(runId, event, data);
            captured.add(saved);
            return saved;
        }
    }

    private static final class RecordingRunStore extends InMemoryAgentRunStore {
        private final List<AgentStep> steps = new ArrayList<>();

        @Override
        public synchronized void appendStep(AgentStep step) {
            super.appendStep(step);
            steps.add(step);
        }
    }
}
