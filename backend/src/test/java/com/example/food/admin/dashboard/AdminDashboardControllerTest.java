package com.example.food.admin.dashboard;

import com.example.food.security.AppRole;
import com.example.food.security.AuthPrincipal;
import com.example.food.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminDashboardControllerTest {

    private static final String OVERVIEW_URL = "/api/admin/dashboard/overview";
    private static final String AGENT_OBSERVABILITY_URL = "/api/admin/dashboard/agent-observability";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Test
    void adminCanReadAggregatedDashboardWithoutSensitiveFields() throws Exception {
        String adminToken = jwtService.generateToken(new AuthPrincipal(1L, "admin", AppRole.ADMIN));

        mockMvc.perform(get(OVERVIEW_URL)
                        .param("period", "7d")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.period").value("7d"))
                .andExpect(jsonPath("$.data.generatedAt").exists())
                .andExpect(jsonPath("$.data.metrics.newUserCount").isNumber())
                .andExpect(jsonPath("$.data.metrics.generationCount").isNumber())
                .andExpect(jsonPath("$.data.metrics.savedRecipeCount").isNumber())
                .andExpect(jsonPath("$.data.metrics.reviewCount").isNumber())
                .andExpect(jsonPath("$.data.dailyTrend.length()").value(7))
                .andExpect(content().string(not(containsString("phone"))))
                .andExpect(content().string(not(containsString("apiKey"))))
                .andExpect(content().string(not(containsString("rawResponse"))))
                .andExpect(content().string(not(containsString("storagePath"))))
                .andExpect(content().string(not(containsString("queryText"))));
    }

    @Test
    void userCannotReadDashboardAndAdminGetsBadRequestForInvalidPeriod() throws Exception {
        String userToken = jwtService.generateToken(new AuthPrincipal(7L, "13800138000", AppRole.USER));
        String adminToken = jwtService.generateToken(new AuthPrincipal(1L, "admin", AppRole.ADMIN));

        mockMvc.perform(get(OVERVIEW_URL)
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));

        mockMvc.perform(get(OVERVIEW_URL)
                        .param("period", "all")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void adminCanReadAgentObservabilityWithoutSensitiveFields() throws Exception {
        String adminToken = jwtService.generateToken(new AuthPrincipal(1L, "admin", AppRole.ADMIN));

        mockMvc.perform(get(AGENT_OBSERVABILITY_URL)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.generatedAt").exists())
                .andExpect(jsonPath("$.data.metrics.runsStarted").isNumber())
                .andExpect(jsonPath("$.data.metrics.runsRecovered").isNumber())
                .andExpect(jsonPath("$.data.metrics.averageRunDurationMs").isNumber())
                .andExpect(jsonPath("$.data.recovery.enabled").isBoolean())
                .andExpect(content().string(not(containsString("apiKey"))))
                .andExpect(content().string(not(containsString("password"))));
    }
}
