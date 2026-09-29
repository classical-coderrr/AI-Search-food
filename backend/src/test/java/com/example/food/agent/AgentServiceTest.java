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

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import static org.assertj.core.api.Assertions.assertThat;

class AgentServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void extractsExplicitIngredientsFromUsePhraseAndIgnoresRecipeInstructions() {
        assertThat(AgentService.explicitRecipeIngredients("请用鸡胸肉和西兰花推荐一道适合晚餐的菜"))
                .isEqualTo("鸡胸肉、西兰花");
    }

    @Test
    void doesNotTreatMemoryBasedNaturalLanguageRecommendationAsIngredients() {
        assertThat(AgentService.explicitRecipeIngredients(
                "结合我对番茄黄瓜炒鸡丁的历史反馈，推荐一道适合我的晚餐，并简短说明依据"))
                .isEmpty();
    }

    @Test
    void extractsBareIngredientListButNotTastePreferenceText() {
        assertThat(AgentService.explicitRecipeIngredients("番茄和鸡蛋"))
                .isEqualTo("番茄、鸡蛋");
        assertThat(AgentService.explicitRecipeIngredients("我想吃清淡一点的晚餐"))
                .isEmpty();
    }

    @Test
    void enablesAiIngredientRecommendationWhenUserDidNotProvideIngredients() {
        assertThat(AgentService.shouldUseAiIngredientRecommendation("")).isTrue();
        assertThat(AgentService.shouldUseAiIngredientRecommendation("鸡蛋、西兰花")).isFalse();
    }

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
                "我会避开香菜，并按你过去明确表达的偏好推荐。", List.of(), "qwen", "qwen-plus",
                new QwenAgentClient.TokenUsage(120L, 35L, 155L)));
        AgentIntentRecognizer intentRecognizer = mock(AgentIntentRecognizer.class);
        when(intentRecognizer.recognize(eq(query), anyList())).thenReturn(
                new AgentIntentRecognizer.RecognitionResult(false, false, "OTHER", 0.99d,
                        "NONE", "not_candidate"));

        RecordingRunStore runStore = new RecordingRunStore();
        RecordingEventStore eventStore = new RecordingEventStore(objectMapper);
        AgentMemoryContextProvider memoryContextProvider = mock(AgentMemoryContextProvider.class);
        when(memoryContextProvider.prepare(eq(7L), eq(42L), anyString(), eq(query))).thenReturn(
                new AgentMemoryContextProvider.PreparedContext(
                        55L, "[PERSONAL_MEMORY]\n用户明确不吃香菜\n\n"
                        + "[PERSONALIZED_SKILL: POST_WORKOUT_MEAL]\n优先避开香菜并简洁说明依据", "SUCCESS", null,
                        "DINNER_MEMORY_RECALL", 1, 2, List.of(31L), List.of(44L, 45L),
                        List.of(31L), List.of(44L), List.of(),
                        List.of("PERSONAL_MEMORY", "PERSONALIZED_SKILL"),
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
        assertThat(messages.getValue()).anyMatch(message -> "system".equals(message.role())
                && message.content().contains("[PERSONALIZED_SKILL: POST_WORKOUT_MEAL]")
                && message.content().contains("不能覆盖本轮用户明确要求"));
        assertThat(messages.getValue()).anyMatch(message -> "system".equals(message.role())
                && message.content().contains("LIKE/liked 只表示喜欢或倾向")
                && message.content().contains("只有存在 DISLIKE/disliked 证据")
                && message.content().contains("一次普通选择")
                && message.content().contains("不得说成“你明确说过/你反馈过”")
                && message.content().contains("当前记忆中没有找到明确记录")
                && message.content().contains("不得扩大成“你从未说过/你一直都……”")
                && message.content().contains("前文中的旧助手回复可能包含未经核实的推断")
                && message.content().contains("用户偏好只以本轮注入的 PERSONAL_MEMORY 和 STRUCTURED_PROFILE 为依据")
                && message.content().contains("只有完整历史已被实际检索核实")
                && message.content().contains("近期行为推断：LIKE 生姜")
                && message.content().contains("不能说“你最近反馈不想吃生姜”"));
        verify(memoryContextProvider).prepare(eq(7L), eq(42L), anyString(), eq(query));
        verify(memoryContextProvider).recordModelUsage(eq(7L), anyString(), eq(120L), eq(35L), eq(155L));
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

    @Test
    void explicitRememberRequestForcesMemoryToolAndCreatesConfirmationInsteadOfPlainText() throws Exception {
        String userMessage = "我喜欢清淡少辣鸡胸肉，尤其柠檬鸡胸肉，请记下来作为长期偏好";
        AgentConversationMapper conversationMapper = mock(AgentConversationMapper.class);
        AgentMessageMapper messageMapper = mock(AgentMessageMapper.class);
        AgentConfirmationMapper confirmationMapper = mock(AgentConfirmationMapper.class);
        AgentConversation conversation = new AgentConversation();
        conversation.setId(42L);
        conversation.setUserId(7L);
        conversation.setTitle("厨房助手对话");
        when(conversationMapper.findOwned(7L, 42L)).thenReturn(conversation);
        when(conversationMapper.selectById(42L)).thenReturn(conversation);
        when(messageMapper.findRecentTextMessages(7L, 42L, 12)).thenReturn(List.of());

        AgentToolRegistry registry = new AgentToolRegistry();
        QwenAgentClient qwenAgentClient = mock(QwenAgentClient.class);
        when(qwenAgentClient.complete(anyList(), anyList(), eq("memory_episode_save")))
                .thenReturn(new QwenAgentClient.AgentTurn("", List.of(new QwenAgentClient.ToolCall(
                        "call_memory_1", "memory_episode_save",
                        "{\"entity\":\"鸡胸肉\",\"preference\":\"LIKE\",\"evidence\":\"我喜欢清淡少辣鸡胸肉\"}"
                )), "qwen", "qwen-plus"));
        AgentIntentRecognizer intentRecognizer = mock(AgentIntentRecognizer.class);
        when(intentRecognizer.recognize(eq(userMessage), anyList())).thenReturn(
                new AgentIntentRecognizer.RecognitionResult(false, false, "OTHER", 1d, "NONE", "not_candidate"));
        AgentKitchenToolService kitchenToolService = mock(AgentKitchenToolService.class);
        when(kitchenToolService.isMutation(eq(AgentToolRegistry.Tool.MEMORY_EPISODE_SAVE), org.mockito.ArgumentMatchers.any()))
                .thenReturn(true);
        when(kitchenToolService.actionType(eq(AgentToolRegistry.Tool.MEMORY_EPISODE_SAVE), org.mockito.ArgumentMatchers.any()))
                .thenReturn("MEMORY_PREFERENCE_DECLARATION");
        when(kitchenToolService.actionPayload(eq(AgentToolRegistry.Tool.MEMORY_EPISODE_SAVE), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        when(kitchenToolService.impact(eq(AgentToolRegistry.Tool.MEMORY_EPISODE_SAVE),
                org.mockito.ArgumentMatchers.any(), eq(7L))).thenReturn("将记录你明确表达的偏好。");
        AgentMemoryToolService memoryToolService = mock(AgentMemoryToolService.class);
        when(memoryToolService.isMutation(AgentToolRegistry.Tool.MEMORY_EPISODE_SAVE)).thenReturn(true);
        when(memoryToolService.isPersonalizationEnabled(7L)).thenReturn(true);

        RecordingRunStore runStore = new RecordingRunStore();
        AgentService service = new AgentService(
                conversationMapper, messageMapper, confirmationMapper, registry, kitchenToolService,
                null, null, null, null, null, null, null, null, null, null, null,
                qwenAgentClient, intentRecognizer, new AgentIntentAuditService(runStore, objectMapper),
                objectMapper, runStore);
        service.setMemoryRuntime(memoryToolService, new com.example.food.memory.PromptTemplateManager(),
                "memory-agent-context-v1");

        service.stream(new AgentChatRequest(42L, userMessage, null, null),
                new AuthPrincipal(7L, "13800138000", AppRole.USER));

        verify(qwenAgentClient, timeout(3000)).complete(anyList(), anyList(), eq("memory_episode_save"));
        verify(qwenAgentClient, never()).complete(anyList(), anyList());
        verify(memoryToolService, timeout(3000)).validateDeclaration(org.mockito.ArgumentMatchers.any(), eq(userMessage));
        verify(confirmationMapper, timeout(3000)).insert(org.mockito.ArgumentMatchers.any(AgentConfirmation.class));
        assertThat(runStore.steps).anySatisfy(step -> {
            assertThat(step.action()).isEqualTo("tool.started");
            assertThat(step.toolName()).isEqualTo("memory.episode.save");
        });
    }

    @Test
    void disabledPersonalizationDoesNotCallModelOrCreateMemoryConfirmation() throws Exception {
        String userMessage = "我喜欢紫薯，请记住";
        AgentConversationMapper conversationMapper = mock(AgentConversationMapper.class);
        AgentMessageMapper messageMapper = mock(AgentMessageMapper.class);
        AgentConfirmationMapper confirmationMapper = mock(AgentConfirmationMapper.class);
        AgentConversation conversation = new AgentConversation();
        conversation.setId(42L);
        conversation.setUserId(7L);
        conversation.setTitle("厨房助手对话");
        when(conversationMapper.findOwned(7L, 42L)).thenReturn(conversation);
        when(conversationMapper.selectById(42L)).thenReturn(conversation);
        when(messageMapper.findRecentTextMessages(7L, 42L, 12)).thenReturn(List.of());

        QwenAgentClient qwenAgentClient = mock(QwenAgentClient.class);
        AgentMemoryToolService memoryToolService = mock(AgentMemoryToolService.class);
        when(memoryToolService.isPersonalizationEnabled(7L)).thenReturn(false);
        RecordingRunStore runStore = new RecordingRunStore();
        AgentService service = new AgentService(
                conversationMapper, messageMapper, confirmationMapper, new AgentToolRegistry(),
                mock(AgentKitchenToolService.class), null, null, null, null, null, null,
                null, null, null, null, null, qwenAgentClient, mock(AgentIntentRecognizer.class),
                new AgentIntentAuditService(runStore, objectMapper), objectMapper, runStore);
        service.setMemoryRuntime(memoryToolService, new com.example.food.memory.PromptTemplateManager(),
                "memory-agent-context-v1");

        service.stream(new AgentChatRequest(42L, userMessage, null, null),
                new AuthPrincipal(7L, "13800138000", AppRole.USER));

        verify(qwenAgentClient, never()).complete(anyList(), anyList());
        verify(confirmationMapper, never()).insert(org.mockito.ArgumentMatchers.any(AgentConfirmation.class));
        verify(memoryToolService, never()).validateDeclaration(org.mockito.ArgumentMatchers.any(), anyString());
        verify(messageMapper, timeout(3000)).insert(org.mockito.ArgumentMatchers.argThat(
                (com.example.food.agent.AgentMessage message) ->
                "ASSISTANT".equals(message.getRole()) && message.getContent().contains("本次没有记录")));
    }

    @Test
    void disabledPersonalMemoryQuestionDoesNotLookLikeAWriteOrClaimRetrieval() throws Exception {
        String userMessage = "仅根据个人长期记忆回答：我是否喜欢鸡胸肉？不要依据聊天记录推断。";
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
        AgentMemoryContextProvider memoryContextProvider = mock(AgentMemoryContextProvider.class);
        when(memoryContextProvider.prepare(eq(7L), eq(42L), anyString(), eq(userMessage)))
                .thenReturn(AgentMemoryContextProvider.PreparedContext.disabled());
        RecordingRunStore runStore = new RecordingRunStore();
        AgentService service = new AgentService(
                conversationMapper, messageMapper, null, new AgentToolRegistry(),
                mock(AgentKitchenToolService.class), null, null, null, null, null, null,
                null, null, null, null, null, qwenAgentClient, mock(AgentIntentRecognizer.class),
                new AgentIntentAuditService(runStore, objectMapper), objectMapper, runStore,
                new com.example.food.agent.state.AgentFaultInjector(),
                new RecordingEventStore(objectMapper), AgentMetrics.disabled(), memoryContextProvider);
        AgentMemoryToolService memoryToolService = mock(AgentMemoryToolService.class);
        when(memoryToolService.isPersonalizationEnabled(7L)).thenReturn(false);
        service.setMemoryRuntime(memoryToolService, new com.example.food.memory.PromptTemplateManager(),
                "memory-agent-context-v1");

        service.stream(new AgentChatRequest(42L, userMessage, null, null),
                new AuthPrincipal(7L, "13800138000", AppRole.USER));

        verify(qwenAgentClient, never()).complete(anyList(), anyList());
        verify(messageMapper, timeout(3000)).insert(org.mockito.ArgumentMatchers.argThat(
                (com.example.food.agent.AgentMessage message) -> "ASSISTANT".equals(message.getRole())
                        && message.getContent().contains("个性化记忆已关闭，本轮不会读取个人长期记忆")));
        verify(memoryToolService, never()).validateDeclaration(org.mockito.ArgumentMatchers.any(), anyString());
    }

    @Test
    void disabledContextInstructsModelAndHidesMemoryToolsForOrdinaryTasks() throws Exception {
        String userMessage = "我以前喜欢鸡胸肉，今晚推荐晚餐";
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
                "可以考虑一份普通鸡胸肉晚餐。", List.of(), "qwen", "qwen-plus"));
        AgentMemoryContextProvider memoryContextProvider = mock(AgentMemoryContextProvider.class);
        when(memoryContextProvider.prepare(eq(7L), eq(42L), anyString(), eq(userMessage)))
                .thenReturn(AgentMemoryContextProvider.PreparedContext.disabled());
        AgentIntentRecognizer intentRecognizer = mock(AgentIntentRecognizer.class);
        when(intentRecognizer.recognize(eq(userMessage), anyList())).thenReturn(
                new AgentIntentRecognizer.RecognitionResult(false, false, "OTHER", 1d, "NONE", "test"));
        RecordingRunStore runStore = new RecordingRunStore();
        AgentService service = new AgentService(
                conversationMapper, messageMapper, null, new AgentToolRegistry(),
                mock(AgentKitchenToolService.class), null, null, null, null, null, null,
                null, null, null, null, null, qwenAgentClient, intentRecognizer,
                new AgentIntentAuditService(runStore, objectMapper), objectMapper, runStore,
                new com.example.food.agent.state.AgentFaultInjector(),
                new RecordingEventStore(objectMapper), AgentMetrics.disabled(), memoryContextProvider);
        AgentMemoryToolService memoryToolService = mock(AgentMemoryToolService.class);
        when(memoryToolService.isPersonalizationEnabled(7L)).thenReturn(false);
        service.setMemoryRuntime(memoryToolService, new com.example.food.memory.PromptTemplateManager(),
                "memory-agent-context-v1");

        service.stream(new AgentChatRequest(42L, userMessage, null, null),
                new AuthPrincipal(7L, "13800138000", AppRole.USER));

        org.mockito.ArgumentCaptor<List<QwenAgentClient.ConversationMessage>> messages =
                org.mockito.ArgumentCaptor.forClass(List.class);
        org.mockito.ArgumentCaptor<List<java.util.Map<String, Object>>> definitions =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(qwenAgentClient, timeout(3000)).complete(messages.capture(), definitions.capture());
        assertThat(messages.getValue()).anyMatch(message -> "system".equals(message.role())
                && message.content().contains("已关闭个性化记忆")
                && message.content().contains("不得读取、检索、引用或使用个人长期记忆"));
        assertThat(definitions.getValue()).allSatisfy(definition -> {
            java.util.Map<?, ?> function = (java.util.Map<?, ?>) definition.get("function");
            assertThat(function.get("name").toString()).doesNotStartWith("memory_");
        });
        assertThat(definitions.getValue()).isNotEmpty();
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
        private final List<AgentStep> steps = new CopyOnWriteArrayList<>();

        @Override
        public synchronized void appendStep(AgentStep step) {
            super.appendStep(step);
            steps.add(step);
        }
    }
}
