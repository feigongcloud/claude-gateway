-- V1__init.sql
-- Claude Gateway V5 Database Schema

-- Tenant table
CREATE TABLE tenant (
    tenant_id VARCHAR(64) NOT NULL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    plan VARCHAR(32) NOT NULL DEFAULT 'basic',
    status VARCHAR(16) NOT NULL DEFAULT 'active',
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    INDEX idx_tenant_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- API Key table (SHA-256 hash only, no plaintext)
CREATE TABLE api_key (
    key_id VARCHAR(64) NOT NULL PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    key_prefix VARCHAR(12) NOT NULL,
    key_hash CHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'active',
    scopes VARCHAR(256) DEFAULT 'messages:write',
    expires_at TIMESTAMP(3) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE INDEX idx_key_hash (key_hash),
    INDEX idx_tenant_status (tenant_id, status),
    CONSTRAINT fk_api_key_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(tenant_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Quota Policy table (per-tenant rate limits)
CREATE TABLE quota_policy (
    tenant_id VARCHAR(64) NOT NULL PRIMARY KEY,
    rpm_limit INT NOT NULL DEFAULT 60,
    tpm_limit INT NULL,
    monthly_token_cap BIGINT NULL,
    burst_multiplier DECIMAL(3,2) NOT NULL DEFAULT 1.50,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_quota_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(tenant_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Upstream Key Secret table (AES-256-GCM encrypted)
CREATE TABLE upstream_key_secret (
    upstream_key_id VARCHAR(64) NOT NULL PRIMARY KEY,
    provider VARCHAR(32) NOT NULL DEFAULT 'anthropic',
    status VARCHAR(16) NOT NULL DEFAULT 'active',
    key_version INT NOT NULL DEFAULT 1,
    iv VARBINARY(12) NOT NULL,
    ciphertext BLOB NOT NULL,
    tag VARBINARY(16) NOT NULL,
    aad VARCHAR(128) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    INDEX idx_upstream_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Admin Audit Log table
CREATE TABLE admin_audit_log (
    audit_id BIGINT NOT NULL PRIMARY KEY AUTO_INCREMENT,
    actor VARCHAR(128) NOT NULL,
    action VARCHAR(64) NOT NULL,
    target_type VARCHAR(32) NOT NULL,
    target_id VARCHAR(64),
    detail_json JSON NULL,
    client_ip VARCHAR(45),
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX idx_audit_action_time (action, created_at),
    INDEX idx_audit_target (target_type, target_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
