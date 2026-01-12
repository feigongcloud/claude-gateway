package com.vcc.gateway.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * Request DTO for updating a tenant's quota policy.
 */
public record UpdatePolicyRequest(
        @Min(value = 1, message = "RPM limit must be at least 1")
        @Max(value = 100000, message = "RPM limit must be at most 100000")
        Integer rpmLimit,

        @Min(value = 1, message = "TPM limit must be at least 1")
        Integer tpmLimit,

        @Min(value = 0, message = "Monthly token cap cannot be negative")
        Long monthlyTokenCap,

        @Min(value = 1, message = "Burst multiplier must be at least 1.0")
        @Max(value = 10, message = "Burst multiplier must be at most 10.0")
        Double burstMultiplier
) {
    /**
     * Check if any field is set for update.
     */
    public boolean hasUpdates() {
        return rpmLimit != null || tpmLimit != null ||
                monthlyTokenCap != null || burstMultiplier != null;
    }
}
