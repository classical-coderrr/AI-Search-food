package com.example.food.admin.dashboard;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = "app.agent.evaluation.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class AgentEvaluationScheduler {

    private static final Logger log = LoggerFactory.getLogger(AgentEvaluationScheduler.class);

    private final AgentEvaluationService service;

    public AgentEvaluationScheduler(AgentEvaluationService service) {
        this.service = service;
    }

    @Scheduled(
            fixedDelayString = "${app.agent.evaluation.interval:P1D}",
            initialDelayString = "${app.agent.evaluation.initial-delay:PT2M}",
            zone = "Asia/Shanghai"
    )
    public void evaluate() {
        try {
            service.run();
        } catch (RuntimeException exception) {
            log.warn("Unable to run Agent evaluation set", exception);
        }
    }
}
