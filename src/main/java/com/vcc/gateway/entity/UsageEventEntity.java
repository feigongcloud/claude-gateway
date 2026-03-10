package com.vcc.gateway.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Table("usage_event")
public class UsageEventEntity {

    @Id
    @Column("event_id")
    private Long eventId;

    @Column("request_id")
    private String requestId;

    @Column("msg_id")
    private String msgId;

    @Column("xtrace_id")
    private String xtraceId;

    @Column("tenant_id")
    private String tenantId;

    @Column("user_id")
    private String userId;

    @Column("model")
    private String model;

    @Column("stream")
    private boolean stream;

    @Column("input_tokens")
    private int inputTokens;

    @Column("output_tokens")
    private int outputTokens;

    @Column("total_tokens")
    private int totalTokens;

    @Column("cache_creation_input_tokens")
    private int cacheCreationInputTokens;

    @Column("cache_read_input_tokens")
    private int cacheReadInputTokens;

    @Column("pricing_version")
    private String pricingVersion;

    @Column("cost_usd")
    private BigDecimal costUsd;

    @Column("raw_usage_json")
    private String rawUsageJson;

    @Column("created_at")
    private LocalDateTime createdAt;

    public UsageEventEntity() {
    }

    public Long getEventId() {
        return eventId;
    }

    public void setEventId(Long eventId) {
        this.eventId = eventId;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getMsgId() {
        return msgId;
    }

    public void setMsgId(String msgId) {
        this.msgId = msgId;
    }

    public String getXtraceId() {
        return xtraceId;
    }

    public void setXtraceId(String xtraceId) {
        this.xtraceId = xtraceId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public boolean isStream() {
        return stream;
    }

    public void setStream(boolean stream) {
        this.stream = stream;
    }

    public int getInputTokens() {
        return inputTokens;
    }

    public void setInputTokens(int inputTokens) {
        this.inputTokens = inputTokens;
    }

    public int getOutputTokens() {
        return outputTokens;
    }

    public void setOutputTokens(int outputTokens) {
        this.outputTokens = outputTokens;
    }

    public int getTotalTokens() {
        return totalTokens;
    }

    public void setTotalTokens(int totalTokens) {
        this.totalTokens = totalTokens;
    }

    public int getCacheCreationInputTokens() {
        return cacheCreationInputTokens;
    }

    public void setCacheCreationInputTokens(int cacheCreationInputTokens) {
        this.cacheCreationInputTokens = cacheCreationInputTokens;
    }

    public int getCacheReadInputTokens() {
        return cacheReadInputTokens;
    }

    public void setCacheReadInputTokens(int cacheReadInputTokens) {
        this.cacheReadInputTokens = cacheReadInputTokens;
    }

    public String getPricingVersion() {
        return pricingVersion;
    }

    public void setPricingVersion(String pricingVersion) {
        this.pricingVersion = pricingVersion;
    }

    public BigDecimal getCostUsd() {
        return costUsd;
    }

    public void setCostUsd(BigDecimal costUsd) {
        this.costUsd = costUsd;
    }

    public String getRawUsageJson() {
        return rawUsageJson;
    }

    public void setRawUsageJson(String rawUsageJson) {
        this.rawUsageJson = rawUsageJson;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
