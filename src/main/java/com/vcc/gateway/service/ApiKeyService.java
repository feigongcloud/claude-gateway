package com.vcc.gateway.service;

import com.vcc.gateway.crypto.KeyGeneratorService;
import com.vcc.gateway.dto.*;
import com.vcc.gateway.entity.ApiKeyEntity;
import com.vcc.gateway.entity.QuotaPolicyEntity;
import com.vcc.gateway.entity.TenantEntity;
import com.vcc.gateway.model.QuotaPolicy;
import com.vcc.gateway.repository.ApiKeyRepository;
import com.vcc.gateway.repository.QuotaPolicyRepository;
import com.vcc.gateway.repository.TenantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;

/**
 * Service for managing tenants and API keys.
 * Provides Admin API operations with cache invalidation.
 */
@Service
public class ApiKeyService {
    private static final Logger log = LoggerFactory.getLogger(ApiKeyService.class);
    private static final ZoneId BEIJING_ZONE = ZoneId.of("Asia/Shanghai");

    private final TenantRepository tenantRepository;
    private final ApiKeyRepository apiKeyRepository;
    private final QuotaPolicyRepository quotaPolicyRepository;
    private final KeyGeneratorService keyGeneratorService;
    private final CacheService cacheService;
    private final int defaultRpm;

    public ApiKeyService(TenantRepository tenantRepository,
                         ApiKeyRepository apiKeyRepository,
                         QuotaPolicyRepository quotaPolicyRepository,
                         KeyGeneratorService keyGeneratorService,
                         CacheService cacheService,
                         com.vcc.gateway.config.GwProperties properties) {
        this.tenantRepository = tenantRepository;
        this.apiKeyRepository = apiKeyRepository;
        this.quotaPolicyRepository = quotaPolicyRepository;
        this.keyGeneratorService = keyGeneratorService;
        this.cacheService = cacheService;
        this.defaultRpm = properties.getDefaultRpm();
    }

    /**
     * Get current time in Beijing timezone as LocalDateTime.
     * This ensures database timestamps match Beijing time (UTC+8).
     */
    private LocalDateTime nowBeijing() {
        return LocalDateTime.now(BEIJING_ZONE);
    }

    // ==================== Tenant Operations ====================

    /**
     * Create a new tenant with optional quota policy.
     */
    @Transactional
    public Mono<CreateTenantResponse> createTenant(CreateTenantRequest request) {
        String tenantId = request.tenantId();

        // Check if tenant already exists
        return tenantRepository.findById(tenantId)
                .flatMap(existing -> Mono.<CreateTenantResponse>error(
                        new ResponseStatusException(HttpStatus.CONFLICT,
                                "Tenant already exists: " + tenantId)))
                .switchIfEmpty(Mono.defer(() -> {
                    // Create tenant entity
                    TenantEntity tenant = new TenantEntity();
                    tenant.setTenantId(tenantId);
                    tenant.setName(request.name());
                    tenant.setPlan(request.effectivePlan());
                    tenant.setStatus("active");
                    tenant.setCreatedAt(nowBeijing());
                    tenant.setUpdatedAt(nowBeijing());

                    // Create quota policy entity
                    QuotaPolicyEntity policy = new QuotaPolicyEntity();
                    policy.setTenantId(tenantId);
                    policy.setRpmLimit(request.effectiveRpmLimit(defaultRpm));
                    policy.setTpmLimit(request.tpmLimit());
                    policy.setMonthlyTokenCap(request.monthlyTokenCap());
                    policy.setBurstMultiplier(BigDecimal.valueOf(request.effectiveBurstMultiplier()));
                    policy.setCreatedAt(nowBeijing());
                    policy.setUpdatedAt(nowBeijing());

                    // Save tenant and policy
                    return tenantRepository.save(tenant)
                            .flatMap(savedTenant ->
                                    quotaPolicyRepository.save(policy)
                                            .map(savedPolicy -> {
                                                QuotaPolicy quotaPolicy = QuotaPolicy.fromEntity(savedPolicy);
                                                log.info("Created tenant {} with plan={}, rpm={}",
                                                        tenantId, savedTenant.getPlan(), quotaPolicy.rpmLimit());
                                                return CreateTenantResponse.fromEntity(savedTenant, quotaPolicy);
                                            })
                            );
                }));
    }

