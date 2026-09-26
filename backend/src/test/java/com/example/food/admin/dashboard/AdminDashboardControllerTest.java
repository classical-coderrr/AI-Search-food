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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminDashboardControllerTest {

    private static final String OVERVIEW_URL = "/api/admin/dashboard/overview";
    private static final String AGENT_OBSERVABILITY_URL = "/api/admin/dashboard/agent-observability";
    private static final String AGENT_EVALUATION_URL = "/api/admin/dashboard/agent-evaluation";
    private static final String MEMORY_EVALUATION_URL = "/api/admin/dashboard/memory-evaluation";
    private static final String MEMORY_OBSERVABILITY_URL = "/api/admin/dashboard/memory-observability";

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
                        .param("range", "7d")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.generatedAt").exists())
                .andExpect(jsonPath("$.data.metrics.runsStarted").isNumber())
                .andExpect(jsonPath("$.data.metrics.runsRecovered").isNumber())
                .andExpect(jsonPath("$.data.metrics.averageRunDurationMs").isNumber())
                .andExpect(jsonPath("$.data.recovery.enabled").isBoolean())
                .andExpect(jsonPath("$.data.persistence.enabled").isBoolean())
                .andExpect(jsonPath("$.data.history").isArray())
                .andExpect(jsonPath("$.data.alerts").isArray())
                .andExpect(content().string(not(containsString("apiKey"))))
                .andExpect(content().string(not(containsString("password"))));
    }

    @Test
    void adminCanRunAndReadAgentEvaluationSet() throws Exception {
        String adminToken = jwtService.generateToken(new AuthPrincipal(1L, "admin", AppRole.ADMIN));

        mockMvc.perform(post(AGENT_EVALUATION_URL + "/run")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("PASSED"))
                .andExpect(jsonPath("$.data.totalCases").value(6))
                .andExpect(jsonPath("$.data.passedCases").value(6))
                .andExpect(jsonPath("$.data.failedCases").value(0))
                .andExpect(jsonPath("$.data.cases.length()").value(6))
                .andExpect(content().string(not(containsString("apiKey"))))
                .andExpect(content().string(not(containsString("password"))));

        mockMvc.perform(get(AGENT_EVALUATION_URL)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PASSED"))
                .andExpect(jsonPath("$.data.cases[0].inputMessage").exists());
    }

    @Test
    void adminCanRunMemoryEvaluationAndReadPrivacySafeObservability() throws Exception {
        String adminToken = jwtService.generateToken(new AuthPrincipal(1L, "admin", AppRole.ADMIN));

        mockMvc.perform(post(MEMORY_EVALUATION_URL + "/run")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.suiteVersion").value("memory-evaluation-v2"))
                .andExpect(jsonPath("$.data.status").value("PASSED"))
                .andExpect(jsonPath("$.data.totalCases").value(19))
                .andExpect(jsonPath("$.data.passedCases").value(19))
                .andExpect(jsonPath("$.data.metrics.extractionPrecision").isNumber())
                .andExpect(jsonPath("$.data.metrics.extractionRecall").isNumber())
                .andExpect(jsonPath("$.data.metrics.rerankRecallAt3").isNumber())
                .andExpect(jsonPath("$.data.metrics.canonicalDedupAccuracy").isNumber())
                .andExpect(jsonPath("$.data.metrics.conflictScenarioSelectionAccuracy").isNumber())
                .andExpect(jsonPath("$.data.metrics.staleMemoryTop3SelectionRate").isNumber())
                .andExpect(jsonPath("$.data.metrics.personalizationScenarioWinRate").isNumber())
                .andExpect(jsonPath("$.data.metrics.extractionPrecision").value(1.0))
                .andExpect(jsonPath("$.data.metrics.extractionRecall").value(1.0))
                .andExpect(jsonPath("$.data.metrics.rerankRecallAt3").value(1.0))
                .andExpect(jsonPath("$.data.metrics.canonicalDedupAccuracy").value(1.0))
                .andExpect(jsonPath("$.data.metrics.conflictScenarioSelectionAccuracy").value(1.0))
                .andExpect(jsonPath("$.data.metrics.staleMemoryTop3SelectionRate").value(0.0))
                .andExpect(jsonPath("$.data.metrics.personalizationScenarioWinRate").value(1.0))
                .andExpect(jsonPath("$.data.unmeasuredMetrics.length()").value(4))
                .andExpect(content().string(not(containsString("apiKey"))))
                .andExpect(content().string(not(containsString("password"))));

        mockMvc.perform(get(MEMORY_EVALUATION_URL)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").exists())
                .andExpect(jsonPath("$.data.cases.length()").value(19))
                .andExpect(jsonPath("$.data.cases[?(@.caseKey=='RECENT_CONTEXT_VS_LONG_TERM_TASTE')].passed").value(org.hamcrest.Matchers.contains(true)))
                .andExpect(jsonPath("$.data.cases[?(@.caseKey=='STALE_SHORT_TERM_TREND_120D')].passed").value(org.hamcrest.Matchers.contains(true)))
                .andExpect(jsonPath("$.data.cases[?(@.caseKey=='LONG_TERM_LIKE_IMPROVES_CHOICE')].passed").value(org.hamcrest.Matchers.contains(true)));

        mockMvc.perform(get(MEMORY_OBSERVABILITY_URL)
                        .param("range", "7d")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.range").value("7d"))
                .andExpect(jsonPath("$.data.metrics.retrievalCount").isNumber())
                .andExpect(jsonPath("$.data.metrics.feedbackCount").isNumber())
                .andExpect(jsonPath("$.data.metrics.inaccurateFeedbackRate").isNumber())
                .andExpect(jsonPath("$.data.metrics.averageLatencyMs").isNumber())
                .andExpect(content().string(not(containsString("userId"))))
                .andExpect(content().string(not(containsString("queryHash"))));
    }

    @Test
    void memoryObservabilityAndEvaluationAreAdminOnly() throws Exception {
        String userToken = jwtService.generateToken(new AuthPrincipal(7L, "13800138000", AppRole.USER));

        mockMvc.perform(get(MEMORY_OBSERVABILITY_URL)
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(MEMORY_EVALUATION_URL + "/run")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }
}
