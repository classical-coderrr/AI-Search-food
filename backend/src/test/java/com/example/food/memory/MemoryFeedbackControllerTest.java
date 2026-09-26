package com.example.food.memory;

import com.example.food.common.GlobalExceptionHandler;
import com.example.food.security.AppRole;
import com.example.food.security.AuthPrincipal;
import com.example.food.security.JwtService;
import com.example.food.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MemoryFeedbackController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class MemoryFeedbackControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JwtService jwtService;

    @MockBean
    private MemoryFeedbackService feedbackService;

    @Test
    void authenticatedUserCanSubmitFeedbackForOwnAgentRun() throws Exception {
        authenticate("user-token", new AuthPrincipal(7L, "13800138000", AppRole.USER));
        LocalDateTime now = LocalDateTime.of(2026, 9, 25, 10, 0);
        when(feedbackService.submit(7L, new MemoryFeedbackRequest("run-1", MemoryFeedbackType.HELPFUL)))
                .thenReturn(new MemoryFeedbackResponse(81L, "run-1", MemoryFeedbackType.HELPFUL,
                        false, now, now));

        mockMvc.perform(post("/api/memory/feedback")
                        .header("Authorization", "Bearer user-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"traceId":"run-1","feedbackType":"HELPFUL"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.traceId").value("run-1"))
                .andExpect(jsonPath("$.data.feedbackType").value("HELPFUL"))
                .andExpect(jsonPath("$.data.updated").value(false));

        verify(feedbackService).submit(7L, new MemoryFeedbackRequest("run-1", MemoryFeedbackType.HELPFUL));
    }

    @Test
    void authenticatedUserCanReadFeedbackEligibilityForOwnAgentRun() throws Exception {
        authenticate("user-token", new AuthPrincipal(7L, "13800138000", AppRole.USER));
        when(feedbackService.status(7L, "run-1"))
                .thenReturn(new MemoryFeedbackStatusResponse(true, MemoryFeedbackType.HELPFUL,
                        LocalDateTime.of(2026, 9, 25, 10, 0)));

        mockMvc.perform(get("/api/memory/feedback/run-1")
                        .header("Authorization", "Bearer user-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.eligible").value(true))
                .andExpect(jsonPath("$.data.feedbackType").value("HELPFUL"));

        verify(feedbackService).status(7L, "run-1");
    }

    @Test
    void requiresAuthenticatedUser() throws Exception {
        mockMvc.perform(post("/api/memory/feedback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"traceId":"run-1","feedbackType":"HELPFUL"}
                                """))
                .andExpect(status().isUnauthorized());

        verify(feedbackService, never()).submit(any(), any());
    }

    @Test
    void rejectsAdminRoleFromUserMemoryFeedbackEndpoint() throws Exception {
        authenticate("admin-token", new AuthPrincipal(3L, "admin", AppRole.ADMIN));

        mockMvc.perform(post("/api/memory/feedback")
                        .header("Authorization", "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"traceId":"run-1","feedbackType":"HELPFUL"}
                                """))
                .andExpect(status().isForbidden());

        verify(feedbackService, never()).submit(any(), any());
    }

    @Test
    void rejectsAdminRoleFromFeedbackStatusEndpoint() throws Exception {
        authenticate("admin-token", new AuthPrincipal(3L, "admin", AppRole.ADMIN));

        mockMvc.perform(get("/api/memory/feedback/run-1")
                        .header("Authorization", "Bearer admin-token"))
                .andExpect(status().isForbidden());

        verify(feedbackService, never()).status(any(), any());
    }

    @Test
    void rejectsMissingOrUnknownFeedbackType() throws Exception {
        authenticate("user-token", new AuthPrincipal(7L, "13800138000", AppRole.USER));

        mockMvc.perform(post("/api/memory/feedback")
                        .header("Authorization", "Bearer user-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"traceId":"run-1"}
                                """))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/memory/feedback")
                        .header("Authorization", "Bearer user-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"traceId":"run-1","feedbackType":"NOT_A_VALUE"}
                                """))
                .andExpect(status().isBadRequest());

        verify(feedbackService, never()).submit(any(), any());
    }

    private void authenticate(String token, AuthPrincipal principal) {
        when(jwtService.parseToken(token)).thenReturn(principal);
    }
}
