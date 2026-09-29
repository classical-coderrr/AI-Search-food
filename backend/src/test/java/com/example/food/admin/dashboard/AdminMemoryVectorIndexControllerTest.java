package com.example.food.admin.dashboard;

import com.example.food.admin.dashboard.dto.AdminMemoryVectorIndexRebuildResponse;
import com.example.food.admin.dashboard.dto.AdminMemoryVectorIndexStatusResponse;
import com.example.food.security.AppRole;
import com.example.food.security.AuthPrincipal;
import com.example.food.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminMemoryVectorIndexControllerTest {
    private static final String URL = "/api/admin/dashboard/memory-vector-index";

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtService jwtService;
    @MockBean private AdminMemoryVectorIndexService service;

    @Test
    void adminCanInspectAggregatedIndexStatusAndRequestRebuild() throws Exception {
        String token = jwtService.generateToken(new AuthPrincipal(1L, "admin", AppRole.ADMIN));
        when(service.status()).thenReturn(new AdminMemoryVectorIndexStatusResponse(
                Instant.parse("2026-09-29T04:00:00Z"), true, "text-embedding-v3", "GREEN", true,
                135, 120, 15, 8, 2, 1, 500, 135, 0));
        when(service.requestRebuild()).thenReturn(new AdminMemoryVectorIndexRebuildResponse(
                Instant.parse("2026-09-29T04:00:00Z"), "text-embedding-v3", 120, 100, "REBUILD_REQUESTED"));

        mockMvc.perform(get(URL).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.databaseVectorCount").value(135))
                .andExpect(jsonPath("$.data.unindexedVectorCount").value(15))
                .andExpect(jsonPath("$.data.qdrantStatus").value("GREEN"))
                .andExpect(jsonPath("$.data.retryingJobCount").value(1));

        mockMvc.perform(post(URL + "/rebuild").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.vectorRowsReset").value(120))
                .andExpect(jsonPath("$.data.upsertJobsQueued").value(100))
                .andExpect(jsonPath("$.data.status").value("REBUILD_REQUESTED"));
    }

    @Test
    void regularUserCannotInspectOrRebuildGlobalVectorIndex() throws Exception {
        String token = jwtService.generateToken(new AuthPrincipal(7L, "13800138000", AppRole.USER));

        mockMvc.perform(get(URL).header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(URL + "/rebuild").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }
}