    /**
     * Get tenant details with quota policy.
     */
    public Mono<CreateTenantResponse> getTenant(String tenantId) {
        return tenantRepository.findById(tenantId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Tenant not found: " + tenantId)))
                .flatMap(tenant ->
                        quotaPolicyRepository.findByTenantId(tenantId)
                                .map(QuotaPolicy::fromEntity)
                                .defaultIfEmpty(QuotaPolicy.defaultPolicy(defaultRpm))
                                .map(policy -> CreateTenantResponse.fromEntity(tenant, policy))
                );
    }

    // ==================== API Key Operations ====================

    /**
     * Create a new API key for a tenant.
     * Returns the plaintext key ONLY once.
     */
    @Transactional
    public Mono<CreateKeyResponse> createApiKey(String tenantId, CreateKeyRequest request) {
        // Verify tenant exists
        return tenantRepository.findById(tenantId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Tenant not found: " + tenantId)))
                .flatMap(tenant -> {
                    // Generate or use provided key
                    String plaintext;
                    String prefix;
                    String hash;

                    if (request.apiKey() != null && !request.apiKey().isBlank()) {
                        // Use custom API key (from external system sync)
                        plaintext = request.apiKey();
                        prefix = keyGeneratorService.extractPrefix(plaintext);
                        hash = keyGeneratorService.hashApiKey(plaintext);
                        log.info("Using custom API key for tenant {} (prefix={})", tenantId, prefix);
                    } else {
                        // Generate new key
                        KeyGeneratorService.GeneratedKey generated = keyGeneratorService.generateApiKey();
                        plaintext = generated.plaintext();
                        prefix = generated.prefix();
                        hash = generated.hash();
                        log.info("Generated new API key for tenant {} (prefix={})", tenantId, prefix);
                    }

                    // Create entity
                    ApiKeyEntity entity = new ApiKeyEntity();
                    entity.setKeyId(UUID.randomUUID().toString());
                    entity.setTenantId(tenantId);
                    entity.setUserId(request.userId());
                    entity.setKeyPrefix(prefix);
                    entity.setKeyHash(hash);
                    entity.setStatus("active");
                    entity.setScopes(request.effectiveScopes());
                    entity.setExpiresAt(request.expiresAt());
                    entity.setCreatedAt(nowBeijing());
                    entity.setUpdatedAt(nowBeijing());

                    // Save and return with plaintext key
                    return apiKeyRepository.save(entity)
                            .map(saved -> {
                                log.info("Created API key {} for tenant {} (prefix={})",
                                        saved.getKeyId(), tenantId, saved.getKeyPrefix());
                                return CreateKeyResponse.create(
                                        saved.getKeyId(),
                                        saved.getTenantId(),
                                        saved.getUserId(),
                                        saved.getKeyPrefix(),
                                        plaintext,  // Only time we return this!
                                        saved.getScopes(),
                                        saved.getExpiresAt(),
                                        saved.getCreatedAt()
                                );
                            });
                });
    }

    /**
     * List API keys for a tenant (without secrets).
     */
    public Flux<ApiKeyInfo> listTenantKeys(String tenantId) {
        return apiKeyRepository.findByTenantId(tenantId)
                .map(entity -> new ApiKeyInfo(
                        entity.getKeyId(),
                        entity.getTenantId(),
                        entity.getUserId(),
                        entity.getKeyPrefix(),
                        entity.getStatus(),
                        entity.getScopes(),
                        entity.getExpiresAt(),
                        entity.getCreatedAt()
                ));
    }

    /**
     * Revoke an API key and invalidate cache.
     */
    @Transactional
    public Mono<RevokeKeyResponse> revokeKey(String keyId) {
        return apiKeyRepository.findById(keyId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "API key not found: " + keyId)))
                .flatMap(entity -> {
                    String previousStatus = entity.getStatus();
                    String keyHash = entity.getKeyHash();

                    // Update status to revoked
                    return apiKeyRepository.revokeByKeyId(keyId)
                            .then(cacheService.invalidateApiKey(keyHash))
                            .then(Mono.fromCallable(() -> {
                                log.info("Revoked API key {} (tenant={}, prefix={})",
                                        keyId, entity.getTenantId(), entity.getKeyPrefix());
                                return RevokeKeyResponse.success(
                                        keyId,
                                        entity.getTenantId(),
                                        entity.getKeyPrefix(),
                                        previousStatus,
                                        true
                                );
                            }));
                });
    }

