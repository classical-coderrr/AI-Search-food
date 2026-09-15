package com.example.food.agent;

import com.example.food.ai.recipe.dto.RecipeGenerateResponse;
import com.example.food.recipe.SavedRecipeService;
import com.example.food.recipe.dto.RecipeHistoryDetailResponse;
import com.example.food.recipe.dto.SaveRecipeRequest;
import com.example.food.security.AppRole;
import com.example.food.security.AuthPrincipal;
import com.example.food.security.UserSecurityLogService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentWriteServiceTest {

    @Mock
    private AgentConfirmationMapper confirmationMapper;

    @Mock
    private SavedRecipeService savedRecipeService;

    @Mock
    private AgentKitchenActionService actionService;

    @Mock
    private UserSecurityLogService securityLogService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private AgentWriteService service;
    private final AuthPrincipal principal = new AuthPrincipal(7L, "13800138000", AppRole.USER);

    @BeforeEach
    void setUp() {
        service = new AgentWriteService(confirmationMapper, savedRecipeService, actionService, securityLogService, objectMapper);
    }

    @Test
    void savesRecipeOnceAfterAtomicClaimAndRecordsSecurityEvent() throws Exception {
        RecipeGenerateResponse recipe = recipe();
        AgentConfirmation confirmation = confirmation("PENDING", objectMapper.writeValueAsString(recipe));
        RecipeHistoryDetailResponse detail = new RecipeHistoryDetailResponse(
                88L, 42L, LocalDateTime.now(), "鸡蛋", "dinner", "balanced", recipe
        );
        when(confirmationMapper.findOwned(7L, 9L)).thenReturn(confirmation);
        when(confirmationMapper.claim(7L, 9L)).thenReturn(1);
        when(confirmationMapper.markConfirmed(7L, 9L, "菜谱已保存到我的菜谱")).thenReturn(1);
        when(savedRecipeService.save(any(SaveRecipeRequest.class), eq(principal), isNull())).thenReturn(detail);

        AgentWriteService.ConfirmationResult result = service.saveRecipe(principal, 9L, "key-9");

        assertThat(result.status()).isEqualTo("completed");
        assertThat(result.detail()).isEqualTo(detail);
        ArgumentCaptor<SaveRecipeRequest> request = ArgumentCaptor.forClass(SaveRecipeRequest.class);
        verify(savedRecipeService).save(request.capture(), eq(principal), isNull());
        assertThat(request.getValue().searchLogId()).isEqualTo(42L);
        assertThat(request.getValue().title()).isEqualTo("番茄炒蛋");
        verify(confirmationMapper).markConfirmed(7L, 9L, "菜谱已保存到我的菜谱");
        verify(securityLogService).record(7L, "AGENT_SAVE_RECIPE", "/api/agent/chat/stream", "confirmationId=9");
    }

    @Test
    void returnsAlreadySavedWithoutWritingAgain() throws Exception {
        AgentConfirmation confirmation = confirmation("CONFIRMED", objectMapper.writeValueAsString(recipe()));
        when(confirmationMapper.findOwned(7L, 9L)).thenReturn(confirmation);

        AgentWriteService.ConfirmationResult result = service.saveRecipe(principal, 9L, "key-9");

        assertThat(result.status()).isEqualTo("already-completed");
        verify(confirmationMapper, never()).claim(7L, 9L);
        verify(savedRecipeService, never()).save(any(), any(), any());
    }

    @Test
    void executesGenericKitchenActionOnlyAfterAtomicClaim() {
        AgentConfirmation confirmation = confirmation("PENDING", "{\"id\":12}");
        confirmation.setActionType("PANTRY_DELETE");
        when(confirmationMapper.findOwned(7L, 9L)).thenReturn(confirmation);
        when(confirmationMapper.claim(7L, 9L)).thenReturn(1);
        when(confirmationMapper.markConfirmed(7L, 9L, "食材已删除")).thenReturn(1);
        when(actionService.execute(eq("PANTRY_DELETE"), any(), eq(principal), anyString()))
                .thenReturn(new AgentKitchenActionService.ActionResult("食材已删除", null));

        AgentWriteService.ConfirmationResult result = service.execute(principal, 9L, "key-9");

        assertThat(result.status()).isEqualTo("completed");
        assertThat(result.message()).isEqualTo("食材已删除");
        verify(confirmationMapper).markConfirmed(7L, 9L, "食材已删除");
        verify(securityLogService).record(7L, "AGENT_PANTRY_DELETE", "/api/agent/chat/stream", "confirmationId=9");
    }

    @Test
    void doesNotExecuteAgainWhileAnotherRequestIsProcessing() {
        AgentConfirmation confirmation = confirmation("PROCESSING", "{\"id\":12}");
        confirmation.setActionType("PANTRY_DELETE");
        confirmation.setProcessingAt(LocalDateTime.now());
        when(confirmationMapper.findOwned(7L, 9L)).thenReturn(confirmation);

        AgentWriteService.ConfirmationResult result = service.execute(principal, 9L, "key-9");

        assertThat(result.status()).isEqualTo("processing");
        verify(confirmationMapper, never()).claim(7L, 9L);
        verify(actionService, never()).execute(any(), any(), any(), anyString());
    }

    @Test
    void returnsProcessingWhenAtomicClaimLosesRace() {
        AgentConfirmation pending = confirmation("PENDING", "{\"id\":12}");
        pending.setActionType("PANTRY_DELETE");
        AgentConfirmation current = confirmation("PROCESSING", "{\"id\":12}");
        current.setActionType("PANTRY_DELETE");
        current.setProcessingAt(LocalDateTime.now());
        when(confirmationMapper.findOwned(7L, 9L)).thenReturn(pending, current);
        when(confirmationMapper.claim(7L, 9L)).thenReturn(0);

        AgentWriteService.ConfirmationResult result = service.execute(principal, 9L, " key-9 ");

        assertThat(result.status()).isEqualTo("processing");
        verify(actionService, never()).execute(any(), any(), any(), anyString());
    }

    @Test
    void movesStaleProcessingToManualReviewWithoutRetryingWrite() {
        AgentConfirmation confirmation = confirmation("PROCESSING", "{\"id\":12}");
        confirmation.setActionType("PANTRY_DELETE");
        confirmation.setProcessingAt(LocalDateTime.now().minusHours(1));
        when(confirmationMapper.findOwned(7L, 9L)).thenReturn(confirmation);
        when(confirmationMapper.markUnknownIfStale(eq(7L), eq(9L), any(LocalDateTime.class))).thenReturn(1);

        AgentWriteService.ConfirmationResult result = service.execute(principal, 9L, "key-9");

        assertThat(result.status()).isEqualTo("unknown-review");
        verify(actionService, never()).execute(any(), any(), any(), anyString());
    }

    @Test
    void exposesConfirmationStatusForClientAndOperations() {
        AgentConfirmation confirmation = confirmation("PROCESSING", "{\"id\":12}");
        confirmation.setActionType("PANTRY_DELETE");
        confirmation.setProcessingAt(LocalDateTime.now());
        when(confirmationMapper.findOwned(7L, 9L)).thenReturn(confirmation);

        var result = service.status(principal, 9L);

        assertThat(result.confirmationId()).isEqualTo(9L);
        assertThat(result.actionType()).isEqualTo("PANTRY_DELETE");
        assertThat(result.status()).isEqualTo("PROCESSING");
        verify(actionService, never()).execute(any(), any(), any(), anyString());
    }

    private AgentConfirmation confirmation(String status, String payload) {
        AgentConfirmation confirmation = new AgentConfirmation();
        confirmation.setId(9L);
        confirmation.setUserId(7L);
        confirmation.setActionType("SAVE_RECIPE");
        confirmation.setIdempotencyKey("key-9");
        confirmation.setPayloadJson(payload);
        confirmation.setStatus(status);
        confirmation.setCreatedAt(LocalDateTime.now());
        return confirmation;
    }

    private RecipeGenerateResponse recipe() {
        return new RecipeGenerateResponse(
                "番茄炒蛋",
                "家常快手菜",
                List.of("补充蛋白质"),
                List.of(new RecipeGenerateResponse.Ingredient("鸡蛋", "2个")),
                List.of(new RecipeGenerateResponse.Step(1, "炒制", "鸡蛋下锅炒熟。", 5)),
                List.of("少油烹饪"),
                List.of("番茄炒蛋"),
                "qwen",
                "qwen-plus",
                42L
        );
    }
}
