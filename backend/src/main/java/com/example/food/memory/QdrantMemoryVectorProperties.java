package com.example.food.memory;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;

@Component
@ConfigurationProperties(prefix = "app.memory.vector.qdrant")
public class QdrantMemoryVectorProperties {
    private boolean enabled;
    private String url = "http://localhost:6333";
    private String apiKey = "";
    private String collection = "xiaochuling_memory_v1";
    private Duration connectTimeout = Duration.ofSeconds(2);
    private Duration readTimeout = Duration.ofSeconds(4);
    private Duration pollInterval = Duration.ofSeconds(5);
    private Duration consistencyCheckInterval = Duration.ofMinutes(5);
    private Duration identityAuditInterval = Duration.ofSeconds(5);
    private int pollBatchSize = 100;
    private int identityAuditBatchSize = 500;

    public boolean enabled() { return enabled && StringUtils.hasText(url); }
    public void setEnabled(boolean value) { enabled = value; }
    public String url() { return url; }
    public void setUrl(String value) { url = value; }
    public String apiKey() { return apiKey; }
    public void setApiKey(String value) { apiKey = value; }
    public String collection() { return collection; }
    public void setCollection(String value) { collection = value; }
    public Duration connectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration value) { connectTimeout = value; }
    public Duration readTimeout() { return readTimeout; }
    public void setReadTimeout(Duration value) { readTimeout = value; }
    public Duration pollInterval() { return pollInterval; }
    public void setPollInterval(Duration value) { pollInterval = value; }
    public Duration consistencyCheckInterval() { return consistencyCheckInterval; }
    public void setConsistencyCheckInterval(Duration value) { consistencyCheckInterval = value; }
    public Duration identityAuditInterval() { return identityAuditInterval; }
    public void setIdentityAuditInterval(Duration value) { identityAuditInterval = value; }
    public int pollBatchSize() { return pollBatchSize; }
    public void setPollBatchSize(int value) { pollBatchSize = value; }
    public int identityAuditBatchSize() { return identityAuditBatchSize; }
    public void setIdentityAuditBatchSize(int value) { identityAuditBatchSize = value; }
}
