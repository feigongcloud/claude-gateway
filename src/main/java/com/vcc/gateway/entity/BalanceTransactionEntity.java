package com.vcc.gateway.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Maps to 'balance_transaction' table for audit trail.
 * Records all balance operations in the two-phase deduction system.
 *
 * Transaction types:
 * - RESERVE: Initial balance deduction before request (Phase 1)
 * - COMMIT: Mark reserve as completed
 * - REFUND: Return funds (excess or failed request)
 * - ADJUST: Additional charge if actual > estimated
 *
 * Status values:
 * - pending: Reserve created, awaiting settlement
 * - completed: Successfully settled
 * - failed: Operation failed
 * - rolled_back: Reserve refunded due to failure
 */
@Table("balance_transaction")
public class BalanceTransactionEntity {

    @Id
    @Column("transaction_id")
    private Long transactionId;

    @Column("uid")
    private String uid;

    @Column("request_id")
    private String requestId;

    @Column("transaction_type")
    private String transactionType;  // RESERVE, COMMIT, REFUND, ADJUST

    @Column("amount")
    private BigDecimal amount;  // Negative for deductions, positive for refunds

    @Column("balance_before")
    private BigDecimal balanceBefore;

    @Column("balance_after")
    private BigDecimal balanceAfter;

    @Column("estimated_cost")
    private BigDecimal estimatedCost;

    @Column("actual_cost")
    private BigDecimal actualCost;

    @Column("model")
    private String model;

    @Column("tokens_estimated")
    private Integer tokensEstimated;

    @Column("tokens_actual")
    private Integer tokensActual;

    @Column("status")
    private String status;  // pending, completed, failed, rolled_back

    @Column("error_message")
    private String errorMessage;

    @Column("created_at")
    private LocalDateTime createdAt;

    public BalanceTransactionEntity() {
    }

    // Getters and setters

    public Long getTransactionId() {
        return transactionId;
    }

    public void setTransactionId(Long transactionId) {
        this.transactionId = transactionId;
    }

    public String getUid() {
        return uid;
    }

    public void setUid(String uid) {
        this.uid = uid;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getTransactionType() {
        return transactionType;
    }

    public void setTransactionType(String transactionType) {
        this.transactionType = transactionType;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public BigDecimal getBalanceBefore() {
        return balanceBefore;
    }

    public void setBalanceBefore(BigDecimal balanceBefore) {
        this.balanceBefore = balanceBefore;
    }

    public BigDecimal getBalanceAfter() {
        return balanceAfter;
    }

    public void setBalanceAfter(BigDecimal balanceAfter) {
        this.balanceAfter = balanceAfter;
    }

    public BigDecimal getEstimatedCost() {
        return estimatedCost;
    }

    public void setEstimatedCost(BigDecimal estimatedCost) {
        this.estimatedCost = estimatedCost;
    }

    public BigDecimal getActualCost() {
        return actualCost;
    }

    public void setActualCost(BigDecimal actualCost) {
        this.actualCost = actualCost;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public Integer getTokensEstimated() {
        return tokensEstimated;
    }

    public void setTokensEstimated(Integer tokensEstimated) {
        this.tokensEstimated = tokensEstimated;
    }

    public Integer getTokensActual() {
        return tokensActual;
    }

    public void setTokensActual(Integer tokensActual) {
        this.tokensActual = tokensActual;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
