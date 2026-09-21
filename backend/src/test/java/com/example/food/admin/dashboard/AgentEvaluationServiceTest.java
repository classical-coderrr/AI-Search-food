package com.example.food.admin.dashboard;

import com.example.food.agent.AgentToolRegistry;
import com.example.food.admin.dashboard.dto.AdminAgentEvaluationResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentEvaluationServiceTest {

    @Test
    void evaluatesRoutingBoundariesAndPersistsEveryCase() {
        AgentEvaluationRunMapper runMapper = mock(AgentEvaluationRunMapper.class);
        AgentEvaluationCaseResultMapper caseResultMapper = mock(AgentEvaluationCaseResultMapper.class);
        when(runMapper.insert(any(AgentEvaluationRun.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, AgentEvaluationRun.class).setId(7L);
            return 1;
        });

        AgentEvaluationService service = new AgentEvaluationService(
                new AgentToolRegistry(),
                new ObjectMapper(),
                runMapper,
                caseResultMapper,
                Clock.fixed(Instant.parse("2026-09-21T08:00:00Z"), ZoneOffset.UTC)
        );

        AdminAgentEvaluationResponse response = service.run();

        assertThat(response.id()).isEqualTo(7L);
        assertThat(response.status()).isEqualTo(AgentEvaluationService.PASSED);
        assertThat(response.totalCases()).isEqualTo(6);
        assertThat(response.passedCases()).isEqualTo(6);
        assertThat(response.failedCases()).isZero();
        assertThat(response.cases()).hasSize(6).allSatisfy(result -> assertThat(result.passed()).isTrue());
        verify(runMapper).insert(any(AgentEvaluationRun.class));
        verify(caseResultMapper, org.mockito.Mockito.times(6)).insert(any(AgentEvaluationCaseResult.class));
    }

    @Test
    void readsLatestRunAndItsStructuredCaseResults() {
        AgentEvaluationRun run = new AgentEvaluationRun();
        run.setId(3L);
        run.setStatus(AgentEvaluationService.FAILED);
        run.setStartedAt(LocalDateTime.of(2026, 9, 21, 16, 0));
        run.setFinishedAt(LocalDateTime.of(2026, 9, 21, 16, 0, 1));
        run.setTotalCases(1);
        run.setPassedCases(0);
        run.setFailedCases(1);
        run.setDurationMs(1L);
        AgentEvaluationCaseResult result = new AgentEvaluationCaseResult();
        result.setCaseKey("SAVE_RECIPE_EXPLICIT");
        result.setDescription("明确保存菜谱");
        result.setInputMessage("保存这道菜");
        result.setPassed(false);
        result.setExpectedTools("[\"recipe_save\"]");
        result.setActualTools("[\"recipe_generate\"]");
        result.setFailureReason("缺少工具：recipe_save");

        AgentEvaluationRunMapper runMapper = mock(AgentEvaluationRunMapper.class);
        AgentEvaluationCaseResultMapper caseResultMapper = mock(AgentEvaluationCaseResultMapper.class);
        when(runMapper.findLatest()).thenReturn(run);
        when(caseResultMapper.findByRunId(3L)).thenReturn(List.of(result));

        AdminAgentEvaluationResponse response = new AgentEvaluationService(
                new AgentToolRegistry(),
                new ObjectMapper(),
                runMapper,
                caseResultMapper,
                Clock.systemUTC()
        ).latest();

        assertThat(response.status()).isEqualTo(AgentEvaluationService.FAILED);
        assertThat(response.totalCases()).isEqualTo(1);
        assertThat(response.cases()).singleElement().satisfies(caseResult -> {
            assertThat(caseResult.passed()).isFalse();
            assertThat(caseResult.expectedTools()).containsExactly("recipe_save");
            assertThat(caseResult.actualTools()).containsExactly("recipe_generate");
        });
    }
}
