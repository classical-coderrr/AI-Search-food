package com.example.food.agent;

import com.example.food.agent.dto.AgentChatRequest;
import com.example.food.agent.dto.AgentConfirmationStatusResponse;
import com.example.food.agent.dto.AgentRunStatusResponse;
import com.example.food.agent.dto.AgentConversationHistoryResponse;
import com.example.food.agent.dto.AgentMessageResponse;
import com.example.food.agent.dto.AgentWriteOperationStatusResponse;
import com.example.food.agent.state.AgentNode;
import com.example.food.agent.state.AgentStatus;
import com.example.food.security.AppRole;
import com.example.food.security.AuthPrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.time.LocalDateTime;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AgentController.class)
@AutoConfigureMockMvc(addFilters = false)
class AgentControllerTest {

    private static final AuthPrincipal PRINCIPAL = new AuthPrincipal(7L, "13800138000", AppRole.USER);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AgentService agentService;

    @BeforeEach
    void setAuthenticatedPrincipal() {
        SecurityContextHolder.getContext().setAuthentication(authenticationFor(PRINCIPAL));
    }

    @AfterEach
    void clearAuthenticatedPrincipal() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void jsonStreamBindsRequestAndAuthenticatedPrincipal() throws Exception {
        SseEmitter emitter = new SseEmitter();
        when(agentService.stream(any(AgentChatRequest.class), eq(PRINCIPAL))).thenReturn(emitter);

        mockMvc.perform(post("/api/agent/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AgentChatRequest(
                                null, "我有哪些食材？", null, null
                        ))))
                .andExpect(status().isOk());

        verify(agentService).stream(new AgentChatRequest(null, "我有哪些食材？", null, null), PRINCIPAL);
    }

    @Test
    void streamRejectsMessageLongerThanValidationLimit() throws Exception {
        String message = "a".repeat(1001);

        mockMvc.perform(post("/api/agent/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AgentChatRequest(
                                null, message, null, null
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        verify(agentService, never()).stream(any(AgentChatRequest.class), any(AuthPrincipal.class));
    }

    @Test
    void deletesConversationForAuthenticatedOwner() throws Exception {
        mockMvc.perform(delete("/api/agent/conversations/42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        verify(agentService).deleteConversation(7L, 42L);
    }

    @Test
    void readsRunStatusForAuthenticatedOwner() throws Exception {
        when(agentService.runStatus(7L, "run-1")).thenReturn(new AgentRunStatusResponse(
                "run-1", 42L, AgentStatus.RECOVERING, AgentNode.TOOL_EXECUTE,
                AgentNode.OBSERVE, null, null, null
        ));

        mockMvc.perform(get("/api/agent/runs/run-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.runId").value("run-1"))
                .andExpect(jsonPath("$.data.status").value("RECOVERING"));

        verify(agentService).runStatus(7L, "run-1");
    }

    @Test
    void readsConfirmationStatusForAuthenticatedOwner() throws Exception {
        when(agentService.confirmationStatus(eq(PRINCIPAL), eq(9L))).thenReturn(new AgentConfirmationStatusResponse(
                9L, 42L, "PANTRY_DELETE", "UNKNOWN_REVIEW", LocalDateTime.now().minusMinutes(20),
                LocalDateTime.now().minusMinutes(19), null, null,
                "PROCESSING_TIMEOUT", "操作长时间处于处理中，无法确认是否已完成，请人工复核"
        ));

        mockMvc.perform(get("/api/agent/confirmations/9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.confirmationId").value(9))
                .andExpect(jsonPath("$.data.status").value("UNKNOWN_REVIEW"));

        verify(agentService).confirmationStatus(PRINCIPAL, 9L);
    }

    @Test
    void readsWriteOperationStatusByIdempotencyKey() throws Exception {
        when(agentService.operationStatus(PRINCIPAL, "key-1")).thenReturn(new AgentWriteOperationStatusResponse(
                11L, 9L, "PANTRY_UPDATE", "key-1", "COMPLETED",
                LocalDateTime.now().minusMinutes(1), LocalDateTime.now().minusMinutes(1), LocalDateTime.now(),
                "库存已更新", JsonNodeFactory.instance.objectNode().put("itemId", 12), null, null
        ));

        mockMvc.perform(get("/api/agent/writes/key-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.result.itemId").value(12));

        verify(agentService).operationStatus(PRINCIPAL, "key-1");
    }

    @Test
    void readsLatestConversationHistoryForAuthenticatedOwner() throws Exception {
        when(agentService.latestConversationHistory(7L)).thenReturn(new AgentConversationHistoryResponse(
                42L,
                "厨房助手对话",
                LocalDateTime.now().minusMinutes(1),
                LocalDateTime.now(),
                List.of(new AgentMessageResponse(9L, "USER", "text", "今晚能做什么", LocalDateTime.now())),
                null
        ));

        mockMvc.perform(get("/api/agent/conversations/latest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.conversationId").value(42))
                .andExpect(jsonPath("$.data.messages[0].content").value("今晚能做什么"));

        verify(agentService).latestConversationHistory(7L);
    }

    private UsernamePasswordAuthenticationToken authenticationFor(AuthPrincipal principal) {
        return new UsernamePasswordAuthenticationToken(
                principal,
                null,
                List.of(new SimpleGrantedAuthority("ROLE_" + principal.role().name()))
        );
    }
}
