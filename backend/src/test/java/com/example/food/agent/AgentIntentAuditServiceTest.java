package com.example.food.agent;

import com.example.food.agent.state.AgentRunStore;
import com.example.food.agent.state.AgentStep;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AgentIntentAuditServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void recordsRuleResolutionAsACompletedAuditStepWithoutRawMessage() throws Exception {
        AgentRunStore runStore = mock(AgentRunStore.class);
        AgentIntentAuditService audit = new AgentIntentAuditService(runStore, objectMapper);

        audit.record(
                "run-1", 1L, AgentIntentAuditService.SOURCE_RULE,
                "SAVE_RECIPE", 1.2d, "LATEST_GENERATED",
                "规则命中", true, false, 8, false
        );

        var captor = forClass(AgentStep.class);
        verify(runStore).appendStep(captor.capture());
        AgentStep step = captor.getValue();
        JsonNode request = objectMapper.readTree(step.requestJson());
        JsonNode response = objectMapper.readTree(step.responseJson());

        assertThat(step.stepNo()).isEqualTo(1L);
        assertThat(step.action()).isEqualTo("intent.resolved");
        assertThat(step.toolName()).isEqualTo("agent_intent_rule");
        assertThat(step.status()).isEqualTo("SUCCESS");
        assertThat(step.idempotencyKey()).isEqualTo("run-1:intent-resolution");
        assertThat(request.path("messageLength").asInt()).isEqualTo(8);
        assertThat(request.has("message")).isFalse();
        assertThat(response.path("intent").asText()).isEqualTo("SAVE_RECIPE");
        assertThat(response.path("confidence").asDouble()).isEqualTo(1d);
        assertThat(response.path("route").asText()).isEqualTo("SAVE_TOOL_EXPOSED");
    }

    @Test
    void recordsModelFallbackAsFallbackAndKeepsTheNormalDialogueRoute() throws Exception {
        AgentRunStore runStore = mock(AgentRunStore.class);
        AgentIntentAuditService audit = new AgentIntentAuditService(runStore, objectMapper);

        audit.record(
                "run-2", 2L, AgentIntentAuditService.SOURCE_MODEL_FALLBACK,
                "OTHER", 0d, "NONE", "invalid_response",
                false, true, 4, true
        );

        var captor = forClass(AgentStep.class);
        verify(runStore).appendStep(captor.capture());
        AgentStep step = captor.getValue();

        assertThat(step.toolName()).isEqualTo("agent_intent_classify");
        assertThat(step.status()).isEqualTo("FALLBACK");
        assertThat(objectMapper.readTree(step.responseJson()).path("route").asText())
                .isEqualTo("NORMAL_DIALOGUE");
        assertThat(objectMapper.readTree(step.responseJson()).path("reason").asText())
                .isEqualTo("invalid_response");
    }
}
