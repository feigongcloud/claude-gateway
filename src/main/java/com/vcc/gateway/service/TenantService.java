package com.vcc.gateway.service;

import com.vcc.gateway.config.GwProperties;
import com.vcc.gateway.crypto.KeyGeneratorService;
import com.vcc.gateway.entity.ApiKeyEntity;
import com.vcc.gateway.entity.TenantEntity;
import com.vcc.gateway.model.ApiKeyInfo;
import com.vcc.gateway.model.QuotaPolicy;
import com.vcc.gateway.model.TenantContext;
import com.vcc.gateway.repository.ApiKeyRepository;
import com.vcc.gateway.repository.QuotaPolicyRepository;
import com.vcc.gateway.repository.TenantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Tenant authentication and resolution service.
 * Supports both YAML fallback (backward compatible) and database lookup with caching.
 *
 * Authentication chain:
 * 1. YAML fallback (if enabled) - check in-memory map
 * 2. SHA-256 hash the API key
 * 3. Redis cache lookup
 * 4. MySQL fallback
 * 5. Cache result
 */
@Service
public class TenantService {
    private static final Logger log = LoggerFactory.getLogger(TenantService.class);

    // In-memory YAML tenants for backward compatibility
    private final Map<String, TenantContext> yamlTenants = new HashMap<>();

    // Configuration flags
    private final boolean useYamlFallback;
    private final boolean useDatabase;
    private final int defaultRpm;

    // Dependencies
    private final ApiKeyRepository apiKeyRepository;
    private final TenantRepository tenantRepository;
    private final QuotaPolicyRepository quotaPolicyRepository;
    private final CacheService cacheService;
    private final KeyGeneratorService keyGeneratorService;

    public TenantService(GwProperties properties,
                         ApiKeyRepository apiKeyRepository,
                         TenantRepository tenantRepository,
                         QuotaPolicyRepository quotaPolicyRepository,
                         CacheService cacheService,
                         KeyGeneratorService keyGeneratorService) {
        this.apiKeyRepository = apiKeyRepository;
        this.tenantRepository = tenantRepository;
        this.quotaPolicyRepository = quotaPolicyRepository;
        this.cacheService = cacheService;
        this.keyGeneratorService = keyGeneratorService;
        this.defaultRpm = properties.getDefaultRpm();

        // Load configuration
        GwProperties.AuthConfig authConfig = properties.getAuth();
        this.useYamlFallback = authConfig != null && authConfig.isUseYamlFallback();
        this.useDatabase = authConfig != null && authConfig.isUseDatabase();

        // Load YAML tenants (backward compatible)
        if (useYamlFallback) {
            for (GwProperties.TenantConfig tenant : properties.getTenants()) {
                QuotaPolicy defaultPolicy = QuotaPolicy.defaultPolicy(defaultRpm);
                yamlTenants.put(tenant.getApiKey(), new TenantContext(
                        tenant.getTenantId(),
                        tenant.getUserId(),
                        tenant.getPlan(),
                        tenant.getApiKey(),
                        defaultPolicy
                ));
            }
            log.info("Loaded {} YAML tenants (fallback enabled)", yamlTenants.size());
        } else {
            log.info("YAML tenant fallback disabled");
        }

        log.info("TenantService initialized: useYamlFallback={}, useDatabase={}",
                useYamlFallback, useDatabase);
    }

    /**
     * Reactive tenant resolution from HttpHeaders.
     */
    public Mono<TenantContext> resolveReactive(HttpHeaders headers) {
        return resolveReactive(headers.getFirst(HttpHeaders.AUTHORIZATION));
    }

