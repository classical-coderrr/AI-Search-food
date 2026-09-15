package com.example.food.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration for the model fallback used when deterministic intent rules
 * cannot decide whether a user wants to save a recipe.
 */
@Component
@ConfigurationProperties(prefix = "app.agent.intent-recognition")
public class AgentIntentProperties {

    private boolean enabled = true;
    private double confidenceThreshold = 0.85d;

    public boolean enabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public double confidenceThreshold() {
        return confidenceThreshold;
    }

    public void setConfidenceThreshold(double confidenceThreshold) {
        this.confidenceThreshold = confidenceThreshold;
    }
}
