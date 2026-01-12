package com.vcc.gateway.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vcc.gateway.config.GwProperties;
import com.vcc.gateway.model.ApiKeyInfo;
import com.vcc.gateway.model.QuotaPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Redis cache service for API key and quota policy lookups.
 * Provides reactive cache operations with configurable TTLs.
 */
@Service
public class CacheService {
    private static final Logger log = LoggerFactory.getLogger(CacheService.class);

    private final ReactiveStringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final String keyPrefix;
    private final Duration apiKeyTtl;
    private final Duration quotaPolicyTtl;

    public CacheService(ReactiveStringRedisTemplate redisTemplate,
                        ObjectMapper objectMapper,
                        GwProperties properties) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;

        GwProperties.CacheConfig cacheConfig = properties.getCache();
        if (cacheConfig != null) {
            this.keyPrefix = cacheConfig.getKeyPrefix() != null ? cacheConfig.getKeyPrefix() : "gw:";
            this.apiKeyTtl = Duration.ofSeconds(cacheConfig.getApiKeyTtlSeconds());
            this.quotaPolicyTtl = Duration.ofSeconds(cacheConfig.getQuotaPolicyTtlSeconds());
        } else {
            this.keyPrefix = "gw:";
            this.apiKeyTtl = Duration.ofMinutes(5);
            this.quotaPolicyTtl = Duration.ofMinutes(1);
        }
    }

    // ==================== API Key Cache ====================

    /**
     * Get API key info from cache.
     *
     * @param keyHash SHA-256 hash of the API key
     * @return ApiKeyInfo if found, empty Mono otherwise
     */
    public Mono<ApiKeyInfo> getApiKeyInfo(String keyHash) {
        String cacheKey = keyPrefix + "apikey:" + keyHash;
        return redisTemplate.opsForValue().get(cacheKey)
                .flatMap(json -> deserialize(json, ApiKeyInfo.class))
                .doOnNext(info -> log.debug("Cache hit for API key: {}", maskHash(keyHash)))
                .doOnSubscribe(s -> log.debug("Checking cache for API key: {}", maskHash(keyHash)));
    }

    /**
     * Cache API key info.
     *
     * @param keyHash SHA-256 hash of the API key
     * @param info    API key info to cache
     * @return true if cached successfully
     */
    public Mono<Boolean> cacheApiKeyInfo(String keyHash, ApiKeyInfo info) {
        String cacheKey = keyPrefix + "apikey:" + keyHash;
        return serialize(info)
                .flatMap(json -> redisTemplate.opsForValue().set(cacheKey, json, apiKeyTtl))
                .doOnSuccess(result -> log.debug("Cached API key info: {}", maskHash(keyHash)))
                .onErrorResume(e -> {
                    log.warn("Failed to cache API key info: {}", e.getMessage());
                    return Mono.just(false);
                });
    }

    /**
     * Invalidate API key cache entry.
     *
     * @param keyHash SHA-256 hash of the API key
     * @return number of keys deleted
     */
    public Mono<Long> invalidateApiKey(String keyHash) {
        String cacheKey = keyPrefix + "apikey:" + keyHash;
        return redisTemplate.delete(cacheKey)
                .doOnSuccess(count -> log.debug("Invalidated API key cache: {} (deleted={})",
                        maskHash(keyHash), count));
    }

    // ==================== Quota Policy Cache ====================

    /**
     * Get quota policy from cache.
     *
     * @param tenantId Tenant ID
     * @return QuotaPolicy if found, empty Mono otherwise
     */
    public Mono<QuotaPolicy> getQuotaPolicy(String tenantId) {
        String cacheKey = keyPrefix + "quota:" + tenantId;
        return redisTemplate.opsForValue().get(cacheKey)
                .flatMap(json -> deserialize(json, QuotaPolicy.class))
                .doOnNext(policy -> log.debug("Cache hit for quota policy: {}", tenantId));
    }

    /**
     * Cache quota policy.
     *
     * @param tenantId Tenant ID
     * @param policy   Quota policy to cache
     * @return true if cached successfully
     */
    public Mono<Boolean> cacheQuotaPolicy(String tenantId, QuotaPolicy policy) {
        String cacheKey = keyPrefix + "quota:" + tenantId;
        return serialize(policy)
                .flatMap(json -> redisTemplate.opsForValue().set(cacheKey, json, quotaPolicyTtl))
                .doOnSuccess(result -> log.debug("Cached quota policy: {}", tenantId))
                .onErrorResume(e -> {
                    log.warn("Failed to cache quota policy: {}", e.getMessage());
                    return Mono.just(false);
                });
    }

    /**
     * Invalidate quota policy cache entry.
     *
     * @param tenantId Tenant ID
     * @return number of keys deleted
     */
    public Mono<Long> invalidateQuotaPolicy(String tenantId) {
        String cacheKey = keyPrefix + "quota:" + tenantId;
        return redisTemplate.delete(cacheKey)
                .doOnSuccess(count -> log.debug("Invalidated quota policy cache: {} (deleted={})",
                        tenantId, count));
    }

    // ==================== Helper Methods ====================

    private <T> Mono<T> deserialize(String json, Class<T> clazz) {
        try {
            return Mono.just(objectMapper.readValue(json, clazz));
        } catch (JsonProcessingException e) {
            log.warn("Failed to deserialize cache value: {}", e.getMessage());
            return Mono.empty();
        }
    }

    private Mono<String> serialize(Object obj) {
        try {
            return Mono.just(objectMapper.writeValueAsString(obj));
        } catch (JsonProcessingException e) {
            return Mono.error(new RuntimeException("Failed to serialize for cache", e));
        }
    }

    private String maskHash(String hash) {
        if (hash == null || hash.length() < 16) {
            return "****";
        }
        return hash.substring(0, 8) + "..." + hash.substring(hash.length() - 4);
    }
}
