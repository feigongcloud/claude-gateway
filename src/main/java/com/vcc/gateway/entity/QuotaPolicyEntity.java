package com.vcc.gateway.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.Instant;

@Table("quota_policy")
public class QuotaPolicyEntity implements Persistable<String> {

    @Id
    @Column("tenant_id")
    private String tenantId;

    @Column("rpm_limit")
    private int rpmLimit;

    @Column("tpm_limit")
    private Integer tpmLimit;

    @Column("monthly_token_cap")
    private Long monthlyTokenCap;

    @Column("burst_multiplier")
    private BigDecimal burstMultiplier;

    @Column("created_at")
    private LocalDateTime createdAt;

    @Column("updated_at")
    private LocalDateTime updatedAt;

    @Transient
    private boolean isNew = true;

    public QuotaPolicyEntity() {
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public int getRpmLimit() {
        return rpmLimit;
    }

    public void setRpmLimit(int rpmLimit) {
        this.rpmLimit = rpmLimit;
    }

    public Integer getTpmLimit() {
        return tpmLimit;
    }

    public void setTpmLimit(Integer tpmLimit) {
        this.tpmLimit = tpmLimit;
    }

    public Long getMonthlyTokenCap() {
        return monthlyTokenCap;
    }

    public void setMonthlyTokenCap(Long monthlyTokenCap) {
        this.monthlyTokenCap = monthlyTokenCap;
    }

    public BigDecimal getBurstMultiplier() {
        return burstMultiplier;
    }

    public void setBurstMultiplier(BigDecimal burstMultiplier) {
        this.burstMultiplier = burstMultiplier;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    @Override
    public String getId() {
        return tenantId;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    public void setNew(boolean isNew) {
        this.isNew = isNew;
    }
}
