package com.example.food.admin.dashboard;

import com.example.food.admin.dashboard.dto.AdminAgentObservabilityResponse;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

@Service
public class AgentObservabilityAlertService {

    public static final String OPEN = "OPEN";
    public static final String RESOLVED = "RESOLVED";
    public static final String WARNING = "WARNING";
    public static final String FAILURE_RATE = "FAILURE_RATE";
    public static final String RECOVERY_RATE = "RECOVERY_RATE";
    public static final String DUPLICATE_WRITE_RATE = "DUPLICATE_WRITE_RATE";

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final AgentObservabilityAlertMapper mapper;
    private final Environment environment;
    private final Clock clock;

    public AgentObservabilityAlertService(
            AgentObservabilityAlertMapper mapper,
            Environment environment,
            Clock clock
    ) {
        this.mapper = mapper;
        this.environment = environment;
        this.clock = clock;
    }

    public void evaluate(AdminAgentObservabilityResponse snapshot) {
        if (!property("app.agent.observability.alerts.enabled", Boolean.class, true)
                || snapshot == null
                || snapshot.metrics() == null) {
            return;
        }
        AdminAgentObservabilityResponse.Metrics metrics = snapshot.metrics();
        int minimumSamples = Math.max(1, property(
                "app.agent.observability.alerts.minimum-samples", Integer.class, 5
        ));
        if (metrics.runsStarted() < minimumSamples) {
            return;
        }

        List<Rule> rules = List.of(
                new Rule(
                        FAILURE_RATE,
                        "Agent 失败率过高",
                        metrics.runsStarted() == 0 ? 0D : (double) metrics.runsFailed() / metrics.runsStarted(),
                        property("app.agent.observability.alerts.failure-rate-threshold", Double.class, 0.2D),
                        "最近 Agent 失败率已超过阈值，请结合异常日志和恢复记录定位。"
                ),
                new Rule(
                        RECOVERY_RATE,
                        "Agent 恢复占比过高",
                        metrics.runsStarted() == 0 ? 0D : (double) metrics.runsRecovered() / metrics.runsStarted(),
                        property("app.agent.observability.alerts.recovery-rate-threshold", Double.class, 0.2D),
                        "最近 Agent 频繁依赖崩溃恢复，请检查容器稳定性和外部模型依赖。"
                ),
                new Rule(
                        DUPLICATE_WRITE_RATE,
                        "Agent 重复写入拦截率过高",
                        metrics.eventsPersisted() == 0 ? 0D : (double) metrics.duplicateWrites() / metrics.eventsPersisted(),
                        property("app.agent.observability.alerts.duplicate-write-rate-threshold", Double.class, 0.1D),
                        "幂等保护正在频繁拦截重复写入，请检查客户端重试或任务重放行为。"
                )
        );

        for (Rule rule : rules) {
            if (DUPLICATE_WRITE_RATE.equals(rule.type()) && metrics.eventsPersisted() < minimumSamples) {
                continue;
            }
            upsert(rule, rule.value() >= Math.max(0D, rule.threshold()));
        }
    }

    private void upsert(Rule rule, boolean triggered) {
        String dedupeKey = "AGENT_OBSERVABILITY|" + rule.type();
        AgentObservabilityAlert existing = mapper.findByDedupeKey(dedupeKey);
        LocalDateTime now = LocalDateTime.ofInstant(Instant.now(clock), ZONE);
        if (triggered) {
            if (existing == null) {
                AgentObservabilityAlert alert = new AgentObservabilityAlert();
                alert.setAlertType(rule.type());
                alert.setSeverity(WARNING);
                alert.setStatus(OPEN);
                alert.setTitle(rule.title());
                alert.setMessage(rule.message());
                alert.setMetricValue(decimal(rule.value()));
                alert.setThresholdValue(decimal(rule.threshold()));
                alert.setDedupeKey(dedupeKey);
                alert.setFirstSeenAt(now);
                alert.setLastSeenAt(now);
                alert.setCreatedAt(now);
                alert.setUpdatedAt(now);
                mapper.insert(alert);
                return;
            }
            existing.setSeverity(WARNING);
            existing.setStatus(OPEN);
            existing.setTitle(rule.title());
            existing.setMessage(rule.message());
            existing.setMetricValue(decimal(rule.value()));
            existing.setThresholdValue(decimal(rule.threshold()));
            existing.setLastSeenAt(now);
            existing.setResolvedAt(null);
            existing.setUpdatedAt(now);
            mapper.updateById(existing);
            return;
        }

        if (existing != null && OPEN.equals(existing.getStatus())) {
            existing.setStatus(RESOLVED);
            existing.setResolvedAt(now);
            existing.setLastSeenAt(now);
            existing.setUpdatedAt(now);
            mapper.updateById(existing);
        }
    }

    private BigDecimal decimal(double value) {
        return BigDecimal.valueOf(Math.max(0D, value));
    }

    private <T> T property(String key, Class<T> type, T fallback) {
        return environment.getProperty(key, type, fallback);
    }

    private record Rule(String type, String title, double value, double threshold, String message) {
    }
}
