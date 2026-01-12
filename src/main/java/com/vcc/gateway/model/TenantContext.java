package com.vcc.gateway.model;

/**
 * Immutable context object representing an authenticated tenant.
 * Used throughout the request lifecycle for authorization and rate limiting.
 */
public class TenantContext {

    private final String tenantId;
    private final String userId;
    private final String plan;
    private final String apiKey;      // May be null for DB-resolved tenants
    private final QuotaPolicy quotaPolicy;  // NEW: per-tenant rate limit policy

    /**
     * Legacy constructor for backward compatibility (YAML tenants).
     */
    public TenantContext(String tenantId, String userId, String plan, String apiKey) {
        this(tenantId, userId, plan, apiKey, null);
    }

    /**
     * Full constructor with quota policy.
     */
    public TenantContext(String tenantId, String userId, String plan, String apiKey, QuotaPolicy quotaPolicy) {
        this.tenantId = tenantId;
        this.userId = userId;
        this.plan = plan;
        this.apiKey = apiKey;
        this.quotaPolicy = quotaPolicy;
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

    public QuotaPolicy getQuotaPolicy() {
        return quotaPolicy;
    }

    /**
     * Check if this tenant has a custom quota policy.
     */
    public boolean hasQuotaPolicy() {
        return quotaPolicy != null;
    }

    /**
     * Get RPM limit, falling back to default if no policy.
     */
    public int getRpmLimit(int defaultRpm) {
        return quotaPolicy != null ? quotaPolicy.rpmLimit() : defaultRpm;
    }

    @Override
    public String toString() {
        return "TenantContext{" +
                "tenantId='" + tenantId + '\'' +
                ", userId='" + userId + '\'' +
                ", plan='" + plan + '\'' +
                ", hasPolicy=" + (quotaPolicy != null) +
                '}';
    }
}
