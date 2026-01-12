package com.vcc.gateway.web;

import com.vcc.gateway.dto.*;
import com.vcc.gateway.model.QuotaPolicy;
import com.vcc.gateway.service.ApiKeyService;
import com.vcc.gateway.service.AuditService;
import com.vcc.gateway.service.KeyPool;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;

/**
 * Admin API controller for tenant and key management.
 * Protected by AdminSecurityFilter via X-Admin-Api-Key header.
 */
@RestController
@RequestMapping("/admin")
public class AdminController {
    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    private static final String ADMIN_ACTOR_ATTR = "adminActor";

    private final ApiKeyService apiKeyService;
    private final AuditService auditService;
    private final KeyPool keyPool;

    public AdminController(ApiKeyService apiKeyService,
                           AuditService auditService,
                           KeyPool keyPool) {
        this.apiKeyService = apiKeyService;
        this.auditService = auditService;
        this.keyPool = keyPool;
    }

    // ==================== Tenant Endpoints ====================

    /**
     * Create a new tenant.
     * POST /admin/tenants
     */
    @PostMapping("/tenants")
    public Mono<ResponseEntity<CreateTenantResponse>> createTenant(
            @Valid @RequestBody CreateTenantRequest request,
            ServerWebExchange exchange
    ) {
        String actor = getAdminActor(exchange);
        String clientIp = getClientIp(exchange);

        return apiKeyService.createTenant(request)
                .flatMap(response ->
                        auditService.logTenantCreated(actor, response.tenantId(), response.plan(), clientIp)
                                .thenReturn(response)
                )
                .map(response -> ResponseEntity.status(HttpStatus.CREATED).body(response))
                .doOnSuccess(r -> log.info("Created tenant {} by {}", request.tenantId(), actor));
    }

    /**
     * Get tenant details.
     * GET /admin/tenants/{tenantId}
     */
    @GetMapping("/tenants/{tenantId}")
    public Mono<ResponseEntity<CreateTenantResponse>> getTenant(
            @PathVariable String tenantId
    ) {
        return apiKeyService.getTenant(tenantId)
                .map(ResponseEntity::ok);
    }

    // ==================== API Key Endpoints ====================

    /**
     * Create a new API key for a tenant.
     * POST /admin/tenants/{tenantId}/keys
     */
    @PostMapping("/tenants/{tenantId}/keys")
    public Mono<ResponseEntity<CreateKeyResponse>> createApiKey(
            @PathVariable String tenantId,
            @Valid @RequestBody CreateKeyRequest request,
            ServerWebExchange exchange
    ) {
        String actor = getAdminActor(exchange);
        String clientIp = getClientIp(exchange);

        return apiKeyService.createApiKey(tenantId, request)
                .flatMap(response ->
                        auditService.logKeyCreated(
                                        actor,
                                        response.tenantId(),
                                        response.keyId(),
                                        response.keyPrefix(),
                                        response.userId(),
                                        clientIp
                                )
                                .thenReturn(response)
                )
                .map(response -> ResponseEntity.status(HttpStatus.CREATED).body(response))
                .doOnSuccess(r -> log.info("Created API key for tenant {} by {}",
                        tenantId, actor));
    }

    /**
     * List API keys for a tenant.
     * GET /admin/tenants/{tenantId}/keys
     */
    @GetMapping("/tenants/{tenantId}/keys")
    public Flux<ApiKeyService.ApiKeyInfo> listTenantKeys(
            @PathVariable String tenantId
    ) {
        return apiKeyService.listTenantKeys(tenantId);
    }

    /**
     * Revoke an API key.
     * POST /admin/keys/{keyId}/revoke
     */
    @PostMapping("/keys/{keyId}/revoke")
    public Mono<ResponseEntity<RevokeKeyResponse>> revokeKey(
            @PathVariable String keyId,
            ServerWebExchange exchange
    ) {
        String actor = getAdminActor(exchange);
        String clientIp = getClientIp(exchange);

        return apiKeyService.revokeKey(keyId)
                .flatMap(response ->
                        auditService.logKeyRevoked(
                                        actor,
                                        response.keyId(),
                                        response.tenantId(),
                                        response.keyPrefix(),
                                        clientIp
                                )
                                .thenReturn(response)
                )
                .map(ResponseEntity::ok)
                .doOnSuccess(r -> log.info("Revoked API key {} by {}", keyId, actor));
    }

    // ==================== Quota Policy Endpoints ====================

    /**
     * Update tenant quota policy.
     * PUT /admin/tenants/{tenantId}/policy
     */
    @PutMapping("/tenants/{tenantId}/policy")
    public Mono<ResponseEntity<QuotaPolicy>> updatePolicy(
            @PathVariable String tenantId,
            @Valid @RequestBody UpdatePolicyRequest request,
            ServerWebExchange exchange
    ) {
        String actor = getAdminActor(exchange);
        String clientIp = getClientIp(exchange);

        return apiKeyService.updateQuotaPolicy(tenantId, request)
                .flatMap(policy -> {
                    Map<String, Object> changes = new HashMap<>();
                    changes.put("rpmLimit", policy.rpmLimit());
                    changes.put("burstMultiplier", policy.burstMultiplier());
                    return auditService.logPolicyUpdated(actor, tenantId, changes, clientIp)
                            .thenReturn(policy);
                })
                .map(ResponseEntity::ok)
                .doOnSuccess(r -> log.info("Updated policy for tenant {} by {}",
                        tenantId, actor));
    }

    // ==================== Operations Endpoints ====================

    /**
     * Refresh upstream key pool.
     * POST /admin/keys/refresh
     */
    @PostMapping("/keys/refresh")
    public Mono<ResponseEntity<Map<String, Object>>> refreshKeyPool(
            ServerWebExchange exchange
    ) {
        String actor = getAdminActor(exchange);
        String clientIp = getClientIp(exchange);

        return keyPool.refresh()
                .flatMap(count ->
                        auditService.logKeysRefreshed(actor, count, clientIp)
                                .thenReturn(count)
                )
                .map(count -> {
                    Map<String, Object> result = new HashMap<>();
                    result.put("status", "success");
                    result.put("keyCount", count);
                    result.put("message", "Key pool refreshed successfully");
                    return ResponseEntity.ok(result);
                })
                .doOnSuccess(r -> log.info("Key pool refreshed by {}", actor));
    }

    /**
     * Get key pool status.
     * GET /admin/keys/status
     */
    @GetMapping("/keys/status")
    public Mono<ResponseEntity<Map<String, Object>>> getKeyPoolStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("keyCount", keyPool.getKeyCount());
        status.put("databaseEnabled", keyPool.isDatabaseEnabled());
        return Mono.just(ResponseEntity.ok(status));
    }

    // ==================== Helper Methods ====================

    /**
     * Get admin actor from exchange attribute (set by AdminSecurityFilter).
     */
    private String getAdminActor(ServerWebExchange exchange) {
        Object actor = exchange.getAttribute(ADMIN_ACTOR_ATTR);
        return actor != null ? actor.toString() : "unknown";
    }

    /**
     * Get client IP address from exchange.
     */
    private String getClientIp(ServerWebExchange exchange) {
        // Check for proxy headers
        String forwardedFor = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }

        String realIp = exchange.getRequest().getHeaders().getFirst("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp;
        }

        InetSocketAddress remoteAddress = exchange.getRequest().getRemoteAddress();
        if (remoteAddress != null && remoteAddress.getAddress() != null) {
            return remoteAddress.getAddress().getHostAddress();
        }

        return "unknown";
    }
}
