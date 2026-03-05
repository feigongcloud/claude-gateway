package com.vcc.gateway.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request DTO for adding an upstream API key.
 */
public record AddUpstreamKeyRequest(
        @NotBlank(message = "Provider is required")
        @Size(max = 32, message = "Provider must be at most 32 characters")
        String provider,

        @NotBlank(message = "API key is required")
        String apiKey
) {
    /**
     * Default provider if not specified.
     */
    public String effectiveProvider() {
        return provider != null && !provider.isBlank() ? provider : "anthropic";
    }
}
