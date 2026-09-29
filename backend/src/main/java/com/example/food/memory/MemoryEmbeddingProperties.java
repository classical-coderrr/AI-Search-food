package com.example.food.memory;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@ConfigurationProperties(prefix = "app.memory.embedding")
public class MemoryEmbeddingProperties {
    private boolean enabled = true;
    private String model = "text-embedding-v3";
    private int dimensions = 1024;
    private int batchSize = 12;
    private int recallLimit = 200;
    private int scanLimit = 5000;
    private int pollBatchSize = 24;
    private long indexUserId;
    private Duration retryBaseDelay = Duration.ofSeconds(5);
    private Duration retryMaxDelay = Duration.ofMinutes(30);
    private Duration leaseDuration = Duration.ofMinutes(2);

    public boolean enabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public String model() { return model; }
    public void setModel(String value) { model = value; }
    public int dimensions() { return dimensions; }
    public void setDimensions(int value) { dimensions = value; }
    public int batchSize() { return batchSize; }
    public void setBatchSize(int value) { batchSize = value; }
    public int recallLimit() { return recallLimit; }
    public void setRecallLimit(int value) { recallLimit = value; }
    public int scanLimit() { return scanLimit; }
    public void setScanLimit(int value) { scanLimit = value; }
    public int pollBatchSize() { return pollBatchSize; }
    public void setPollBatchSize(int value) { pollBatchSize = value; }
    public Long indexUserId() { return indexUserId > 0 ? indexUserId : null; }
    public void setIndexUserId(long value) { indexUserId = value; }
    public Duration retryBaseDelay() { return retryBaseDelay; }
    public void setRetryBaseDelay(Duration value) { retryBaseDelay = value; }
    public Duration retryMaxDelay() { return retryMaxDelay; }
    public void setRetryMaxDelay(Duration value) { retryMaxDelay = value; }
    public Duration leaseDuration() { return leaseDuration; }
    public void setLeaseDuration(Duration value) { leaseDuration = value; }
}
