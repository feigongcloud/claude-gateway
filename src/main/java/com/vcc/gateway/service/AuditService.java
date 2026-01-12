package com.vcc.gateway.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vcc.gateway.entity.AdminAuditLogEntity;
import com.vcc.gateway.repository.AdminAuditLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;

/**
 * Audit logging service for admin operations.
 * Records all admin API actions with actor, target, and details.
 */
@Service
public class AuditService {
    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AdminAuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    // Standard action types
    public static final String ACTION_CREATE_TENANT = "CREATE_TENANT";
    public static final String ACTION_UPDATE_TENANT = "UPDATE_TENANT";
    public static final String ACTION_DELETE_TENANT = "DELETE_TENANT";
    public static final String ACTION_CREATE_KEY = "CREATE_KEY";
    public static final String ACTION_REVOKE_KEY = "REVOKE_KEY";
    public static final String ACTION_UPDATE_POLICY = "UPDATE_POLICY";
    public static final String ACTION_REFRESH_KEYS = "REFRESH_KEYS";

    // Target types
    public static final String TARGET_TENANT = "tenant";
    public static final String TARGET_API_KEY = "api_key";
    public static final String TARGET_QUOTA_POLICY = "quota_policy";
    public static final String TARGET_UPSTREAM_KEY = "upstream_key";

    public AuditService(AdminAuditLogRepository auditLogRepository,
                        ObjectMapper objectMapper) {
        this.auditLogRepository = auditLogRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Log an admin action.
     *
     * @param actor      The admin user performing the action
     * @param action     The action type (e.g., CREATE_TENANT)
     * @param targetType The type of target (e.g., tenant, api_key)
     * @param targetId   The ID of the target
     * @param details    Additional details as key-value pairs
     * @param clientIp   Client IP address
     * @return The saved audit log entry
     */
    public Mono<AdminAuditLogEntity> logAction(
            String actor,
            String action,
            String targetType,
            String targetId,
            Map<String, Object> details,
            String clientIp
    ) {
        AdminAuditLogEntity entity = new AdminAuditLogEntity();
        entity.setActor(actor != null ? actor : "unknown");
        entity.setAction(action);
        entity.setTargetType(targetType);
        entity.setTargetId(targetId);
        entity.setClientIp(clientIp);
        entity.setCreatedAt(Instant.now());

        // Serialize details to JSON
        if (details != null && !details.isEmpty()) {
            try {
                entity.setDetailJson(objectMapper.writeValueAsString(details));
            } catch (JsonProcessingException e) {
                log.warn("Failed to serialize audit details: {}", e.getMessage());
                entity.setDetailJson("{}");
            }
        }

        return auditLogRepository.save(entity)
                .doOnSuccess(saved -> log.info("Audit: {} {} {} by {} from {}",
                        action, targetType, targetId, actor, clientIp))
                .doOnError(e -> log.error("Failed to save audit log: {}", e.getMessage()));
    }

    /**
     * Log a tenant creation.
     */
    public Mono<AdminAuditLogEntity> logTenantCreated(
            String actor,
            String tenantId,
            String plan,
            String clientIp
    ) {
        return logAction(
                actor,
                ACTION_CREATE_TENANT,
                TARGET_TENANT,
                tenantId,
                Map.of("plan", plan),
                clientIp
        );
    }

    /**
     * Log an API key creation.
     */
    public Mono<AdminAuditLogEntity> logKeyCreated(
            String actor,
            String tenantId,
            String keyId,
            String keyPrefix,
            String userId,
            String clientIp
    ) {
        return logAction(
                actor,
                ACTION_CREATE_KEY,
                TARGET_API_KEY,
                keyId,
                Map.of(
                        "tenantId", tenantId,
                        "keyPrefix", keyPrefix,
                        "userId", userId
                ),
                clientIp
        );
    }

    /**
     * Log an API key revocation.
     */
    public Mono<AdminAuditLogEntity> logKeyRevoked(
            String actor,
            String keyId,
            String tenantId,
            String keyPrefix,
            String clientIp
    ) {
        return logAction(
                actor,
                ACTION_REVOKE_KEY,
                TARGET_API_KEY,
                keyId,
                Map.of(
                        "tenantId", tenantId,
                        "keyPrefix", keyPrefix
                ),
                clientIp
        );
    }

    /**
     * Log a quota policy update.
     */
    public Mono<AdminAuditLogEntity> logPolicyUpdated(
            String actor,
            String tenantId,
            Map<String, Object> changes,
            String clientIp
    ) {
        return logAction(
                actor,
                ACTION_UPDATE_POLICY,
                TARGET_QUOTA_POLICY,
                tenantId,
                changes,
                clientIp
        );
    }

    /**
     * Log upstream key refresh.
     */
    public Mono<AdminAuditLogEntity> logKeysRefreshed(
            String actor,
            int keyCount,
            String clientIp
    ) {
        return logAction(
                actor,
                ACTION_REFRESH_KEYS,
                TARGET_UPSTREAM_KEY,
                null,
                Map.of("keyCount", keyCount),
                clientIp
        );
    }

    // ==================== Query Methods ====================

    /**
     * Get recent audit logs.
     */
    public Flux<AdminAuditLogEntity> getRecentLogs(int limit) {
        return auditLogRepository.findRecent(Math.min(limit, 1000));
    }

    /**
     * Get audit logs for a specific target.
     */
    public Flux<AdminAuditLogEntity> getLogsForTarget(String targetType, String targetId) {
        return auditLogRepository.findByTargetTypeAndTargetId(targetType, targetId);
    }

    /**
     * Get audit logs by actor.
     */
    public Flux<AdminAuditLogEntity> getLogsByActor(String actor) {
        return auditLogRepository.findByActor(actor);
    }

    /**
     * Get audit logs within a time range.
     */
    public Flux<AdminAuditLogEntity> getLogsByTimeRange(Instant from, Instant to) {
        return auditLogRepository.findByTimeRange(from, to);
    }
}
