package com.vcc.gateway.model;

import com.vcc.gateway.entity.ApiKeyEntity;

import java.time.Instant;

/**
 * Lightweight value object for API key metadata.
 * Used for caching and tenant resolution.
 */
public record ApiKeyInfo(
        String keyId,
        String tenantId,
        String userId,
        String status,
        String scopes,
        Instant expiresAt,
        Instant createdAt
) {

    /**
     * Create from database entity.
     */
    public static ApiKeyInfo fromEntity(ApiKeyEntity entity) {
        return new ApiKeyInfo(
                entity.getKeyId(),
                entity.getTenantId(),
                entity.getUserId(),
                entity.getStatus(),
                entity.getScopes(),
                entity.getExpiresAt(),
                entity.getCreatedAt()
        );
    }

    /**
     * Check if key is active.
     */
    public boolean isActive() {
        return "active".equalsIgnoreCase(status);
    }

    /**
     * Check if key is expired.
     */
    public boolean isExpired() {
        return expiresAt != null && expiresAt.isBefore(Instant.now());
    }

    /**
     * Check if key is valid (active and not expired).
     */
    public boolean isValid() {
        return isActive() && !isExpired();
    }
}
