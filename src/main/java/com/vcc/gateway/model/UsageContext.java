package com.vcc.gateway.model;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Mutable context for collecting usage data during request processing.
 * Thread-safe for use in reactive streams.
 */
public class UsageContext {

    private final String requestId;
    private final Instant startTime;
    private final String model;
    private final boolean stream;

    // Populated during response processing
    private final AtomicInteger inputTokens = new AtomicInteger(0);
    private final AtomicInteger outputTokens = new AtomicInteger(0);
    private final AtomicInteger cacheCreationInputTokens = new AtomicInteger(0);
    private final AtomicInteger cacheReadInputTokens = new AtomicInteger(0);
    private final AtomicReference<Integer> statusCode = new AtomicReference<>();
    private final AtomicReference<String> upstreamKeyId = new AtomicReference<>();
    private final AtomicReference<String> rawUsageJson = new AtomicReference<>();
    private final AtomicReference<String> errorCode = new AtomicReference<>();
    private final AtomicReference<String> msgId = new AtomicReference<>();

    public UsageContext(String requestId, String model, boolean stream) {
        this.requestId = requestId;
        this.startTime = Instant.now();
        this.model = model;
        this.stream = stream;
    }

    // Thread-safe setters for streaming accumulation
    public void addInputTokens(int tokens) {
        inputTokens.addAndGet(tokens);
    }

    public void addOutputTokens(int tokens) {
        outputTokens.addAndGet(tokens);
    }

    public void setCacheCreationInputTokens(int tokens) {
        cacheCreationInputTokens.set(tokens);
    }

    public void setCacheReadInputTokens(int tokens) {
        cacheReadInputTokens.set(tokens);
    }

    public void setStatusCode(int code) {
        statusCode.set(code);
    }

    public void setUpstreamKeyId(String keyId) {
        upstreamKeyId.set(keyId);
    }

    public void setRawUsageJson(String json) {
        rawUsageJson.set(json);
    }

    public void setErrorCode(String code) {
        errorCode.set(code);
    }

    public void setMsgId(String id) {
        msgId.set(id);
    }

    public long getDurationMs() {
        return Duration.between(startTime, Instant.now()).toMillis();
    }

    public int getTotalTokens() {
        return inputTokens.get() + outputTokens.get();
    }

    // Getters
    public String getRequestId() {
        return requestId;
    }

    public Instant getStartTime() {
        return startTime;
    }

    public String getModel() {
        return model;
    }

    public boolean isStream() {
        return stream;
    }

    public int getInputTokens() {
        return inputTokens.get();
    }

    public int getOutputTokens() {
        return outputTokens.get();
    }

    public int getCacheCreationInputTokens() {
        return cacheCreationInputTokens.get();
    }

    public int getCacheReadInputTokens() {
        return cacheReadInputTokens.get();
    }

    public Integer getStatusCode() {
        return statusCode.get();
    }

    public String getUpstreamKeyId() {
        return upstreamKeyId.get();
    }

    public String getRawUsageJson() {
        return rawUsageJson.get();
    }

    public String getErrorCode() {
        return errorCode.get();
    }

    public String getMsgId() {
        return msgId.get();
    }
}
