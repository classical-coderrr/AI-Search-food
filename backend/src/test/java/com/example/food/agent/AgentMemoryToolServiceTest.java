package com.example.food.agent;

import com.example.food.agent.AgentToolRegistry.Tool;
import com.example.food.memory.MemoryEpisode;
import com.example.food.memory.MemoryEpisodeService;
import com.example.food.memory.MemoryManagementService;
import com.example.food.memory.MemoryPersonalizationService;
import com.example.food.memory.MemoryProfileMapper;
import com.example.food.memory.MemoryProcessingJobService;
import com.example.food.memory.MemoryRetrievalResult;
import com.example.food.memory.MemoryRetriever;
import com.example.food.memory.PersonalizedSkillService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentMemoryToolServiceTest {

    @Mock private MemoryRetriever retriever;
    @Mock private MemoryProfileMapper profileMapper;
    @Mock private MemoryEpisodeService episodeService;
    @Mock private MemoryManagementService managementService;
    @Mock private MemoryPersonalizationService personalizationService;
    @Mock private PersonalizedSkillService skillService;
    @Mock private MemoryProcessingJobService jobService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void refusesPersonalMemoryReadsWhenPersonalizationIsDisabled() throws Exception {
        when(personalizationService.isEnabled(7L)).thenReturn(false);

        AgentKitchenToolService.ToolResult result = service().executeRead(Tool.MEMORY_SEARCH,
                objectMapper.readTree("{\"query\":\"我之前喜欢什么\"}"), 7L);

        assertThat(result.summary()).contains("已关闭个性化");
        verify(retriever, never()).search(any(), any());
        verify(profileMapper, never()).findOwned(any());
    }

    @Test
    void refusesConfirmedPreferenceDeclarationWhenPersonalizationIsDisabled() throws Exception {
        when(personalizationService.isEnabled(7L)).thenReturn(false);
        JsonNode payload = objectMapper.readTree("""
                {"entity":"鸡胸肉","preference":"LIKE","candidateType":"INGREDIENT_PREFERENCE",
                 "evidence":"我喜欢鸡胸肉"}
                """);

        assertThatThrownBy(() -> service().executeConfirmed(
                "MEMORY_PREFERENCE_DECLARATION", payload, 7L, "disabled-write-key"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("已关闭个性化");

        verify(episodeService, never()).record(eq(7L), any());
        verify(jobService, never()).enqueue(any(), any());
    }

    @Test
    void refusesConfirmedPreferenceUpdateWhenPersonalizationIsDisabled() throws Exception {
        when(personalizationService.isEnabled(7L)).thenReturn(false);
        JsonNode payload = objectMapper.readTree("""
                {"memoryId":14,"version":3,"preference":"DISLIKE"}
                """);

        assertThatThrownBy(() -> service().executeConfirmed(
                "MEMORY_PREFERENCE_UPDATE", payload, 7L, "disabled-update-key"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("已关闭个性化");

        verify(managementService, never()).requireActiveAgentMemory(any(), any(), any());
        verify(managementService, never()).updateItem(any(), any(), any());
    }

    @Test
    void scopesMemorySearchToTheAuthenticatedUser() throws Exception {
        when(personalizationService.isEnabled(17L)).thenReturn(true);
        when(retriever.search(eq(17L), any())).thenReturn(new MemoryRetrievalResult(null, List.of(), null));

        AgentKitchenToolService.ToolResult result = service().executeRead(Tool.MEMORY_SEARCH,
                objectMapper.readTree("{\"query\":\"之前收藏的晚餐\",\"limit\":6}"), 17L);

        assertThat(result.summary()).contains("不包含外部菜谱知识");
        ArgumentCaptor<com.example.food.memory.MemorySearchCommand> command =
                ArgumentCaptor.forClass(com.example.food.memory.MemorySearchCommand.class);
        verify(retriever).search(eq(17L), command.capture());
        assertThat(command.getValue().limit()).isEqualTo(6);
    }

    @Test
    void rejectsBlankMemorySearchQueries() throws Exception {
        when(personalizationService.isEnabled(17L)).thenReturn(true);

        assertThatThrownBy(() -> service().executeRead(Tool.MEMORY_SEARCH,
                objectMapper.readTree("{\"query\":\"  \"}"), 17L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("query 不能为空");

        verify(retriever, never()).search(any(), any());
    }

    @Test
    void recipeHistoryUsesAnEpisodeTypeFilterBeforeApplyingTheLimit() throws Exception {
        when(personalizationService.isEnabled(7L)).thenReturn(true);
        MemoryEpisode saved = new MemoryEpisode();
        saved.setEpisodeType("RECIPE_SAVED");
        when(episodeService.listOwnedRecipeHistory(7L, 7)).thenReturn(List.of(saved));

        AgentKitchenToolService.ToolResult result = service().executeRead(Tool.MEMORY_RECIPE_HISTORY,
                objectMapper.readTree("{\"limit\":7}"), 7L);

        assertThat(result.payload()).asList().containsExactly(saved);
        verify(episodeService).listOwnedRecipeHistory(7L, 7);
        verify(episodeService, never()).listOwned(eq(7L), any(), any(), anyInt());
    }

    @Test
    void onlyAcceptsAnExplicitPreferenceQuoteFromTheCurrentUserMessage() throws Exception {
        AgentMemoryToolService service = service();
        JsonNode declaration = objectMapper.readTree("""
                {"entity":"香菜","preference":"AVOID","candidateType":"INGREDIENT_PREFERENCE","evidence":"我不吃香菜"}
                """);

        service.validateDeclaration(declaration, "我不吃香菜，以后推荐时帮我避开。");

        assertThatThrownBy(() -> service.validateDeclaration(
                objectMapper.readTree("{\"entity\":\"鸡胸肉\",\"preference\":\"LIKE\",\"evidence\":\"我今天想吃鸡胸肉\"}"),
                "我今天想吃鸡胸肉"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("偏好方向");
        assertThatThrownBy(() -> service.validateDeclaration(declaration, "我今天想吃香菜"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("明确内容");
    }

    @Test
    void validatesExecutionPreferenceKeysValuesAndVerbatimEvidence() throws Exception {
        AgentMemoryToolService service = service();
        JsonNode declaration = objectMapper.readTree("""
                {"entity":"CANDIDATE_COUNT","preference":"3","candidateType":"SKILL_PREFERENCE",
                 "evidence":"以后每次给我三个选项"}
                """);

        service.validateDeclaration(declaration, "以后每次给我三个选项，回答也简洁一点。");

        assertThatThrownBy(() -> service.validateDeclaration(
                objectMapper.readTree("""
                        {"entity":"CANDIDATE_COUNT","preference":"5","candidateType":"SKILL_PREFERENCE",
                         "evidence":"以后每次给我三个选项"}
                        """),
                "以后每次给我三个选项"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("执行习惯");
        assertThatThrownBy(() -> service.validateDeclaration(
                objectMapper.readTree("""
                        {"entity":"UNKNOWN","preference":"3","candidateType":"SKILL_PREFERENCE",
                         "evidence":"以后每次给我三个选项"}
                        """),
                "以后每次给我三个选项"))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void validatesThatPreferenceUpdatesUseTheCurrentUsersExpectedMemoryVersion() throws Exception {
        when(managementService.requireActiveAgentMemory(7L, 14L, 3)).thenReturn(
                new com.example.food.memory.MemoryManagementItemResponse(14L, "INGREDIENT_PREFERENCE",
                        "鸡胸肉", "LIKE", new BigDecimal("0.8000"), new BigDecimal("0.7000"),
                        2, 2, 2, "USER", "LONG_TERM", null, null, 3, false, true));

        var selected = service().validateUpdate(objectMapper.readTree("""
                {"memoryId":14,"version":3,"preference":"DISLIKE"}
                """), 7L);

        assertThat(selected.entity()).isEqualTo("鸡胸肉");
        assertThat(selected.version()).isEqualTo(3);
        verify(managementService).requireActiveAgentMemory(7L, 14L, 3);
    }

    @Test
    void validatesExecutionPreferenceUpdatesAgainstTheirOwnKey() throws Exception {
        when(managementService.requireActiveAgentMemory(7L, 15L, 2)).thenReturn(
                new com.example.food.memory.MemoryManagementItemResponse(15L, "SKILL_PREFERENCE",
                        "RESPONSE_STYLE", "CONCISE", new BigDecimal("0.9500"), new BigDecimal("0.9500"),
                        1, 1, 1, "USER", "LONG_TERM", null, null, 2, false, true));

        var selected = service().validateUpdate(objectMapper.readTree("""
                {"memoryId":15,"version":2,"preference":"DETAILED"}
                """), 7L);

        assertThat(selected.memoryType()).isEqualTo("SKILL_PREFERENCE");
    }

    @Test
    void keepsExistingStrengthWhenConfirmedUpdateOmitsStrength() throws Exception {
        when(personalizationService.isEnabled(7L)).thenReturn(true);
        var current = new com.example.food.memory.MemoryManagementItemResponse(14L, "INGREDIENT_PREFERENCE",
                "鸡胸肉", "LIKE", new BigDecimal("0.7000"), new BigDecimal("0.7000"),
                2, 2, 2, "USER", "LONG_TERM", null, null, 3, false, true);
        when(managementService.requireActiveAgentMemory(7L, 14L, 3)).thenReturn(current);
        AgentMemoryToolService service = service();
        JsonNode payload = objectMapper.readTree("""
                {"memoryId":14,"version":3,"preference":"DISLIKE"}
                """);

        service.executeConfirmed("MEMORY_PREFERENCE_UPDATE", payload, 7L, "update-key-14");

        ArgumentCaptor<com.example.food.memory.MemoryItemUpdateRequest> update =
                ArgumentCaptor.forClass(com.example.food.memory.MemoryItemUpdateRequest.class);
        verify(managementService).updateItem(eq(7L), eq(14L), update.capture());
        assertThat(update.getValue().strength()).isEqualByComparingTo("0.7000");
    }

    @Test
    void confirmedPreferenceIsStoredAsAnEpisodeAndQueuedOnceByItsIdempotencyKey() throws Exception {
        when(personalizationService.isEnabled(7L)).thenReturn(true);
        MemoryEpisode episode = new MemoryEpisode();
        episode.setId(41L);
        when(episodeService.record(eq(7L), any())).thenReturn(new MemoryEpisodeService.RecordResult(episode, false));
        AgentMemoryToolService service = service();
        JsonNode payload = objectMapper.readTree("""
                {"entity":"鸡胸肉","preference":"LIKE","candidateType":"INGREDIENT_PREFERENCE","evidence":"我喜欢鸡胸肉"}
                """);

        AgentKitchenActionService.ActionResult result = service.executeConfirmed(
                "MEMORY_PREFERENCE_DECLARATION", payload, 7L, "write-key-41");

        assertThat(result.message()).contains("后台更新");
        ArgumentCaptor<com.example.food.memory.MemoryEpisodeCommand> command =
                ArgumentCaptor.forClass(com.example.food.memory.MemoryEpisodeCommand.class);
        verify(episodeService).record(eq(7L), command.capture());
        assertThat(command.getValue().episodeType()).isEqualTo("USER_PREFERENCE_DECLARED");
        assertThat(command.getValue().idempotencyKey()).isEqualTo("write-key-41");
        assertThat(command.getValue().payloadJson()).contains("鸡胸肉", "我喜欢鸡胸肉");
        verify(jobService).enqueue(7L, 41L);
    }

    @Test
    void confirmedExecutionPreferenceKeepsItsKeyAndQuotedEvidenceInTheEpisode() throws Exception {
        when(personalizationService.isEnabled(7L)).thenReturn(true);
        MemoryEpisode episode = new MemoryEpisode();
        episode.setId(42L);
        when(episodeService.record(eq(7L), any())).thenReturn(new MemoryEpisodeService.RecordResult(episode, false));
        JsonNode payload = objectMapper.readTree("""
                {"entity":"RESPONSE_STYLE","preference":"CONCISE","candidateType":"SKILL_PREFERENCE",
                 "evidence":"以后回答简洁一点"}
                """);

        service().executeConfirmed("MEMORY_PREFERENCE_DECLARATION", payload, 7L, "style-key-42");

        ArgumentCaptor<com.example.food.memory.MemoryEpisodeCommand> command =
                ArgumentCaptor.forClass(com.example.food.memory.MemoryEpisodeCommand.class);
        verify(episodeService).record(eq(7L), command.capture());
        assertThat(command.getValue().payloadJson()).contains("SKILL_PREFERENCE", "RESPONSE_STYLE",
                "CONCISE", "以后回答简洁一点");
        verify(jobService).enqueue(7L, 42L);
    }

    private AgentMemoryToolService service() {
        return new AgentMemoryToolService(retriever, profileMapper, episodeService, managementService,
                personalizationService, skillService, jobService, objectMapper);
    }
}
