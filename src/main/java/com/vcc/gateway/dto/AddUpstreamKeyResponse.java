package com.vcc.gateway.dto;

import java.time.Instant;

/**
 * Response DTO for adding an upstream API key.
 */
public record AddUpstreamKeyResponse(
        String keyId,
        String provider,
        String status,
        String keyPrefix,
        Instant createdAt
) {
}
