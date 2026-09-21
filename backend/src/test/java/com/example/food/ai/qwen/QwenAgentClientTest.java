package com.example.food.ai.qwen;

import com.example.food.ai.config.AiModelConfigService;
import com.example.food.ai.config.AiModelRuntimeConfig;
import com.example.food.agent.AgentToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class QwenAgentClientTest {

    @Test
    void sendsRegisteredToolsAndParsesToolCall() {
        RestTemplate restTemplate = new RestTemplateBuilder().build();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        QwenProperties properties = properties("test-api-key");
        QwenAgentClient client = new QwenAgentClient(restTemplate, new ObjectMapper(), properties);
        AgentToolRegistry registry = new AgentToolRegistry();

        server.expect(once(), requestTo(properties.endpoint()))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer test-api-key"))
                .andExpect(jsonPath("$.model").value("qwen-plus"))
                .andExpect(jsonPath("$.max_tokens").value(900))
                .andExpect(jsonPath("$.tool_choice").value("auto"))
                .andExpect(jsonPath("$.parallel_tool_calls").value(false))
                .andExpect(jsonPath("$.messages[0].role").value("system"))
                .andExpect(jsonPath("$.messages[1].content").value("我的库存有什么？"))
                .andExpect(jsonPath("$.tools[0].type").value("function"))
                .andRespond(withSuccess("""
                        {
                          "choices": [{
                            "message": {
                              "content": "",
                              "tool_calls": [{
                                "id": "call_inventory_1",
                                "type": "function",
                                "function": {"name": "pantry_list", "arguments": "{}"}
                              }]
                            }
                          }]
                        }
                        """, MediaType.APPLICATION_JSON));

        QwenAgentClient.AgentTurn turn = client.complete(
                List.of(QwenAgentClient.ConversationMessage.user("我的库存有什么？")),
                registry.functionDefinitions()
        );

        assertThat(turn.content()).isEmpty();
        assertThat(turn.provider()).isEqualTo("qwen");
        assertThat(turn.model()).isEqualTo("qwen-plus");
        assertThat(turn.toolCalls()).containsExactly(
                new QwenAgentClient.ToolCall("call_inventory_1", "pantry_list", "{}")
        );
        server.verify();
    }

    @Test
    void disablesThinkingForQwen3AgentRequests() {
        RestTemplate restTemplate = new RestTemplateBuilder().build();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        QwenProperties properties = new QwenProperties(
                "test-api-key",
                "qwen3.8-max",
                "https://dashscope.test/compatible-mode/v1/chat/completions"
        );
        QwenAgentClient client = new QwenAgentClient(restTemplate, new ObjectMapper(), properties);

        server.expect(once(), requestTo(properties.endpoint()))
                .andExpect(jsonPath("$.enable_thinking").value(false))
                .andExpect(jsonPath("$.max_tokens").value(900))
                .andRespond(withSuccess(
                        "{\"choices\":[{\"message\":{\"content\":\"收到\"}}]}",
                        MediaType.APPLICATION_JSON
                ));

        assertThat(client.complete(
                List.of(QwenAgentClient.ConversationMessage.user("你好")),
                List.of()
        ).content()).isEqualTo("收到");
        server.verify();
    }

    @Test
    void normalizesDirectQwenToolCallWithoutIdOrNestedFunction() {
        RestTemplate restTemplate = new RestTemplateBuilder().build();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        QwenProperties properties = properties("test-api-key");
        QwenAgentClient client = new QwenAgentClient(restTemplate, new ObjectMapper(), properties);

        server.expect(once(), requestTo(properties.endpoint()))
                .andRespond(withSuccess("""
                        {
                          "choices": [{
                            "message": {
                              "content": "",
                              "tool_calls": [{
                                "type": "function",
                                "name": "pantry_list",
                                "arguments": {}
                              }]
                            }
                          }]
                        }
                        """, MediaType.APPLICATION_JSON));

        QwenAgentClient.AgentTurn turn = client.complete(
                List.of(QwenAgentClient.ConversationMessage.user("我的库存有什么？")),
                new AgentToolRegistry().functionDefinitions()
        );

        assertThat(turn.toolCalls()).containsExactly(
                new QwenAgentClient.ToolCall("qwen_tool_call_1", "pantry_list", "{}")
        );
        server.verify();
    }

    @Test
    void parsesResponsesApiFunctionCallShape() {
        RestTemplate restTemplate = new RestTemplateBuilder().build();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        QwenProperties properties = properties("test-api-key");
        QwenAgentClient client = new QwenAgentClient(restTemplate, new ObjectMapper(), properties);

        server.expect(once(), requestTo(properties.endpoint()))
                .andRespond(withSuccess("""
                        {
                          "output_text": "",
                          "output": [{
                            "type": "function_call",
                            "call_id": "call_response_1",
                            "name": "pantry_list",
                            "arguments": "{}"
                          }]
                        }
                        """, MediaType.APPLICATION_JSON));

        QwenAgentClient.AgentTurn turn = client.complete(
                List.of(QwenAgentClient.ConversationMessage.user("我的库存有什么？")),
                new AgentToolRegistry().functionDefinitions()
        );

        assertThat(turn.toolCalls()).containsExactly(
                new QwenAgentClient.ToolCall("call_response_1", "pantry_list", "{}")
        );
        server.verify();
    }

    @Test
    void ignoresMalformedStructuredCallWhenTextAnswerIsUsable() {
        RestTemplate restTemplate = new RestTemplateBuilder().build();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        QwenProperties properties = properties("test-api-key");
        QwenAgentClient client = new QwenAgentClient(restTemplate, new ObjectMapper(), properties);

        server.expect(once(), requestTo(properties.endpoint()))
                .andRespond(withSuccess("""
                        {
                          "choices": [{
                            "message": {
                              "content": "我可以帮你安排晚餐。",
                              "tool_calls": [{"id": "call_bad", "function": {"arguments": "{}"}}]
                            }
                          }]
                        }
                        """, MediaType.APPLICATION_JSON));

        QwenAgentClient.AgentTurn turn = client.complete(
                List.of(QwenAgentClient.ConversationMessage.user("帮我安排晚餐")),
                new AgentToolRegistry().functionDefinitions()
        );

        assertThat(turn.content()).isEqualTo("我可以帮你安排晚餐。");
        assertThat(turn.toolCalls()).isEmpty();
        server.verify();
    }

    @Test
    void sendsToolResultBackAndParsesFinalAnswer() {
        RestTemplate restTemplate = new RestTemplateBuilder().build();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        QwenProperties properties = properties("test-api-key");
        QwenAgentClient client = new QwenAgentClient(restTemplate, new ObjectMapper(), properties);
        QwenAgentClient.ToolCall call = new QwenAgentClient.ToolCall("call_1", "pantry_list", "{}");

        server.expect(once(), requestTo(properties.endpoint()))
                .andExpect(jsonPath("$.messages[2].tool_calls[0].id").value("call_1"))
                .andExpect(jsonPath("$.messages[3].role").value("tool"))
                .andExpect(jsonPath("$.messages[3].tool_call_id").value("call_1"))
                .andExpect(jsonPath("$.messages[3].content").value("{\"count\":2}"))
                .andRespond(withSuccess("""
                        {
                          "choices": [{
                            "message": {"content": "你目前有 2 种食材。"}
                          }]
                        }
                        """, MediaType.APPLICATION_JSON));

        QwenAgentClient.AgentTurn turn = client.complete(
                List.of(
                        QwenAgentClient.ConversationMessage.user("我的库存有什么？"),
                        QwenAgentClient.ConversationMessage.assistant(
                                new QwenAgentClient.AgentTurn("", List.of(call), "qwen", "qwen-plus")
                        ),
                        QwenAgentClient.ConversationMessage.tool("call_1", "{\"count\":2}")
                ),
                new AgentToolRegistry().functionDefinitions()
        );

        assertThat(turn.content()).isEqualTo("你目前有 2 种食材。");
        assertThat(turn.toolCalls()).isEmpty();
        server.verify();
    }

    @Test
    void refusesCallWithoutApiKey() {
        QwenAgentClient client = new QwenAgentClient(
                new RestTemplateBuilder().build(),
                new ObjectMapper(),
                properties("")
        );

        assertThatThrownBy(() -> client.complete(
                List.of(QwenAgentClient.ConversationMessage.user("你好")),
                new AgentToolRegistry().functionDefinitions()
        ))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("DASHSCOPE_API_KEY");
    }

    @Test
    void ordinaryChatOmitsFunctionCallingFields() {
        RestTemplate restTemplate = new RestTemplateBuilder().build();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        QwenProperties properties = properties("test-api-key");
        QwenAgentClient client = new QwenAgentClient(restTemplate, new ObjectMapper(), properties);

        server.expect(once(), requestTo(properties.endpoint()))
                .andExpect(jsonPath("$.tools").doesNotExist())
                .andExpect(jsonPath("$.tool_choice").doesNotExist())
                .andExpect(jsonPath("$.messages[0].content").value(org.hamcrest.Matchers.containsString("当前服务器时间")))
                .andRespond(withSuccess("""
                        {"choices":[{"message":{"content":"今天是星期三。"}}]}
                        """, MediaType.APPLICATION_JSON));

        QwenAgentClient.AgentTurn turn = client.complete(
                List.of(QwenAgentClient.ConversationMessage.user("今天过得怎么样")),
                List.of()
        );

        assertThat(turn.content()).isEqualTo("今天是星期三。");
        assertThat(turn.toolCalls()).isEmpty();
        server.verify();
    }

    @Test
    void parsesTextToolCallMarkupInsteadOfShowingJsonToUser() {
        RestTemplate restTemplate = new RestTemplateBuilder().build();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        QwenProperties properties = properties("test-api-key");
        QwenAgentClient client = new QwenAgentClient(restTemplate, new ObjectMapper(), properties);

        server.expect(once(), requestTo(properties.endpoint()))
                .andRespond(withSuccess("""
                        {
                          "choices": [{
                            "message": {
                              "content": "我先根据你的库存安排晚餐。\\n<tool_call>\\n{\\\"name\\\":\\\"recipe_generate\\\",\\\"arguments\\\":{\\\"ingredients\\\":[\\\"番茄\\\",\\\"茄子\\\"],\\\"meal_type\\\":\\\"dinner\\\",\\\"servings\\\":3}}\\n</tool_call>"
                            }
                          }]
                        }
                        """, MediaType.APPLICATION_JSON));

        QwenAgentClient.AgentTurn turn = client.complete(
                List.of(QwenAgentClient.ConversationMessage.user("根据库存推荐晚餐")),
                new AgentToolRegistry().functionDefinitions()
        );

        assertThat(turn.content()).isEqualTo("我先根据你的库存安排晚餐。");
        assertThat(turn.toolCalls()).containsExactly(
                new QwenAgentClient.ToolCall(
                        "text_tool_call_1",
                        "recipe_generate",
                        "{\"ingredients\":[\"番茄\",\"茄子\"],\"meal_type\":\"dinner\",\"servings\":3}"
                )
        );
        server.verify();
    }

    @Test
    void parsesBareJsonToolCallInsteadOfShowingItToUser() {
        RestTemplate restTemplate = new RestTemplateBuilder().build();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        QwenProperties properties = properties("test-api-key");
        QwenAgentClient client = new QwenAgentClient(restTemplate, new ObjectMapper(), properties);

        server.expect(once(), requestTo(properties.endpoint()))
                .andRespond(withSuccess("""
                        {
                          "choices": [{
                            "message": {
                              "content": "{\\"name\\":\\"recipe\\\\_generate\\",\\"arguments\\":{\\"ingredients\\":[\\"黄瓜\\",\\"火腿\\"]}}"
                            }
                          }]
                        }
                        """, MediaType.APPLICATION_JSON));

        QwenAgentClient.AgentTurn turn = client.complete(
                List.of(QwenAgentClient.ConversationMessage.user("生成一道黄瓜和火腿的菜")),
                new AgentToolRegistry().functionDefinitions()
        );

        assertThat(turn.content()).isEmpty();
        assertThat(turn.toolCalls()).containsExactly(
                new QwenAgentClient.ToolCall(
                        "text_tool_call_1",
                        "recipe_generate",
                        "{\"ingredients\":[\"黄瓜\",\"火腿\"]}"
                )
        );
        server.verify();
    }

    @Test
    void appendsChatCompletionsToConfiguredOpenAiBaseEndpoint() {
        RestTemplate restTemplate = new RestTemplateBuilder().build();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        QwenProperties properties = new QwenProperties(
                "test-api-key",
                "qwen-plus",
                "https://dashscope.test/compatible-mode/v1"
        );
        QwenAgentClient client = new QwenAgentClient(restTemplate, new ObjectMapper(), properties);

        server.expect(once(), requestTo("https://dashscope.test/compatible-mode/v1/chat/completions"))
                .andRespond(withSuccess(
                        "{\"choices\":[{\"message\":{\"content\":\"连接正常\"}}]}",
                        MediaType.APPLICATION_JSON
                ));

        QwenAgentClient.AgentTurn turn = client.complete(
                List.of(QwenAgentClient.ConversationMessage.user("测试连接")),
                List.of()
        );

        assertThat(turn.content()).isEqualTo("连接正常");
        server.verify();
    }

    @Test
    void sendsAnthropicCompatibleAgentRequestAndParsesToolUse() {
        RestTemplate restTemplate = new RestTemplateBuilder().build();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        AiModelConfigService configService = org.mockito.Mockito.mock(AiModelConfigService.class);
        when(configService.textRecipeRuntimeConfig()).thenReturn(new AiModelRuntimeConfig(
                "qwen",
                "anthropic",
                "qwen-plus",
                "https://dashscope.test/apps/anthropic",
                "anthropic-api-key"
        ));
        QwenAgentClient client = new QwenAgentClient(
                restTemplate,
                new ObjectMapper(),
                properties("environment-key"),
                configService
        );
        List<Map<String, Object>> tools = List.of(Map.of(
                "type", "function",
                "function", Map.of(
                        "name", "pantry_list",
                        "description", "查询库存",
                        "parameters", Map.of(
                                "type", "object",
                                "properties", Map.of(),
                                "additionalProperties", false
                        )
                )
        ));

        server.expect(once(), requestTo("https://dashscope.test/apps/anthropic"))
                .andExpect(header("x-api-key", "anthropic-api-key"))
                .andExpect(header("anthropic-version", "2023-06-01"))
                .andExpect(jsonPath("$.system").value(org.hamcrest.Matchers.containsString("当前服务器时间")))
                .andExpect(jsonPath("$.messages[0].role").value("user"))
                .andExpect(jsonPath("$.messages[0].content").value("我的库存有什么？"))
                .andExpect(jsonPath("$.tools[0].name").value("pantry_list"))
                .andExpect(jsonPath("$.tools[0].input_schema.type").value("object"))
                .andRespond(withSuccess("""
                        {
                          "content": [
                            {"type": "text", "text": ""},
                            {"type": "tool_use", "id": "call_inventory_1", "name": "pantry_list", "input": {}}
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        QwenAgentClient.AgentTurn turn = client.complete(
                List.of(QwenAgentClient.ConversationMessage.user("我的库存有什么？")),
                tools
        );

        assertThat(turn.content()).isEmpty();
        assertThat(turn.toolCalls()).containsExactly(
                new QwenAgentClient.ToolCall("call_inventory_1", "pantry_list", "{}")
        );
        server.verify();
    }

    private QwenProperties properties(String apiKey) {
        return new QwenProperties(
                apiKey,
                "qwen-plus",
                "https://dashscope.test/compatible-mode/v1/chat/completions"
        );
    }
}
