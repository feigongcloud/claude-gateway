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
import java.util.UUID;

/**
 * Service for managing tenants and API keys.
 * Provides Admin API operations with cache invalidation.
 */
@Service
public class ApiKeyService {
    private static final Logger log = LoggerFactory.getLogger(ApiKeyService.class);

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
                    tenant.setCreatedAt(Instant.now());
                    tenant.setUpdatedAt(Instant.now());

                    // Create quota policy entity
                    QuotaPolicyEntity policy = new QuotaPolicyEntity();
                    policy.setTenantId(tenantId);
                    policy.setRpmLimit(request.effectiveRpmLimit(defaultRpm));
                    policy.setTpmLimit(request.tpmLimit());
                    policy.setMonthlyTokenCap(request.monthlyTokenCap());
                    policy.setBurstMultiplier(BigDecimal.valueOf(request.effectiveBurstMultiplier()));
                    policy.setCreatedAt(Instant.now());
                    policy.setUpdatedAt(Instant.now());

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
                    // Generate new key
                    KeyGeneratorService.GeneratedKey generated = keyGeneratorService.generateApiKey();

                    // Create entity
                    ApiKeyEntity entity = new ApiKeyEntity();
                    entity.setKeyId(UUID.randomUUID().toString());
                    entity.setTenantId(tenantId);
                    entity.setUserId(request.userId());
                    entity.setKeyPrefix(generated.prefix());
                    entity.setKeyHash(generated.hash());
                    entity.setStatus("active");
                    entity.setScopes(request.effectiveScopes());
                    entity.setExpiresAt(request.expiresAt());
                    entity.setCreatedAt(Instant.now());
                    entity.setUpdatedAt(Instant.now());

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
                                        generated.plaintext(),  // Only time we return this!
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
                    existing.setUpdatedAt(Instant.now());

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
                    newPolicy.setCreatedAt(Instant.now());
                    newPolicy.setUpdatedAt(Instant.now());

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
            Instant createdAt
    ) {}
}
