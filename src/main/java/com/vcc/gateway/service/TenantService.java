package com.vcc.gateway.service;

import com.vcc.gateway.config.GwProperties;
import com.vcc.gateway.model.TenantContext;
import java.util.HashMap;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TenantService {
  private final Map<String, TenantContext> tenantsByKey = new HashMap<>();

  public TenantService(GwProperties properties) {
    for (GwProperties.TenantConfig tenant : properties.getTenants()) {
      tenantsByKey.put(
          tenant.getApiKey(),
          new TenantContext(tenant.getTenantId(), tenant.getUserId(), tenant.getPlan(), tenant.getApiKey()));
    }
  }

  public TenantContext resolve(String authorizationHeader) {
    if (authorizationHeader == null || authorizationHeader.isBlank()) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing Authorization header");
    }
    if (!authorizationHeader.startsWith("Bearer ")) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid Authorization scheme");
    }
    String apiKey = authorizationHeader.substring("Bearer ".length()).trim();
    if (apiKey.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing API key");
    }
    TenantContext tenant = tenantsByKey.get(apiKey);
    if (tenant == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unknown API key");
    }
    return tenant;
  }

  public TenantContext resolve(HttpHeaders headers) {
    return resolve(headers.getFirst(HttpHeaders.AUTHORIZATION));
  }
}
