package com.vcc.gateway.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "gw")
@Validated
public class GwProperties {
  @NotBlank
  private String upstreamBaseUrl;

  @NotBlank
  private String anthropicVersion;

  @NotEmpty
  private List<String> upstreamApiKeys = new ArrayList<>();

  private int defaultRpm = 60;

  private List<TenantConfig> tenants = new ArrayList<>();

  public String getUpstreamBaseUrl() {
    return upstreamBaseUrl;
  }

  public void setUpstreamBaseUrl(String upstreamBaseUrl) {
    this.upstreamBaseUrl = upstreamBaseUrl;
  }

  public String getAnthropicVersion() {
    return anthropicVersion;
  }

  public void setAnthropicVersion(String anthropicVersion) {
    this.anthropicVersion = anthropicVersion;
  }

  public List<String> getUpstreamApiKeys() {
    return upstreamApiKeys;
  }

  public void setUpstreamApiKeys(List<String> upstreamApiKeys) {
    this.upstreamApiKeys = upstreamApiKeys;
  }

  public int getDefaultRpm() {
    return defaultRpm;
  }

  public void setDefaultRpm(int defaultRpm) {
    this.defaultRpm = defaultRpm;
  }

  public List<TenantConfig> getTenants() {
    return tenants;
  }

  public void setTenants(List<TenantConfig> tenants) {
    this.tenants = tenants;
  }

  public static class TenantConfig {
    @NotBlank
    private String apiKey;

    @NotBlank
    private String tenantId;

    @NotBlank
    private String userId;

    @NotBlank
    private String plan;

    public String getApiKey() {
      return apiKey;
    }

    public void setApiKey(String apiKey) {
      this.apiKey = apiKey;
    }

    public String getTenantId() {
      return tenantId;
    }

    public void setTenantId(String tenantId) {
      this.tenantId = tenantId;
    }

    public String getUserId() {
      return userId;
    }

    public void setUserId(String userId) {
      this.userId = userId;
    }

    public String getPlan() {
      return plan;
    }

    public void setPlan(String plan) {
      this.plan = plan;
    }
  }
}
