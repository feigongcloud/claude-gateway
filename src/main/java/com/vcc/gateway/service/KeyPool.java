package com.vcc.gateway.service;

import com.vcc.gateway.config.GwProperties;
import com.vcc.gateway.crypto.AesGcmCryptoService;
import com.vcc.gateway.entity.UpstreamKeySecretEntity;
import com.vcc.gateway.repository.UpstreamKeySecretRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Pool of upstream API keys with round-robin selection.
 * Supports both YAML configuration (backward compatible) and encrypted database keys.
 *
 * Key loading priority:
 * 1. Database (if auth.useDatabase=true): Decrypt keys from upstream_key_secret table
 * 2. YAML fallback (if auth.useYamlFallback=true): Use gw.upstreamApiKeys list
 */
@Service
public class KeyPool {
    private static final Logger log = LoggerFactory.getLogger(KeyPool.class);

    private final GwProperties properties;
    private final UpstreamKeySecretRepository keySecretRepository;
    private final AesGcmCryptoService cryptoService;

    // Thread-safe key storage
    private final AtomicReference<List<String>> keysRef = new AtomicReference<>(List.of());
    private final AtomicInteger index = new AtomicInteger(0);

    // Configuration flags
    private final boolean useDatabase;
    private final boolean useYamlFallback;

    public KeyPool(GwProperties properties,
                   UpstreamKeySecretRepository keySecretRepository,
                   AesGcmCryptoService cryptoService) {
        this.properties = properties;
        this.keySecretRepository = keySecretRepository;
        this.cryptoService = cryptoService;

        GwProperties.AuthConfig authConfig = properties.getAuth();
        this.useDatabase = authConfig != null && authConfig.isUseDatabase();
        this.useYamlFallback = authConfig != null && authConfig.isUseYamlFallback();

        log.info("KeyPool initialized: useDatabase={}, useYamlFallback={}",
                useDatabase, useYamlFallback);
    }

    @PostConstruct
    public void init() {
        // Load keys synchronously on startup
        loadKeys()
                .doOnSuccess(count -> log.info("KeyPool loaded {} upstream keys", count))
                .doOnError(e -> log.error("Failed to load upstream keys: {}", e.getMessage()))
                .block();

        // Verify we have at least one key
        if (keysRef.get().isEmpty()) {
            throw new IllegalStateException(
                    "No upstream API keys available. Configure gw.upstreamApiKeys or add keys to database.");
        }
    }

    /**
     * Load keys from database and/or YAML configuration.
     *
     * @return Number of keys loaded
     */
    public Mono<Integer> loadKeys() {
        List<String> loadedKeys = new ArrayList<>();

        // Step 1: Try loading from database
        Mono<Void> dbLoad = Mono.empty();
        if (useDatabase && cryptoService.isEnabled()) {
            dbLoad = keySecretRepository.findAllActive()
                    .flatMap(this::decryptKey)
                    .doOnNext(loadedKeys::add)
                    .doOnComplete(() -> log.debug("Loaded {} keys from database", loadedKeys.size()))
                    .then();
        }

        // Step 2: Add YAML keys if configured
        return dbLoad.then(Mono.fromCallable(() -> {
            if (useYamlFallback) {
                List<String> yamlKeys = properties.getUpstreamApiKeys();
                if (yamlKeys != null && !yamlKeys.isEmpty()) {
                    for (String key : yamlKeys) {
                        if (key != null && !key.isBlank() && !loadedKeys.contains(key)) {
                            loadedKeys.add(key);
                        }
                    }
                    log.debug("Added {} keys from YAML (total={})",
                            yamlKeys.size(), loadedKeys.size());
                }
            }

            // Update the key pool
            if (!loadedKeys.isEmpty()) {
                keysRef.set(List.copyOf(loadedKeys));
                logKeysSummary(loadedKeys);
            }

            return loadedKeys.size();
        }));
    }

    /**
     * Decrypt an upstream key entity.
     */
    private Mono<String> decryptKey(UpstreamKeySecretEntity entity) {
        return Mono.fromCallable(() -> {
            try {
                String plaintext = cryptoService.decrypt(entity);
                log.debug("Decrypted key {} (provider={})",
                        entity.getUpstreamKeyId(), entity.getProvider());
                return plaintext;
            } catch (Exception e) {
                log.error("Failed to decrypt key {}: {}",
                        entity.getUpstreamKeyId(), e.getMessage());
                throw new RuntimeException("Decryption failed for key: " + entity.getUpstreamKeyId(), e);
            }
        });
    }

    /**
     * Log summary of loaded keys (masked for security).
     */
    private void logKeysSummary(List<String> keys) {
        for (int i = 0; i < keys.size(); i++) {
            String key = keys.get(i);
            String masked = maskKey(key);
            log.info("Loaded upstream key [{}]: {} (length={})", i, masked, key.length());
        }
    }

    /**
     * Mask key for logging (show first 10 chars only).
     */
    private String maskKey(String key) {
        if (key == null || key.length() <= 10) {
            return "***";
        }
        return key.substring(0, 10) + "...";
    }

    /**
     * Get next key using round-robin selection.
     *
     * @return Upstream API key for Anthropic
     */
    public String nextKey() {
        List<String> keys = keysRef.get();
        if (keys.isEmpty()) {
            throw new IllegalStateException("No upstream API keys available");
        }
        int next = Math.floorMod(index.getAndIncrement(), keys.size());
        return keys.get(next);
    }

    /**
     * Get total number of available keys.
     */
    public int getKeyCount() {
        return keysRef.get().size();
    }

    /**
     * Refresh keys from database (hot reload without restart).
     *
     * @return Number of keys after refresh
     */
    public Mono<Integer> refresh() {
        log.info("Refreshing upstream key pool...");
        return loadKeys()
                .doOnSuccess(count -> log.info("Key pool refreshed: {} keys available", count));
    }

    /**
     * Check if database key loading is enabled.
     */
    public boolean isDatabaseEnabled() {
        return useDatabase && cryptoService.isEnabled();
    }
}
