package com.vcc.gateway.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * Request DTO for creating a new API key.
 */
public record CreateKeyRequest(
        @NotBlank(message = "User ID is required")
        @Size(min = 1, max = 64, message = "User ID must be 1-64 characters")
        String userId,

        @Size(max = 256, message = "Scopes must be at most 256 characters")
        @Pattern(regexp = "^[a-z:,]+$", message = "Scopes must be comma-separated lowercase identifiers")
        String scopes,

        Instant expiresAt,

        @Size(max = 255, message = "Description must be at most 255 characters")
        String description
) {
    /**
     * Default scopes if not specified.
     */
    public String effectiveScopes() {
        return scopes != null && !scopes.isBlank() ? scopes : "messages:write";
    }
}
