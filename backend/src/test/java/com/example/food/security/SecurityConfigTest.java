package com.example.food.security;

import com.example.food.agent.AgentController;
import com.example.food.agent.AgentService;
import com.example.food.agent.dto.AgentChatRequest;
import com.example.food.common.ApiResponse;
import com.example.food.memory.MemoryManagementController;
import com.example.food.memory.MemoryManagementResponse;
import com.example.food.memory.MemoryManagementService;
import com.example.food.memory.MemoryConfirmationController;
import com.example.food.memory.MemoryConfirmationService;
import com.example.food.memory.MemoryPersonalizationService;
import com.example.food.memory.MemoryPersonalizationState;
import com.example.food.stats.HotIngredientStatsController;
import com.example.food.stats.HotIngredientStatsService;
import com.example.food.stats.dto.HotIngredientStatsResponse;
import com.example.food.stats.IngredientNormalizer;
import com.example.food.stats.image.IngredientImage;
import com.example.food.stats.image.IngredientImageController;
import com.example.food.stats.image.IngredientImageService;
import com.example.food.user.preference.UserDietPreferenceController;
import com.example.food.user.preference.UserDietPreferenceService;
import com.example.food.user.preference.dto.DietPreferenceResponse;
import com.example.food.video.VideoSearchController;
import com.example.food.video.VideoSearchService;
import com.example.food.video.dto.VideoSearchResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {
        SecurityConfigTest.TestController.class,
        HotIngredientStatsController.class,
        UserDietPreferenceController.class,
        IngredientImageController.class,
        VideoSearchController.class,
        AgentController.class,
        MemoryConfirmationController.class,
        MemoryManagementController.class
})
@Import(SecurityConfig.class)
class SecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private JwtService jwtService;

    @MockBean
    private HotIngredientStatsService hotIngredientStatsService;

    @MockBean
    private UserDietPreferenceService userDietPreferenceService;

    @MockBean
    private IngredientImageService ingredientImageService;

    @MockBean
    private IngredientNormalizer ingredientNormalizer;

    @MockBean
    private VideoSearchService videoSearchService;

    @MockBean
    private AgentService agentService;

    @MockBean
    private MemoryConfirmationService memoryConfirmationService;

    @MockBean
    private MemoryManagementService memoryManagementService;

    @MockBean
    private MemoryPersonalizationService memoryPersonalizationService;

    @Test
    void unauthenticatedProtectedEndpointReturnsJsonUnauthorized() throws Exception {
        mockMvc.perform(get("/api/user/profile"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("Unauthorized"));
    }

    @Test
    void unauthenticatedMemoryEndpointReturnsJsonUnauthorized() throws Exception {
        mockMvc.perform(get("/api/memory/confirmations/pending"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void unauthenticatedMemoryManagementEndpointReturnsJsonUnauthorized() throws Exception {
        mockMvc.perform(get("/api/memory/management"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void wrongRoleReturnsJsonForbidden() throws Exception {
        when(jwtService.parseToken("user-token"))
                .thenReturn(new AuthPrincipal(7L, "13800138000", AppRole.USER));

        mockMvc.perform(get("/api/admin/dashboard")
                        .header("Authorization", "Bearer user-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.message").value("Forbidden"));
    }

    @Test
    void invalidBearerTokenReturnsJsonUnauthorized() throws Exception {
        when(jwtService.parseToken("bad-token"))
                .thenThrow(new BadCredentialsException("Invalid JWT token"));

        mockMvc.perform(get("/api/user/profile")
                        .header("Authorization", "Bearer bad-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("Unauthorized"));
    }

    @Test
    void hotIngredientRankingIsPublic() throws Exception {
        when(hotIngredientStatsService.get("all", 10))
                .thenReturn(new HotIngredientStatsResponse("all", 0, 0, null, java.util.List.of()));

        mockMvc.perform(get("/api/stats/hot-ingredients"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.period").value("all"));
    }

    @Test
    void verifiedIngredientImageEndpointIsPublic() throws Exception {
        IngredientImage image = new IngredientImage();
        image.setContentType("image/jpeg");
        image.setImageData(new byte[]{1, 2, 3});
        when(ingredientNormalizer.normalizeDistinct("番茄"))
                .thenReturn(java.util.List.of(new IngredientNormalizer.NormalizedIngredient("番茄", "番茄")));
        when(ingredientImageService.findReady("番茄")).thenReturn(image);

        mockMvc.perform(get("/api/ingredients/images/番茄"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/jpeg"))
                .andExpect(content().bytes(new byte[]{1, 2, 3}));
    }

    @Test
    void unauthenticatedUserDietPreferenceRequestIsRejected() throws Exception {
        mockMvc.perform(get("/api/users/me/diet-preferences"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void unauthenticatedVideoSearchRequestIsRejected() throws Exception {
        mockMvc.perform(get("/api/videos/search").param("recipeTitle", "Tomato Egg"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void authenticatedUserCanSearchVideos() throws Exception {
        when(jwtService.parseToken("user-token"))
                .thenReturn(new AuthPrincipal(7L, "13800138000", AppRole.USER));
        when(videoSearchService.search(
                7L,
                new AuthPrincipal(7L, "13800138000", AppRole.USER),
                "Tomato Egg",
                null,
                1,
                null
        )).thenReturn(new VideoSearchResponse(java.util.List.of(), 1, false, false, true,
                "https://search.bilibili.com/all?keyword=Tomato%20Egg", "degraded"));

        mockMvc.perform(get("/api/videos/search")
                        .param("recipeTitle", "Tomato Egg")
                        .header("Authorization", "Bearer user-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.degraded").value(true))
                .andExpect(jsonPath("$.data.page").value(1));
    }

    @Test
    void adminCannotAccessUserDietPreferences() throws Exception {
        when(jwtService.parseToken("admin-token"))
                .thenReturn(new AuthPrincipal(1L, "admin", AppRole.ADMIN));

        mockMvc.perform(get("/api/users/me/diet-preferences")
                        .header("Authorization", "Bearer admin-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void userCanAccessUserDietPreferences() throws Exception {
        when(jwtService.parseToken("user-token"))
                .thenReturn(new AuthPrincipal(7L, "13800138000", AppRole.USER));
        when(userDietPreferenceService.get(7L)).thenReturn(DietPreferenceResponse.empty());

        mockMvc.perform(get("/api/users/me/diet-preferences")
                        .header("Authorization", "Bearer user-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.taste").value("any"));
    }

    @Test
    void unauthenticatedAgentStreamRequestIsRejected() throws Exception {
        mockMvc.perform(post("/api/agent/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"我的食材\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void unauthenticatedAgentMultipartStreamRequestIsRejected() throws Exception {
        MockMultipartFile request = new MockMultipartFile(
                "request",
                "request.json",
                MediaType.APPLICATION_JSON_VALUE,
                "{\"message\":\"识别这张图片\"}".getBytes()
        );
        MockMultipartFile image = new MockMultipartFile(
                "image",
                "food.png",
                MediaType.IMAGE_PNG_VALUE,
                new byte[]{1, 2, 3}
        );

        mockMvc.perform(multipart("/api/agent/chat/stream")
                        .file(request)
                        .file(image))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void authenticatedUserCanStartAgentMultipartStream() throws Exception {
        when(jwtService.parseToken("user-token"))
                .thenReturn(new AuthPrincipal(7L, "13800138000", AppRole.USER));
        when(agentService.stream(
                any(AgentChatRequest.class),
                any(AuthPrincipal.class),
                any(MultipartFile.class)
        )).thenReturn(new SseEmitter());
        MockMultipartFile requestPart = new MockMultipartFile(
                "request",
                "request.json",
                MediaType.APPLICATION_JSON_VALUE,
                objectMapper.writeValueAsBytes(new AgentChatRequest(
                        null, "识别这张图片", null, null
                ))
        );
        MockMultipartFile image = new MockMultipartFile(
                "image",
                "food.png",
                MediaType.IMAGE_PNG_VALUE,
                new byte[]{1, 2, 3}
        );

        mockMvc.perform(multipart("/api/agent/chat/stream")
                        .file(requestPart)
                        .file(image)
                        .header("Authorization", "Bearer user-token"))
                .andExpect(request().asyncStarted());
    }

    @Test
    void adminCannotUseAgentStream() throws Exception {
        when(jwtService.parseToken("admin-token"))
                .thenReturn(new AuthPrincipal(1L, "admin", AppRole.ADMIN));

        mockMvc.perform(post("/api/agent/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"我的食材\"}")
                        .header("Authorization", "Bearer admin-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void adminCannotAccessUserMemoryEndpoints() throws Exception {
        when(jwtService.parseToken("admin-token"))
                .thenReturn(new AuthPrincipal(1L, "admin", AppRole.ADMIN));

        mockMvc.perform(get("/api/memory/confirmations/pending")
                        .header("Authorization", "Bearer admin-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void adminCannotAccessMemoryManagementEndpoints() throws Exception {
        when(jwtService.parseToken("admin-token"))
                .thenReturn(new AuthPrincipal(1L, "admin", AppRole.ADMIN));

        mockMvc.perform(get("/api/memory/management")
                        .header("Authorization", "Bearer admin-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void authenticatedUserCanAccessUserMemoryEndpoints() throws Exception {
        when(jwtService.parseToken("user-token"))
                .thenReturn(new AuthPrincipal(7L, "13800138000", AppRole.USER));
        when(memoryConfirmationService.listPending(7L, 20)).thenReturn(java.util.List.of());

        mockMvc.perform(get("/api/memory/confirmations/pending")
                        .header("Authorization", "Bearer user-token"))
                .andExpect(status().isOk());
    }

    @Test
    void authenticatedUserCanAccessTheirMemoryManagementOverview() throws Exception {
        when(jwtService.parseToken("user-token"))
                .thenReturn(new AuthPrincipal(7L, "13800138000", AppRole.USER));
        when(memoryManagementService.getOverview(7L)).thenReturn(
                new MemoryManagementResponse(new MemoryPersonalizationState(true, 0), 0, java.util.List.of()));

        mockMvc.perform(get("/api/memory/management")
                        .header("Authorization", "Bearer user-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.personalization.enabled").value(true))
                .andExpect(jsonPath("$.data.total").value(0));
    }

    @RestController
    static class TestController {

        @GetMapping("/api/user/profile")
        ApiResponse<String> userProfile() {
            return ApiResponse.ok("user");
        }

        @GetMapping("/api/admin/dashboard")
        ApiResponse<String> adminDashboard() {
            return ApiResponse.ok("admin");
        }

        @PostMapping(value = "/api/auth/user/login", consumes = MediaType.APPLICATION_JSON_VALUE)
        ApiResponse<String> userLogin() {
            return ApiResponse.ok("token");
        }
    }
}
