package com.example.food.agent;

import com.example.food.agent.AgentToolRegistry.Tool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentKitchenToolServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void preservesTopLevelArgumentsWhenRequestingMemoryWriteConfirmation() throws Exception {
        JsonNode arguments = objectMapper.readTree("""
                {"entity":"鸡胸肉","preference":"LIKE","evidence":"我喜欢鸡胸肉"}
                """);

        assertThat(service().actionPayload(Tool.MEMORY_EPISODE_SAVE, arguments)).isEqualTo(arguments);
        assertThat(service().actionPayload(Tool.MEMORY_PREFERENCE_UPDATE, arguments)).isEqualTo(arguments);
    }

    @Test
    void keepsNestedPayloadContractForExistingKitchenActions() throws Exception {
        JsonNode arguments = objectMapper.readTree("""
                {"action":"create","payload":{"ingredientName":"鸡蛋","quantity":2}}
                """);

        assertThat(service().actionPayload(Tool.PANTRY_MANAGE, arguments))
                .isEqualTo(arguments.path("payload"));
    }

    private AgentKitchenToolService service() {
        return new AgentKitchenToolService(
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, objectMapper);
    }
}
