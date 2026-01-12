package com.vcc.gateway.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request DTO for creating a new tenant.
 */
public record CreateTenantRequest(
        @NotBlank(message = "Tenant ID is required")
        @Size(min = 3, max = 64, message = "Tenant ID must be 3-64 characters")
        @Pattern(regexp = "^[a-zA-Z0-9_-]+$", message = "Tenant ID can only contain letters, numbers, underscores, and hyphens")
        String tenantId,

        @NotBlank(message = "Tenant name is required")
        @Size(max = 255, message = "Tenant name must be at most 255 characters")
        String name,

        @Pattern(regexp = "^(basic|pro|enterprise)$", message = "Plan must be basic, pro, or enterprise")
        String plan,

        Integer rpmLimit,
        Integer tpmLimit,
        Long monthlyTokenCap,
        Double burstMultiplier
) {
    /**
     * Default plan if not specified.
     */
    public String effectivePlan() {
        return plan != null ? plan : "basic";
    }

    /**
     * Default RPM limit based on plan.
     */
    public int effectiveRpmLimit(int defaultRpm) {
        if (rpmLimit != null) {
            return rpmLimit;
        }
        return switch (effectivePlan()) {
            case "enterprise" -> 600;
            case "pro" -> 120;
            default -> defaultRpm;
        };
    }

    /**
     * Default burst multiplier.
     */
    public double effectiveBurstMultiplier() {
        return burstMultiplier != null ? burstMultiplier : 1.5;
    }
}
