package com.vcc.gateway.dto;

import com.vcc.gateway.entity.TenantEntity;
import com.vcc.gateway.model.QuotaPolicy;

import java.time.Instant;

/**
 * Response DTO for tenant creation.
 */
public record CreateTenantResponse(
        String tenantId,
        String name,
        String plan,
        String status,
        QuotaPolicyInfo quotaPolicy,
        Instant createdAt
) {
    /**
     * Create response from entity and policy.
     */
    public static CreateTenantResponse fromEntity(TenantEntity tenant, QuotaPolicy policy) {
        return new CreateTenantResponse(
                tenant.getTenantId(),
                tenant.getName(),
                tenant.getPlan(),
                tenant.getStatus(),
                policy != null ? new QuotaPolicyInfo(
                        policy.rpmLimit(),
                        policy.tpmLimit(),
                        policy.monthlyTokenCap(),
                        policy.burstMultiplier()
                ) : null,
                tenant.getCreatedAt()
        );
    }

    /**
     * Nested record for quota policy info.
     */
    public record QuotaPolicyInfo(
            int rpmLimit,
            Integer tpmLimit,
            Long monthlyTokenCap,
            double burstMultiplier
    ) {}
}
