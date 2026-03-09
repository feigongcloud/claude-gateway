package com.vcc.gateway.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Maps to external 'users' table for balance management.
 * READ-ONLY for most fields, only balance is updated atomically.
 *
 * External users table schema:
 * - uid (unique): User unique identifier
 * - balance: Account balance (DECIMAL 10,2)
 * - status: 0-disabled, 1-normal
 * - is_frozen: 0-normal, 1-frozen
 * - freeze_reason: Reason for freezing
 */
@Table("users")
public class UserBalanceEntity {

    @Id
    @Column("id")
    private Long id;

    @Column("uid")
    private String uid;

    @Column("balance")
    private BigDecimal balance;

    @Column("currency")
    private String currency;

    @Column("status")
    private Integer status;  // 0-disabled, 1-normal

    @Column("is_frozen")
    private Integer isFrozen;  // 0-normal, 1-frozen

    @Column("freeze_reason")
    private String freezeReason;

    @Column("created_at")
    private LocalDateTime createdAt;

    @Column("updated_at")
    private LocalDateTime updatedAt;

    public UserBalanceEntity() {
    }

    // Getters and setters

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUid() {
        return uid;
    }

    public void setUid(String uid) {
        this.uid = uid;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public void setBalance(BigDecimal balance) {
        this.balance = balance;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Integer getIsFrozen() {
        return isFrozen;
    }

    public void setIsFrozen(Integer isFrozen) {
        this.isFrozen = isFrozen;
    }

    public String getFreezeReason() {
        return freezeReason;
    }

    public void setFreezeReason(String freezeReason) {
        this.freezeReason = freezeReason;
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

    // Helper methods

    /**
     * Check if user is active (status = 1).
     */
    public boolean isActive() {
        return status != null && status == 1;
    }

    /**
     * Check if user is frozen (is_frozen = 1).
     */
    public boolean isFrozen() {
        return isFrozen != null && isFrozen == 1;
    }

    /**
     * Check if user has insufficient balance for the required amount.
     */
    public boolean hasInsufficientBalance(BigDecimal requiredAmount) {
        return balance == null || balance.compareTo(requiredAmount) < 0;
    }
}
