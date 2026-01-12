package com.vcc.gateway.service;

import com.vcc.gateway.config.GwProperties;
import com.vcc.gateway.model.QuotaPolicy;
import com.vcc.gateway.model.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-tenant rate limiter using token bucket algorithm.
 * Supports dynamic rate limits from QuotaPolicy with burst capacity.
 */
@Service
public class RateLimiter {
    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    private final Map<String, TokenBucket> buckets = new ConcurrentHashMap<>();
    private final int defaultRpm;

    public RateLimiter(GwProperties properties) {
        this.defaultRpm = properties.getDefaultRpm();
        log.info("RateLimiter initialized with defaultRpm={}", defaultRpm);
    }

    /**
     * Try to consume a token for the given tenant.
     * Uses per-tenant QuotaPolicy from TenantContext.
     *
     * @param tenant The authenticated tenant context
     * @return true if request is allowed, false if rate limited
     */
    public boolean tryConsume(TenantContext tenant) {
        if (tenant == null) {
            log.warn("Null tenant context, using default rate limit");
            return tryConsumeWithPolicy(null, QuotaPolicy.defaultPolicy(defaultRpm));
        }
        return tryConsumeWithPolicy(tenant.getTenantId(), tenant.getQuotaPolicy());
    }

    /**
     * Try to consume a token for a tenant with a specific policy.
     *
     * @param tenantId Tenant identifier
     * @param policy   Quota policy (may be null, defaults will be used)
     * @return true if request is allowed, false if rate limited
     */
    public boolean tryConsumeWithPolicy(String tenantId, QuotaPolicy policy) {
        if (tenantId == null) {
            tenantId = "_anonymous_";
        }

        // Use default policy if none provided
        QuotaPolicy effectivePolicy = policy != null ? policy : QuotaPolicy.defaultPolicy(defaultRpm);
        int rpmLimit = effectivePolicy.rpmLimit();
        int burstCapacity = effectivePolicy.getBurstCapacity();

        TokenBucket bucket = buckets.compute(tenantId, (id, existing) -> {
            if (existing == null) {
                log.debug("Creating new bucket for tenant {} with rpm={}, burst={}",
                        id, rpmLimit, burstCapacity);
                return new TokenBucket(rpmLimit, burstCapacity);
            }
            // Update capacity if policy changed
            if (existing.getRpmLimit() != rpmLimit || existing.getBurstCapacity() != burstCapacity) {
                log.info("Updating bucket for tenant {} from rpm={} to rpm={}, burst={}",
                        id, existing.getRpmLimit(), rpmLimit, burstCapacity);
                existing.updateCapacity(rpmLimit, burstCapacity);
            }
            return existing;
        });

        boolean allowed = bucket.tryConsume();
        if (!allowed) {
            log.debug("Rate limit exceeded for tenant {} (rpm={}, tokens={})",
                    tenantId, rpmLimit, bucket.getAvailableTokens());
        }
        return allowed;
    }

    /**
     * Legacy method for backward compatibility.
     * Uses default RPM for all tenants.
     *
     * @deprecated Use tryConsume(TenantContext) instead
     */
    @Deprecated
    public boolean tryConsume(String tenantId) {
        return tryConsumeWithPolicy(tenantId, QuotaPolicy.defaultPolicy(defaultRpm));
    }

    /**
     * Get remaining tokens for a tenant (for diagnostics).
     */
    public double getRemainingTokens(String tenantId) {
        TokenBucket bucket = buckets.get(tenantId);
        return bucket != null ? bucket.getAvailableTokens() : defaultRpm;
    }

    /**
     * Clear bucket for a tenant (e.g., when tenant is deleted or policy reset).
     */
    public void clearBucket(String tenantId) {
        TokenBucket removed = buckets.remove(tenantId);
        if (removed != null) {
            log.debug("Cleared rate limit bucket for tenant {}", tenantId);
        }
    }

    /**
     * Token bucket implementation with dynamic capacity updates.
     */
    private static final class TokenBucket {
        private volatile int rpmLimit;
        private volatile int burstCapacity;
        private volatile double tokens;
        private volatile long lastRefillNanos;

        private TokenBucket(int rpmLimit, int burstCapacity) {
            this.rpmLimit = Math.max(1, rpmLimit);
            this.burstCapacity = Math.max(1, burstCapacity);
            this.tokens = this.burstCapacity; // Start with burst capacity
            this.lastRefillNanos = System.nanoTime();
        }

        private synchronized boolean tryConsume() {
            refill();
            if (tokens >= 1.0d) {
                tokens -= 1.0d;
                return true;
            }
            return false;
        }

        private void refill() {
            long now = System.nanoTime();
            long elapsedNanos = now - lastRefillNanos;
            if (elapsedNanos <= 0) {
                return;
            }
            // Refill rate based on RPM (tokens per second = rpm / 60)
            double tokensPerSecond = rpmLimit / 60.0d;
            double add = (elapsedNanos / 1_000_000_000.0d) * tokensPerSecond;
            if (add > 0) {
                // Cap at burst capacity
                tokens = Math.min(burstCapacity, tokens + add);
                lastRefillNanos = now;
            }
        }

        /**
         * Update bucket capacity when policy changes.
         * Preserves current token count but caps at new burst capacity.
         */
        private synchronized void updateCapacity(int newRpmLimit, int newBurstCapacity) {
            this.rpmLimit = Math.max(1, newRpmLimit);
            this.burstCapacity = Math.max(1, newBurstCapacity);
            // Cap tokens at new burst capacity
            if (tokens > burstCapacity) {
                tokens = burstCapacity;
            }
        }

        private int getRpmLimit() {
            return rpmLimit;
        }

        private int getBurstCapacity() {
            return burstCapacity;
        }

        private double getAvailableTokens() {
            return tokens;
        }
    }
}
