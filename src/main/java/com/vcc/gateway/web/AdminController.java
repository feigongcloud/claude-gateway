package com.vcc.gateway.web;

import com.vcc.gateway.crypto.AesGcmCryptoService;
import com.vcc.gateway.dto.*;
import com.vcc.gateway.entity.UpstreamKeySecretEntity;
import com.vcc.gateway.model.QuotaPolicy;
import com.vcc.gateway.repository.UpstreamKeySecretRepository;
import com.vcc.gateway.service.ApiKeyService;
import com.vcc.gateway.service.AuditService;
import com.vcc.gateway.service.KeyPool;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

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
    private final AesGcmCryptoService cryptoService;
    private final UpstreamKeySecretRepository upstreamKeyRepository;

    public AdminController(ApiKeyService apiKeyService,
                           AuditService auditService,
                           KeyPool keyPool,
                           AesGcmCryptoService cryptoService,
                           UpstreamKeySecretRepository upstreamKeyRepository) {
        this.apiKeyService = apiKeyService;
        this.auditService = auditService;
        this.keyPool = keyPool;
        this.cryptoService = cryptoService;
        this.upstreamKeyRepository = upstreamKeyRepository;
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

    /**
     * Rotate an API key (revoke old + create new atomically).
     * POST /admin/tenants/{tenantId}/keys/rotate
     *
     * Use this when downstream customer regenerates their API key.
     * This atomically revokes the old key and creates the new key.
     */
    @PostMapping("/tenants/{tenantId}/keys/rotate")
    public Mono<ResponseEntity<RotateKeyResponse>> rotateApiKey(
            @PathVariable String tenantId,
            @Valid @RequestBody RotateKeyRequest request,
            ServerWebExchange exchange
    ) {
        String actor = getAdminActor(exchange);
        String clientIp = getClientIp(exchange);

        return apiKeyService.rotateApiKey(tenantId, request)
                .flatMap(response -> {
                    // Log both revocation and creation for audit trail
                    return auditService.logKeyRevoked(
                                    actor,
                                    response.oldKeyId(),
                                    response.tenantId(),
                                    response.oldKeyPrefix(),
                                    clientIp
                            )
                            .then(auditService.logKeyCreated(
                                    actor,
                                    response.tenantId(),
                                    response.newKeyId(),
                                    response.newKeyPrefix(),
                                    response.userId(),
                                    clientIp
                            ))
                            .thenReturn(response);
                })
                .map(ResponseEntity::ok)
                .doOnSuccess(r -> log.info("Rotated API key for tenant {} user {} by {}",
                        tenantId, request.userId(), actor));
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

    // ==================== Upstream Key Endpoints ====================

    /**
     * Add a new upstream API key (encrypted).
     * POST /admin/keys/upstream
     */
    @PostMapping("/keys/upstream")
    public Mono<ResponseEntity<AddUpstreamKeyResponse>> addUpstreamKey(
            @Valid @RequestBody AddUpstreamKeyRequest request,
            ServerWebExchange exchange
    ) {
        String actor = getAdminActor(exchange);
        String clientIp = getClientIp(exchange);

        if (!cryptoService.isEnabled()) {
            return Mono.error(new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Encryption not available - master key not configured"));
        }

        return Mono.fromCallable(() -> {
            // Generate key ID
            String keyId = "uk-" + UUID.randomUUID().toString().substring(0, 8);
            String provider = request.effectiveProvider();
            String apiKey = request.apiKey();

            // Encrypt the API key
            AesGcmCryptoService.EncryptedData encrypted = cryptoService.encrypt(apiKey, provider);

            // Create entity
            UpstreamKeySecretEntity entity = new UpstreamKeySecretEntity();
            entity.setUpstreamKeyId(keyId);
            entity.setProvider(provider);
            entity.setStatus("active");
            entity.setKeyVersion(encrypted.keyVersion());
            entity.setIv(encrypted.iv());
            entity.setCiphertext(encrypted.ciphertext());
            entity.setTag(encrypted.tag());
            entity.setAad(encrypted.aad());
            entity.setCreatedAt(Instant.now());
            entity.setUpdatedAt(Instant.now());

            // Extract key prefix for response (first 10 chars)
            String keyPrefix = apiKey.length() > 10 ? apiKey.substring(0, 10) + "..." : "***";

            return new Object[]{entity, keyPrefix};
        })
        .flatMap(arr -> {
            UpstreamKeySecretEntity entity = (UpstreamKeySecretEntity) arr[0];
            String keyPrefix = (String) arr[1];

            return upstreamKeyRepository.save(entity)
                    .map(saved -> new AddUpstreamKeyResponse(
                            saved.getUpstreamKeyId(),
                            saved.getProvider(),
                            saved.getStatus(),
                            keyPrefix,
                            saved.getCreatedAt()
                    ));
        })
        .map(response -> ResponseEntity.status(HttpStatus.CREATED).body(response))
        .doOnSuccess(r -> log.info("Added upstream key {} by {}",
                r.getBody() != null ? r.getBody().keyId() : "unknown", actor));
    }

    /**
     * List upstream keys (without secrets).
     * GET /admin/keys/upstream
     */
    @GetMapping("/keys/upstream")
    public Flux<Map<String, Object>> listUpstreamKeys() {
        return upstreamKeyRepository.findAllActive()
                .map(entity -> {
                    Map<String, Object> info = new HashMap<>();
                    info.put("keyId", entity.getUpstreamKeyId());
                    info.put("provider", entity.getProvider());
                    info.put("status", entity.getStatus());
                    info.put("keyVersion", entity.getKeyVersion());
                    info.put("createdAt", entity.getCreatedAt());
                    return info;
                });
    }

    /**
     * Delete an upstream key.
     * DELETE /admin/keys/upstream/{keyId}
     */
    @DeleteMapping("/keys/upstream/{keyId}")
    public Mono<ResponseEntity<Map<String, Object>>> deleteUpstreamKey(
            @PathVariable String keyId,
            ServerWebExchange exchange
    ) {
        String actor = getAdminActor(exchange);

        return upstreamKeyRepository.findById(keyId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Upstream key not found: " + keyId)))
                .flatMap(entity -> {
                    entity.setNew(false);  // Mark as existing entity for proper delete
                    return upstreamKeyRepository.delete(entity).thenReturn(entity);
                })
                .map(entity -> {
                    Map<String, Object> result = new HashMap<>();
                    result.put("status", "deleted");
                    result.put("keyId", keyId);
                    result.put("provider", entity.getProvider());
                    return ResponseEntity.ok(result);
                })
                .doOnSuccess(r -> log.info("Deleted upstream key {} by {}", keyId, actor));
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
