package com.example.food.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Local-only controls for reproducing the write transaction crash window.
 * All pauses are disabled by default and must be explicitly enabled for a
 * Docker drill.
 */
@ConfigurationProperties(prefix = "app.agent.write-drill")
public record AgentWriteDrillProperties(
        boolean enabled,
        Duration pauseBeforeBusinessWrite,
        Duration pauseAfterBusinessWrite
) {
    public AgentWriteDrillProperties {
        pauseBeforeBusinessWrite = pauseBeforeBusinessWrite == null
                ? Duration.ZERO
                : pauseBeforeBusinessWrite;
        pauseAfterBusinessWrite = pauseAfterBusinessWrite == null
                ? Duration.ZERO
                : pauseAfterBusinessWrite;
    }
}
