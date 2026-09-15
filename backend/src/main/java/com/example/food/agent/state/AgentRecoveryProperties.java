package com.example.food.agent.state;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "app.agent.recovery")
public record AgentRecoveryProperties(
        boolean enabled,
        Duration staleAfter,
        Duration leaseDuration
) {
    public AgentRecoveryProperties {
        staleAfter = staleAfter == null ? Duration.ofMinutes(5) : staleAfter;
        leaseDuration = leaseDuration == null ? Duration.ofMinutes(10) : leaseDuration;
    }
}
