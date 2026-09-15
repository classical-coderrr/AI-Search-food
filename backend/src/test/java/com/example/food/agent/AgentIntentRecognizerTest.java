package com.example.food.agent;

import com.example.food.ai.qwen.QwenAgentClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentIntentRecognizerTest {

    @Test
    void usesStructuredHighConfidenceSaveIntentFromClassifierTool() {
        QwenAgentClient client = mock(QwenAgentClient.class);
        when(client.complete(anyList(), anyList())).thenReturn(new QwenAgentClient.AgentTurn(
                "",
                List.of(new QwenAgentClient.ToolCall(
                        "intent-1",
                        "agent_intent_classify",
                        "{\"intent\":\"SAVE_RECIPE\",\"confidence\":0.96,\"recipe_reference\":\"LATEST_GENERATED\",\"reason\":\"指代刚生成的菜\"}"
                )),
                "qwen",
                "qwen-plus"
        ));
        AgentIntentRecognizer recognizer = new AgentIntentRecognizer(
                client,
                new ObjectMapper(),
                properties()
        );

        AgentIntentRecognizer.RecognitionResult result = recognizer.recognize(
                "帮我把刚才那份留着",
                List.of(QwenAgentClient.ConversationMessage.assistant("刚刚生成了番茄炒蛋"))
        );

        assertThat(result.available()).isTrue();
        assertThat(result.isSaveRecipe()).isTrue();
        assertThat(result.intent()).isEqualTo("SAVE_RECIPE");
        assertThat(result.confidence()).isEqualTo(0.96d);
        assertThat(result.recipeReference()).isEqualTo("LATEST_GENERATED");
        assertThat(result.auditSource()).isEqualTo(AgentIntentAuditService.SOURCE_MODEL);
        assertThat(result.modelCalled()).isTrue();
        verify(client).complete(anyList(), anyList());
    }

    @Test
    void rejectsLowConfidenceOrMissingRecipeReference() {
        QwenAgentClient client = mock(QwenAgentClient.class);
        when(client.complete(anyList(), anyList())).thenReturn(new QwenAgentClient.AgentTurn(
                "",
                List.of(new QwenAgentClient.ToolCall(
                        "intent-2",
                        "agent_intent_classify",
                        "{\"intent\":\"SAVE_RECIPE\",\"confidence\":0.84,\"recipe_reference\":\"LATEST_GENERATED\"}"
                )),
                "qwen",
                "qwen-plus"
        ));
        AgentIntentRecognizer recognizer = new AgentIntentRecognizer(client, new ObjectMapper(), properties());

        AgentIntentRecognizer.RecognitionResult result = recognizer.recognize("收藏一下", List.of());

        assertThat(result.available()).isTrue();
        assertThat(result.isSaveRecipe()).isFalse();
    }

    @Test
    void rejectsUnknownRecipeReferenceEvenWhenConfidenceIsHigh() {
        QwenAgentClient client = mock(QwenAgentClient.class);
        when(client.complete(anyList(), anyList())).thenReturn(new QwenAgentClient.AgentTurn(
                "",
                List.of(new QwenAgentClient.ToolCall(
                        "intent-unknown-reference",
                        "agent_intent_classify",
                        "{\"intent\":\"SAVE_RECIPE\",\"confidence\":0.99,\"recipe_reference\":\"UNKNOWN\"}"
                )),
                "qwen",
                "qwen-plus"
        ));
        AgentIntentRecognizer recognizer = new AgentIntentRecognizer(client, new ObjectMapper(), properties());

        AgentIntentRecognizer.RecognitionResult result = recognizer.recognize("收藏一下", List.of());

        assertThat(result.available()).isTrue();
        assertThat(result.isSaveRecipe()).isFalse();
    }

    @Test
    void doesNotCallModelForMessagesWithoutSaveLikeAction() {
        QwenAgentClient client = mock(QwenAgentClient.class);
        AgentIntentRecognizer recognizer = new AgentIntentRecognizer(client, new ObjectMapper(), properties());

        AgentIntentRecognizer.RecognitionResult result = recognizer.recognize("查看我的菜谱", List.of());

        assertThat(result.available()).isFalse();
        assertThat(result.reason()).isEqualTo("not_candidate");
        assertThat(result.auditSource()).isEqualTo(AgentIntentAuditService.SOURCE_RULE_GATE);
        assertThat(result.modelCalled()).isFalse();
        org.mockito.Mockito.verifyNoInteractions(client);
    }

    @Test
    void failsClosedWhenModelReturnsInvalidStructuredIntent() {
        QwenAgentClient client = mock(QwenAgentClient.class);
        when(client.complete(anyList(), anyList())).thenReturn(new QwenAgentClient.AgentTurn(
                "我觉得应该保存",
                List.of(),
                "qwen",
                "qwen-plus"
        ));
        AgentIntentRecognizer recognizer = new AgentIntentRecognizer(client, new ObjectMapper(), properties());

        AgentIntentRecognizer.RecognitionResult result = recognizer.recognize("收藏一下", List.of());

        assertThat(result.available()).isFalse();
        assertThat(result.isSaveRecipe()).isFalse();
        assertThat(result.reason()).isEqualTo("invalid_response");
    }

    private AgentIntentProperties properties() {
        AgentIntentProperties properties = new AgentIntentProperties();
        properties.setEnabled(true);
        properties.setConfidenceThreshold(0.85d);
        return properties;
    }
}
