package com.vcc.gateway.config;

import jakarta.validation.constraints.NotBlank;
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

    // Changed: No longer @NotEmpty since DB keys are now primary
    private List<String> upstreamApiKeys = new ArrayList<>();

    private int defaultRpm = 60;

    private List<TenantConfig> tenants = new ArrayList<>();

    // NEW: V5 configuration sections
    private AuthConfig auth = new AuthConfig();
    private CryptoConfig crypto = new CryptoConfig();
    private CacheConfig cache = new CacheConfig();
    private AdminConfig admin = new AdminConfig();

    // ==================== Existing Getters/Setters ====================

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

    // ==================== NEW: V5 Config Getters/Setters ====================

    public AuthConfig getAuth() {
        return auth;
    }

    public void setAuth(AuthConfig auth) {
        this.auth = auth;
    }

    public CryptoConfig getCrypto() {
        return crypto;
    }

    public void setCrypto(CryptoConfig crypto) {
        this.crypto = crypto;
    }

    public CacheConfig getCache() {
        return cache;
    }

    public void setCache(CacheConfig cache) {
        this.cache = cache;
    }

    public AdminConfig getAdmin() {
        return admin;
    }

    public void setAdmin(AdminConfig admin) {
        this.admin = admin;
    }

    // ==================== Nested Config Classes ====================

    /**
     * Existing tenant configuration from YAML.
     */
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

    /**
     * Authentication configuration.
     */
    public static class AuthConfig {
        // Enable YAML tenant fallback (default: true for backward compatibility)
        private boolean useYamlFallback = true;

        // Enable database lookup (default: false, enable in production)
        private boolean useDatabase = false;

        public boolean isUseYamlFallback() {
            return useYamlFallback;
        }

        public void setUseYamlFallback(boolean useYamlFallback) {
            this.useYamlFallback = useYamlFallback;
        }

        public boolean isUseDatabase() {
            return useDatabase;
        }

        public void setUseDatabase(boolean useDatabase) {
            this.useDatabase = useDatabase;
        }
    }

    /**
     * Crypto configuration for upstream key encryption.
     */
    public static class CryptoConfig {
        // Path to master key file (32 bytes for AES-256)
        private String masterKeyPath = "/etc/gw/master.key";

        // Current key version for new encryptions
        private int currentKeyVersion = 1;

        public String getMasterKeyPath() {
            return masterKeyPath;
        }

        public void setMasterKeyPath(String masterKeyPath) {
            this.masterKeyPath = masterKeyPath;
        }

        public int getCurrentKeyVersion() {
            return currentKeyVersion;
        }

        public void setCurrentKeyVersion(int currentKeyVersion) {
            this.currentKeyVersion = currentKeyVersion;
        }
    }

    /**
     * Cache configuration for Redis.
     */
    public static class CacheConfig {
        // Redis key prefix
        private String keyPrefix = "gw:";

        // API key cache TTL in seconds
        private int apiKeyTtlSeconds = 300;  // 5 minutes

        // Quota policy cache TTL in seconds
        private int quotaPolicyTtlSeconds = 60;  // 1 minute

        public String getKeyPrefix() {
            return keyPrefix;
        }

        public void setKeyPrefix(String keyPrefix) {
            this.keyPrefix = keyPrefix;
        }

        public int getApiKeyTtlSeconds() {
            return apiKeyTtlSeconds;
        }

        public void setApiKeyTtlSeconds(int apiKeyTtlSeconds) {
            this.apiKeyTtlSeconds = apiKeyTtlSeconds;
        }

        public int getQuotaPolicyTtlSeconds() {
            return quotaPolicyTtlSeconds;
        }

        public void setQuotaPolicyTtlSeconds(int quotaPolicyTtlSeconds) {
            this.quotaPolicyTtlSeconds = quotaPolicyTtlSeconds;
        }
    }

    /**
     * Admin API configuration.
     */
    public static class AdminConfig {
        // Header name for admin API key
        private String apiKeyHeader = "X-Admin-Api-Key";

        // List of valid admin API keys
        private List<String> adminApiKeys = new ArrayList<>();

        public String getApiKeyHeader() {
            return apiKeyHeader;
        }

        public void setApiKeyHeader(String apiKeyHeader) {
            this.apiKeyHeader = apiKeyHeader;
        }

        public List<String> getAdminApiKeys() {
            return adminApiKeys;
        }

        public void setAdminApiKeys(List<String> adminApiKeys) {
            this.adminApiKeys = adminApiKeys;
        }
    }
}
