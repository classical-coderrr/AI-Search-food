package com.example.food.admin.dashboard;

import com.example.food.admin.dashboard.dto.AdminMemoryEvaluationResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MemoryEvaluationConflictAdjudicationTest {

    @Autowired private MemoryEvaluationService evaluationService;
    @Autowired private MemoryEvaluationRunMapper runMapper;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void measuresOfflineConflictAdjudicationSeparatelyFromOnlineAccuracy() {
        AdminMemoryEvaluationResponse response = evaluationService.run();

        assertThat(response.suiteVersion()).isEqualTo("memory-evaluation-v6");
        assertThat(response.metrics().conflictAdjudicationAccuracy()).isEqualTo(1D);
        assertThat(response.cases())
                .filteredOn(testCase -> "CONFLICT_ADJUDICATION".equals(testCase.metric()))
                .hasSize(4)
                .allMatch(AdminMemoryEvaluationResponse.CaseResult::passed);
        assertThat(response.cases())
                .filteredOn(testCase -> "SKILL_EXECUTION_PREFERENCE".equals(testCase.metric()))
                .hasSize(3)
                .allMatch(AdminMemoryEvaluationResponse.CaseResult::passed);
        assertThat(response.unmeasuredMetrics())
                .anySatisfy(metric -> {
                    assertThat(metric.metric()).isEqualTo("conflictResolutionAccuracy");
                    assertThat(metric.status()).isEqualTo("NOT_MEASURED");
                });
    }

    @Test
    void latestEvaluationRefreshesExplanationsForPreviouslyStoredResults() throws Exception {
        AdminMemoryEvaluationResponse completed = evaluationService.run();
        MemoryEvaluationRun storedRun = runMapper.selectById(completed.id());
        ObjectNode payload = (ObjectNode) objectMapper.readTree(storedRun.getResultJson());
        ArrayNode staleExplanations = objectMapper.createArrayNode();
        staleExplanations.addObject()
                .put("metric", "wrongMemoryUsageRate")
                .put("status", "NOT_MEASURED")
                .put("reason", "旧说明：尚未收集用户反馈");
        payload.set("unmeasuredMetrics", staleExplanations);
        storedRun.setResultJson(objectMapper.writeValueAsString(payload));
        runMapper.updateById(storedRun);

        AdminMemoryEvaluationResponse latest = evaluationService.latest();

        assertThat(latest.unmeasuredMetrics())
                .filteredOn(metric -> "wrongMemoryUsageRate".equals(metric.metric()))
                .singleElement()
                .satisfies(metric -> {
                    assertThat(metric.reason()).contains("用户主动标注");
                    assertThat(metric.reason()).doesNotContain("尚未收集");
                });
    }
}
