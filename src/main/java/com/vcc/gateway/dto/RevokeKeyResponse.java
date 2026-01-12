package com.vcc.gateway.dto;

import java.time.Instant;

/**
 * Response DTO for API key revocation.
 */
public record RevokeKeyResponse(
        String keyId,
        String tenantId,
        String keyPrefix,
        String previousStatus,
        String newStatus,
        Instant revokedAt,
        boolean cacheInvalidated
) {
    /**
     * Create response for successful revocation.
     */
    public static RevokeKeyResponse success(
            String keyId,
            String tenantId,
            String keyPrefix,
            String previousStatus,
            boolean cacheInvalidated
    ) {
        return new RevokeKeyResponse(
                keyId,
                tenantId,
                keyPrefix,
                previousStatus,
                "revoked",
                Instant.now(),
                cacheInvalidated
        );
    }
}
