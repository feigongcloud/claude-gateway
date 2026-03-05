package com.vcc.gateway.dto;

import java.time.LocalDateTime;

/**
 * Response for API key rotation operation.
 * Contains information about both the revoked old key and the newly created key.
 */
public record RotateKeyResponse(
        boolean success,
        boolean oldKeyRevoked,
        String oldKeyId,
        String oldKeyPrefix,
        boolean newKeyCreated,
        String newKeyId,
        String newKeyPrefix,
        String tenantId,
        String userId,
        LocalDateTime rotatedAt,
        String message
) {
    public static RotateKeyResponse success(
            String oldKeyId,
            String oldKeyPrefix,
            String newKeyId,
            String newKeyPrefix,
            String tenantId,
            String userId,
            LocalDateTime rotatedAt
    ) {
        return new RotateKeyResponse(
                true,
                true,
                oldKeyId,
                oldKeyPrefix,
                true,
                newKeyId,
                newKeyPrefix,
                tenantId,
                userId,
                rotatedAt,
                "API key rotated successfully"
        );
    }

    public static RotateKeyResponse partialSuccess(
            String oldKeyId,
            String oldKeyPrefix,
            boolean oldKeyRevoked,
            String newKeyId,
            String newKeyPrefix,
            boolean newKeyCreated,
            String tenantId,
            String userId,
            LocalDateTime rotatedAt,
            String message
    ) {
        return new RotateKeyResponse(
                oldKeyRevoked && newKeyCreated,
                oldKeyRevoked,
                oldKeyId,
                oldKeyPrefix,
                newKeyCreated,
                newKeyId,
                newKeyPrefix,
                tenantId,
                userId,
                rotatedAt,
                message
        );
    }
}
