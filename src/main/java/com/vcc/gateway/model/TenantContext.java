package com.vcc.gateway.model;

public class TenantContext {
  private final String tenantId;
  private final String userId;
  private final String plan;
  private final String apiKey;

  public TenantContext(String tenantId, String userId, String plan, String apiKey) {
    this.tenantId = tenantId;
    this.userId = userId;
    this.plan = plan;
    this.apiKey = apiKey;
  }

  public String getTenantId() {
    return tenantId;
  }

  public String getUserId() {
    return userId;
  }

  public String getPlan() {
    return plan;
  }

  public String getApiKey() {
    return apiKey;
  }
}
