package com.example.food.memory;

import com.example.food.ai.config.AiModelConfigService;
import com.example.food.ai.config.AiModelRuntimeConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpMethod.POST;

class OpenAiCompatibleMemoryEmbeddingClientTest {

    @Test
    void sendsBatchToOpenAiCompatibleEndpointAndOrdersReturnedVectors() {
        AiModelConfigService config = mock(AiModelConfigService.class);
        when(config.textRecipeRuntimeConfig()).thenReturn(new AiModelRuntimeConfig("qwen", "openai", "qwen-plus",
                "https://example.test/compatible-mode/v1/chat/completions", "test-secret"));
        MemoryEmbeddingProperties properties = new MemoryEmbeddingProperties();
        properties.setDimensions(2);
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(requestTo("https://example.test/compatible-mode/v1/embeddings"))
                .andExpect(method(POST))
                .andExpect(header("Authorization", "Bearer test-secret"))
                .andExpect(content().json("""
                        {"model":"text-embedding-v3","input":["鸡胸肉","豆腐"],"dimensions":2,"encoding_format":"float"}
                        """))
                .andRespond(withSuccess("""
                        {"data":[{"index":1,"embedding":[0.0,1.0]},{"index":0,"embedding":[1.0,0.0]}]}
                        """, MediaType.APPLICATION_JSON));
        OpenAiCompatibleMemoryEmbeddingClient client = new OpenAiCompatibleMemoryEmbeddingClient(
                restTemplate, new ObjectMapper(), config, properties);

        List<float[]> vectors = client.embed(List.of("鸡胸肉", "豆腐"));

        assertThat(client.isAvailable()).isTrue();
        assertThat(vectors).hasSize(2);
        assertThat(vectors.get(0)).containsExactly(1.0f, 0.0f);
        assertThat(vectors.get(1)).containsExactly(0.0f, 1.0f);
        server.verify();
    }

    @Test
    void declinesUnsupportedProviderAndMalformedDimensionsWithoutBreakingFallback() {
        AiModelConfigService config = mock(AiModelConfigService.class);
        when(config.textRecipeRuntimeConfig()).thenReturn(new AiModelRuntimeConfig("deepseek", "openai", "chat",
                "https://api.deepseek.com/v1", "test-secret"));
        MemoryEmbeddingProperties properties = new MemoryEmbeddingProperties();
        RestTemplate restTemplate = new RestTemplate();
        OpenAiCompatibleMemoryEmbeddingClient client = new OpenAiCompatibleMemoryEmbeddingClient(
                restTemplate, new ObjectMapper(), config, properties);

        assertThat(client.isAvailable()).isFalse();
        assertThat(client.embed(List.of("test"))).isEmpty();
    }
}
