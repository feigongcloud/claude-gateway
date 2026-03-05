package com.vcc.gateway.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * Request to rotate (replace) an API key.
 * This atomically revokes the old key and creates a new one.
 */
public record RotateKeyRequest(
        @NotBlank(message = "userId is required")
        @Size(max = 100, message = "userId must be <= 100 characters")
        String userId,

        @NotBlank(message = "oldApiKey is required")
        @Size(min = 10, max = 100, message = "oldApiKey must be 10-100 characters")
        String oldApiKey,

        @NotBlank(message = "newApiKey is required")
        @Size(min = 10, max = 100, message = "newApiKey must be 10-100 characters")
        String newApiKey,

        @Size(max = 256, message = "scopes must be <= 256 characters")
        String scopes,

        Instant expiresAt,

        @Size(max = 500, message = "description must be <= 500 characters")
        String description
) {
    public String effectiveScopes() {
        return scopes != null && !scopes.isBlank() ? scopes : "messages:write";
    }

    public String effectiveDescription() {
        return description != null && !description.isBlank()
                ? description
                : "API key rotated";
    }
}
