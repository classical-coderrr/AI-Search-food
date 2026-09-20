package com.example.food.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Adds deterministic pauses around a real write transaction so the Docker
 * drill can terminate the backend at a known point. It is a no-op unless the
 * explicit drill switch is enabled.
 */
@Component
@EnableConfigurationProperties(AgentWriteDrillProperties.class)
public class AgentWriteDrill {

    private static final Logger log = LoggerFactory.getLogger(AgentWriteDrill.class);

    private final AgentWriteDrillProperties properties;

    public AgentWriteDrill(AgentWriteDrillProperties properties) {
        this.properties = properties;
    }

    public void pauseBeforeBusinessWrite(Long userId, String idempotencyKey) {
        pause("before business write", properties.pauseBeforeBusinessWrite(), userId, idempotencyKey);
    }

    public void pauseAfterBusinessWrite(Long userId, String idempotencyKey) {
        pause("after business write", properties.pauseAfterBusinessWrite(), userId, idempotencyKey);
    }

    private void pause(String point, Duration duration, Long userId, String idempotencyKey) {
        if (!properties.enabled() || duration == null || duration.isZero() || duration.isNegative()) {
            return;
        }
        log.warn("Agent write drill paused {}: userId={}, idempotencyKey={}, duration={}",
                point, userId, idempotencyKey, duration);
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("写操作演练暂停被中断", interrupted);
        }
    }
}
