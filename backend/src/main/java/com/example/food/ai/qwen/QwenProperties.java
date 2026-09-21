package com.example.food.ai.qwen;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.ai.qwen")
public class QwenProperties {

    private String apiKey = "";
    private String model = "qwen-plus";
    private String visionModel = "qwen-vl-plus";
    private String endpoint = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";
    /**
     * Kept enabled for direct-client compatibility; the application profile
     * explicitly disables the extra planning round for the low-latency path.
     */
    private boolean planningEnabled = true;

    public QwenProperties() {
    }

    public QwenProperties(String apiKey, String model, String endpoint) {
        this.apiKey = apiKey;
        this.model = model;
        this.endpoint = endpoint;
    }

    public String apiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String model() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String visionModel() {
        return visionModel;
    }

    public void setVisionModel(String visionModel) {
        this.visionModel = visionModel;
    }

    public String endpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public boolean planningEnabled() {
        return planningEnabled;
    }

    public void setPlanningEnabled(boolean planningEnabled) {
        this.planningEnabled = planningEnabled;
    }
}
