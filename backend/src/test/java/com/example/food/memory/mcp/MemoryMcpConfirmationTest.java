package com.example.food.memory.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.ClientCapabilities.Elicitation;
import io.modelcontextprotocol.spec.McpSchema.ElicitFormRequest;
import io.modelcontextprotocol.spec.McpSchema.ElicitResult;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MemoryMcpConfirmationTest {
    private final MemoryMcpToolCatalog catalog = new MemoryMcpToolCatalog(null, null, null, new ObjectMapper());

    @Test
    void acceptsOnlyWhenClientSubmitsConfirmedTrue() {
        McpSyncServerExchange exchange = exchange(formElicitation(),
                new ElicitResult(ElicitResult.Action.ACCEPT, Map.of("confirmed", true)));

        assertThat(requestConfirmation(exchange)).isTrue();
    }

    @Test
    void treatsAcceptWithoutExplicitTrueAsNotConfirmed() {
        for (Map<String, Object> content : List.<Map<String, Object>>of(
                Map.of("confirmed", false), Map.of())) {
            McpSyncServerExchange exchange = exchange(formElicitation(),
                    new ElicitResult(ElicitResult.Action.ACCEPT, content));

            assertThat(requestConfirmation(exchange)).isFalse();
        }
    }

    @Test
    void rejectsUrlOnlyElicitationInsteadOfAttemptingUnsupportedForm() {
        McpSyncServerExchange exchange = mock(McpSyncServerExchange.class);
        when(exchange.getClientCapabilities()).thenReturn(McpSchema.ClientCapabilities.builder()
                .elicitation(Elicitation.builder().url(new Elicitation.Url()).build())
                .build());

        assertThatThrownBy(() -> requestConfirmation(exchange))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("不支持用户确认弹窗");
        verify(exchange, never()).createElicitation(any(ElicitFormRequest.class));
    }

    @Test
    void treatsDeclineAsNotConfirmed() {
        McpSyncServerExchange exchange = exchange(formElicitation(),
                new ElicitResult(ElicitResult.Action.DECLINE, Map.of("confirmed", true)));

        assertThat(requestConfirmation(exchange)).isFalse();
    }

    private boolean requestConfirmation(McpSyncServerExchange exchange) {
        return Boolean.TRUE.equals(ReflectionTestUtils.invokeMethod(catalog,
                "requestConfirmation", exchange, "确认测试操作？"));
    }

    private McpSyncServerExchange exchange(Elicitation elicitation, ElicitResult result) {
        McpSyncServerExchange exchange = mock(McpSyncServerExchange.class);
        when(exchange.getClientCapabilities()).thenReturn(McpSchema.ClientCapabilities.builder()
                .elicitation(elicitation)
                .build());
        when(exchange.createElicitation(any(ElicitFormRequest.class))).thenReturn(result);
        return exchange;
    }

    private Elicitation formElicitation() {
        return Elicitation.builder().form(new Elicitation.Form()).build();
    }
}
