package com.example.food.agent;

import com.example.food.agent.dto.AgentChatRequest;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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

    private UsernamePasswordAuthenticationToken authenticationFor(AuthPrincipal principal) {
        return new UsernamePasswordAuthenticationToken(
                principal,
                null,
                List.of(new SimpleGrantedAuthority("ROLE_" + principal.role().name()))
        );
    }
}
