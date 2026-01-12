package com.vcc.gateway.dto;

import java.time.Instant;

/**
 * Response DTO for API key creation.
 * Contains the plaintext key which is ONLY returned once.
 */
public record CreateKeyResponse(
        String keyId,
        String tenantId,
        String userId,
        String keyPrefix,
        String apiKey,        // Plaintext key - only returned on creation!
        String scopes,
        Instant expiresAt,
        Instant createdAt,
        String warning
) {
    /**
     * Create response with plaintext key.
     */
    public static CreateKeyResponse create(
            String keyId,
            String tenantId,
            String userId,
            String keyPrefix,
            String plaintextKey,
            String scopes,
            Instant expiresAt,
            Instant createdAt
    ) {
        return new CreateKeyResponse(
                keyId,
                tenantId,
                userId,
                keyPrefix,
                plaintextKey,
                scopes,
                expiresAt,
                createdAt,
                "Store this API key securely. It will NOT be shown again."
        );
    }
}