    /**
     * Rotate an API key: revoke old key and create new key atomically.
     * This is a common operation when users regenerate their API keys.
     */
    @Transactional
    public Mono<RotateKeyResponse> rotateApiKey(String tenantId, RotateKeyRequest request) {
        String userId = request.userId();
        String oldApiKey = request.oldApiKey();
        String newApiKey = request.newApiKey();

        // Verify tenant exists first
        return tenantRepository.findById(tenantId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Tenant not found: " + tenantId)))
                .then(Mono.defer(() -> {
                    // Hash the old API key to find it in database
                    String oldKeyHash = keyGeneratorService.hashApiKey(oldApiKey);

                    // Find the old key by hash
                    return apiKeyRepository.findByKeyHash(oldKeyHash)
                            .switchIfEmpty(Mono.error(new ResponseStatusException(
                                    HttpStatus.NOT_FOUND,
                                    "Old API key not found or already revoked")))
                            .flatMap(oldKeyEntity -> {
                                // Verify ownership: key must belong to the specified tenant and user
                                if (!oldKeyEntity.getTenantId().equals(tenantId)) {
                                    return Mono.error(new ResponseStatusException(
                                            HttpStatus.FORBIDDEN,
                                            "Old API key does not belong to tenant: " + tenantId));
                                }
                                if (!oldKeyEntity.getUserId().equals(userId)) {
                                    return Mono.error(new ResponseStatusException(
                                            HttpStatus.FORBIDDEN,
                                            "Old API key does not belong to user: " + userId));
                                }
                                if ("revoked".equals(oldKeyEntity.getStatus())) {
                                    return Mono.error(new ResponseStatusException(
                                            HttpStatus.BAD_REQUEST,
                                            "Old API key is already revoked"));
                                }

                                // Prepare new key
                                String newKeyPrefix = keyGeneratorService.extractPrefix(newApiKey);
                                String newKeyHash = keyGeneratorService.hashApiKey(newApiKey);

                                // Check if new key already exists
                                return apiKeyRepository.existsByKeyHash(newKeyHash)
                                        .flatMap(exists -> {
                                            if (exists) {
                                                return Mono.error(new ResponseStatusException(
                                                        HttpStatus.CONFLICT,
                                                        "New API key already exists"));
                                            }

                                            // Create new key entity
                                            ApiKeyEntity newKeyEntity = new ApiKeyEntity();
                                            newKeyEntity.setKeyId(UUID.randomUUID().toString());
                                            newKeyEntity.setTenantId(tenantId);
                                            newKeyEntity.setUserId(userId);
                                            newKeyEntity.setKeyPrefix(newKeyPrefix);
                                            newKeyEntity.setKeyHash(newKeyHash);
                                            newKeyEntity.setStatus("active");
                                            newKeyEntity.setScopes(request.effectiveScopes());
                                            newKeyEntity.setExpiresAt(request.expiresAt());
                                            newKeyEntity.setCreatedAt(nowBeijing());
                                            newKeyEntity.setUpdatedAt(nowBeijing());

                                            // Execute rotation atomically:
                                            // 1. Revoke old key
                                            // 2. Save new key
                                            // 3. Invalidate old key cache
                                            return apiKeyRepository.revokeByKeyId(oldKeyEntity.getKeyId())
                                                    .then(apiKeyRepository.save(newKeyEntity))
                                                    .flatMap(savedNewKey ->
                                                            cacheService.invalidateApiKey(oldKeyHash)
                                                                    .thenReturn(savedNewKey)
                                                    )
                                                    .map(savedNewKey -> {
                                                        log.info("Rotated API key for tenant {} user {}: old_key={} -> new_key={}",
                                                                tenantId, userId,
                                                                oldKeyEntity.getKeyPrefix(), savedNewKey.getKeyPrefix());

                                                        return RotateKeyResponse.success(
                                                                oldKeyEntity.getKeyId(),
                                                                oldKeyEntity.getKeyPrefix(),
                                                                savedNewKey.getKeyId(),
                                                                savedNewKey.getKeyPrefix(),
                                                                tenantId,
                                                                userId,
                                                                nowBeijing()
                                                        );
                                                    });
                                        });
                            });
                }));
    }

    // ==================== Quota Policy Operations ====================

    /**
     * Update tenant quota policy.
     */
    @Transactional
    public Mono<QuotaPolicy> updateQuotaPolicy(String tenantId, UpdatePolicyRequest request) {
        // Verify tenant exists
        return tenantRepository.findById(tenantId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Tenant not found: " + tenantId)))
                .then(quotaPolicyRepository.findByTenantId(tenantId))
                .flatMap(existing -> {
                    // Mark as existing entity (not new)
                    existing.setNew(false);

                    // Apply updates
                    if (request.rpmLimit() != null) {
                        existing.setRpmLimit(request.rpmLimit());
                    }
                    if (request.tpmLimit() != null) {
                        existing.setTpmLimit(request.tpmLimit());
                    }
                    if (request.monthlyTokenCap() != null) {
                        existing.setMonthlyTokenCap(request.monthlyTokenCap());
                    }
                    if (request.burstMultiplier() != null) {
                        existing.setBurstMultiplier(BigDecimal.valueOf(request.burstMultiplier()));
                    }
                    existing.setUpdatedAt(nowBeijing());

                    return quotaPolicyRepository.save(existing);
                })
                .switchIfEmpty(Mono.defer(() -> {
                    // Create new policy if none exists
                    QuotaPolicyEntity newPolicy = new QuotaPolicyEntity();
                    newPolicy.setTenantId(tenantId);
                    newPolicy.setRpmLimit(request.rpmLimit() != null ? request.rpmLimit() : defaultRpm);
                    newPolicy.setTpmLimit(request.tpmLimit());
                    newPolicy.setMonthlyTokenCap(request.monthlyTokenCap());
                    newPolicy.setBurstMultiplier(request.burstMultiplier() != null
                            ? BigDecimal.valueOf(request.burstMultiplier())
                            : BigDecimal.valueOf(1.5));
                    newPolicy.setCreatedAt(nowBeijing());
                    newPolicy.setUpdatedAt(nowBeijing());

                    return quotaPolicyRepository.save(newPolicy);
                }))
                .flatMap(saved -> {
                    QuotaPolicy policy = QuotaPolicy.fromEntity(saved);
                    // Invalidate cached policy
                    return cacheService.invalidateQuotaPolicy(tenantId)
                            .thenReturn(policy);
                })
                .doOnSuccess(policy -> log.info("Updated quota policy for tenant {}: rpm={}",
                        tenantId, policy.rpmLimit()));
    }

    /**
     * API key info for listing (without hash).
     */
    public record ApiKeyInfo(
            String keyId,
            String tenantId,
            String userId,
            String keyPrefix,
            String status,
            String scopes,
            Instant expiresAt,
            LocalDateTime createdAt
    ) {}
}
