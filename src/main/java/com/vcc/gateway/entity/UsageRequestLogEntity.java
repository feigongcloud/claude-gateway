package com.vcc.gateway.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.time.LocalDateTime;

@Table("usage_request_log")
public class UsageRequestLogEntity implements Persistable<String> {

    @Id
    @Column("request_id")
    private String requestId;

    @Transient
    private boolean isNew = true;

    @Column("tenant_id")
    private String tenantId;

    @Column("user_id")
    private String userId;

    @Column("plan")
    private String plan;

    @Column("model")
    private String model;

    @Column("stream")
    private boolean stream;

    @Column("status_code")
    private int statusCode;

    @Column("duration_ms")
    private int durationMs;

    @Column("upstream_key_id")
    private String upstreamKeyId;

    @Column("error_code")
    private String errorCode;

    @Column("created_at")
    private LocalDateTime createdAt;

    public UsageRequestLogEntity() {
    }

    @Override
    public String getId() {
        return requestId;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    public void setNew(boolean isNew) {
        this.isNew = isNew;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
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

    public String getPlan() {
        return plan;
    }

    public void setPlan(String plan) {
        this.plan = plan;
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

    public int getStatusCode() {
        return statusCode;
    }

    public void setStatusCode(int statusCode) {
        this.statusCode = statusCode;
    }

    public int getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(int durationMs) {
        this.durationMs = durationMs;
    }

    public String getUpstreamKeyId() {
        return upstreamKeyId;
    }

    public void setUpstreamKeyId(String upstreamKeyId) {
        this.upstreamKeyId = upstreamKeyId;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
