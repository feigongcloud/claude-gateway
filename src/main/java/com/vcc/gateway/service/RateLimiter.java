package com.vcc.gateway.service;

import com.vcc.gateway.config.GwProperties;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

@Service
public class RateLimiter {
  private final Map<String, TokenBucket> buckets = new ConcurrentHashMap<>();
  private final int defaultRpm;

  public RateLimiter(GwProperties properties) {
    this.defaultRpm = properties.getDefaultRpm();
  }

  public boolean tryConsume(String tenantId) {
    TokenBucket bucket = buckets.computeIfAbsent(tenantId, id -> new TokenBucket(defaultRpm));
    return bucket.tryConsume();
  }

  private static final class TokenBucket {
    private final int capacity;
    private double tokens;
    private long lastRefillNanos;

    private TokenBucket(int capacity) {
      this.capacity = Math.max(1, capacity);
      this.tokens = this.capacity;
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
      double tokensPerSecond = capacity / 60.0d;
      double add = (elapsedNanos / 1_000_000_000.0d) * tokensPerSecond;
      if (add > 0) {
        tokens = Math.min(capacity, tokens + add);
        lastRefillNanos = now;
      }
    }
  }
}
