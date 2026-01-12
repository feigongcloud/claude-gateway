package com.vcc.gateway.model;

import com.vcc.gateway.entity.QuotaPolicyEntity;

import java.math.BigDecimal;

/**
 * Immutable value object representing a tenant's quota policy.
 * Used in TenantContext and for rate limiting decisions.
 */
public record QuotaPolicy(
        int rpmLimit,           // Requests per minute
        Integer tpmLimit,       // Tokens per minute (optional)
        Long monthlyTokenCap,   // Monthly token limit (optional)
        double burstMultiplier  // Burst capacity multiplier (e.g., 1.5 = 150% burst)
) {

    /**
     * Create default policy with given RPM limit.
     */
    public static QuotaPolicy defaultPolicy(int defaultRpm) {
        return new QuotaPolicy(defaultRpm, null, null, 1.5);
    }

    /**
     * Create from database entity.
     */
    public static QuotaPolicy fromEntity(QuotaPolicyEntity entity) {
        return new QuotaPolicy(
                entity.getRpmLimit(),
                entity.getTpmLimit(),
                entity.getMonthlyTokenCap(),
                entity.getBurstMultiplier() != null
                        ? entity.getBurstMultiplier().doubleValue()
                        : 1.5
        );
    }

    /**
     * Calculate effective burst capacity.
     */
    public int getBurstCapacity() {
        return (int) Math.ceil(rpmLimit * burstMultiplier);
    }

    /**
     * Check if TPM limit is configured.
     */
    public boolean hasTpmLimit() {
        return tpmLimit != null && tpmLimit > 0;
    }

    /**
     * Check if monthly cap is configured.
     */
    public boolean hasMonthlyTokenCap() {
        return monthlyTokenCap != null && monthlyTokenCap > 0;
    }
}
