package com.vcc.gateway.service;

import com.vcc.gateway.config.GwProperties;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class KeyPool {
  private static final Logger log = LoggerFactory.getLogger(KeyPool.class);

  private final List<String> keys;
  private final AtomicInteger index = new AtomicInteger(0);

  public KeyPool(GwProperties properties) {
    this.keys = List.copyOf(properties.getUpstreamApiKeys());
    if (keys.isEmpty()) {
      throw new IllegalStateException("gw.upstreamApiKeys must not be empty");
    }
    // Debug: log masked key info on startup
    for (int i = 0; i < keys.size(); i++) {
      String key = keys.get(i);
      String masked = key.length() > 10 ? key.substring(0, 10) + "..." : "TOO_SHORT";
      log.info("Loaded API key [{}]: {} (length={})", i, masked, key.length());
    }
  }

  public String nextKey() {
    int next = Math.floorMod(index.getAndIncrement(), keys.size());
    return keys.get(next);
  }
}