    /**
     * Reactive tenant resolution.
     * Chain: YAML → SHA-256 → Redis → MySQL → Cache
     */
    public Mono<TenantContext> resolveReactive(String authorizationHeader) {
        // Validate header
        if (authorizationHeader == null || authorizationHeader.isBlank()) {
            return Mono.error(new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED, "Missing Authorization header"));
        }
        if (!authorizationHeader.startsWith("Bearer ")) {
            return Mono.error(new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED, "Invalid Authorization scheme"));
        }

        String apiKey = authorizationHeader.substring("Bearer ".length()).trim();
        if (apiKey.isEmpty()) {
            return Mono.error(new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED, "Missing API key"));
        }

        // Step 1: Check YAML fallback first (fast path)
        if (useYamlFallback) {
            TenantContext yamlTenant = yamlTenants.get(apiKey);
            if (yamlTenant != null) {
                log.debug("Resolved tenant from YAML: {}", yamlTenant.getTenantId());
                return Mono.just(yamlTenant);
            }
        }

        // Step 2: Database path
        if (!useDatabase) {
            return Mono.error(new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED, "Unknown API key"));
        }

        // Step 3: Hash the key and lookup
        String keyHash = keyGeneratorService.hashApiKey(apiKey);
        return lookupFromCacheOrDb(keyHash);
    }

    /**
     * Lookup tenant from cache, falling back to database.
     */
    private Mono<TenantContext> lookupFromCacheOrDb(String keyHash) {
        // Try cache first
        return cacheService.getApiKeyInfo(keyHash)
                .flatMap(this::buildTenantContextFromCache)
                .switchIfEmpty(Mono.defer(() -> lookupFromDbAndCache(keyHash)));
    }

    /**
     * Build TenantContext from cached ApiKeyInfo.
     */
    private Mono<TenantContext> buildTenantContextFromCache(ApiKeyInfo info) {
        if (!info.isValid()) {
            log.debug("Cached API key is invalid: {}", info.keyId());
            return Mono.empty(); // Fall through to DB to verify
        }
        return fetchQuotaPolicyAndBuild(info.tenantId(), info.userId());
    }

    /**
     * Lookup from database and cache the result.
     */
    private Mono<TenantContext> lookupFromDbAndCache(String keyHash) {
        return apiKeyRepository.findActiveByKeyHash(keyHash)
                .switchIfEmpty(Mono.error(new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "Unknown API key")))
                .filter(this::isKeyValid)
                .switchIfEmpty(Mono.error(new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "API key expired or revoked")))
                .flatMap(entity -> {
                    // Build ApiKeyInfo and cache it
                    ApiKeyInfo info = ApiKeyInfo.fromEntity(entity);
                    return cacheService.cacheApiKeyInfo(keyHash, info)
                            .then(fetchQuotaPolicyAndBuild(entity.getTenantId(), entity.getUserId()));
                });
    }

    /**
     * Validate API key entity.
     */
    private boolean isKeyValid(ApiKeyEntity entity) {
        if (!"active".equalsIgnoreCase(entity.getStatus())) {
            return false;
        }
        if (entity.getExpiresAt() != null && entity.getExpiresAt().isBefore(Instant.now())) {
            return false;
        }
        return true;
    }

    /**
     * Fetch tenant and quota policy, build TenantContext.
     */
    private Mono<TenantContext> fetchQuotaPolicyAndBuild(String tenantId, String userId) {
        // Get tenant entity
        Mono<TenantEntity> tenantMono = tenantRepository.findById(tenantId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "Tenant not found")));

        // Get quota policy (from cache or DB)
        Mono<QuotaPolicy> policyMono = getQuotaPolicy(tenantId);

        return Mono.zip(tenantMono, policyMono)
                .map(tuple -> {
                    TenantEntity tenant = tuple.getT1();
                    QuotaPolicy policy = tuple.getT2();
                    return new TenantContext(
                            tenant.getTenantId(),
                            userId,
                            tenant.getPlan(),
                            null,  // Don't store the API key in context
                            policy
                    );
                })
                .doOnNext(ctx -> log.debug("Resolved tenant from DB: {}", ctx.getTenantId()));
    }

    /**
     * Get quota policy from cache or database.
     */
    private Mono<QuotaPolicy> getQuotaPolicy(String tenantId) {
        return cacheService.getQuotaPolicy(tenantId)
                .switchIfEmpty(Mono.defer(() ->
                        quotaPolicyRepository.findByTenantId(tenantId)
                                .map(QuotaPolicy::fromEntity)
                                .flatMap(policy -> cacheService.cacheQuotaPolicy(tenantId, policy)
                                        .thenReturn(policy))
                                .switchIfEmpty(Mono.just(QuotaPolicy.defaultPolicy(defaultRpm)))
                ));
    }

    /**
     * Synchronous resolution (deprecated, for backward compatibility).
     * WARNING: This blocks the calling thread. Use resolveReactive() instead.
     */
    @Deprecated
    public TenantContext resolve(HttpHeaders headers) {
        return resolve(headers.getFirst(HttpHeaders.AUTHORIZATION));
    }

    /**
     * Synchronous resolution (deprecated, for backward compatibility).
     * WARNING: This blocks the calling thread. Use resolveReactive() instead.
     */
    @Deprecated
    public TenantContext resolve(String authorizationHeader) {
        // For YAML-only mode, we can resolve synchronously without blocking
        if (useYamlFallback && !useDatabase) {
            if (authorizationHeader == null || authorizationHeader.isBlank()) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing Authorization header");
            }
            if (!authorizationHeader.startsWith("Bearer ")) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid Authorization scheme");
            }
            String apiKey = authorizationHeader.substring("Bearer ".length()).trim();
            TenantContext tenant = yamlTenants.get(apiKey);
            if (tenant == null) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unknown API key");
            }
            return tenant;
        }

        // For database mode, we need to block (not recommended)
        log.warn("Synchronous resolve() called with database enabled - this will block!");
        return resolveReactive(authorizationHeader).block();
    }
}
