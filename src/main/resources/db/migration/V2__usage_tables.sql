-- V2__usage_tables.sql
-- Usage tracking tables for request logging and cost calculation

-- Request log: one row per API request
CREATE TABLE usage_request_log (
    request_id VARCHAR(64) NOT NULL PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    plan VARCHAR(32) NOT NULL,
    model VARCHAR(64) NOT NULL,
    stream BOOLEAN NOT NULL DEFAULT FALSE,
    status_code INT NOT NULL,
    duration_ms INT NOT NULL,
    upstream_key_id VARCHAR(64) NULL,
    error_code VARCHAR(32) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX idx_request_tenant_time (tenant_id, created_at),
    INDEX idx_request_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Usage event: token counts and cost per request (for successful requests)
CREATE TABLE usage_event (
    event_id BIGINT NOT NULL PRIMARY KEY AUTO_INCREMENT,
    request_id VARCHAR(64) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    model VARCHAR(64) NOT NULL,
    stream BOOLEAN NOT NULL DEFAULT FALSE,
    input_tokens INT NOT NULL DEFAULT 0,
    output_tokens INT NOT NULL DEFAULT 0,
    total_tokens INT NOT NULL DEFAULT 0,
    cache_creation_input_tokens INT NOT NULL DEFAULT 0,
    cache_read_input_tokens INT NOT NULL DEFAULT 0,
    pricing_version VARCHAR(16) NOT NULL DEFAULT 'v1',
    cost_usd DECIMAL(12,8) NOT NULL DEFAULT 0,
    raw_usage_json JSON NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX idx_event_request (request_id),
    INDEX idx_event_tenant_time (tenant_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Daily aggregated usage per tenant (for reporting)
CREATE TABLE tenant_daily_usage (
    id BIGINT NOT NULL PRIMARY KEY AUTO_INCREMENT,
    tenant_id VARCHAR(64) NOT NULL,
    day DATE NOT NULL,
    request_count INT NOT NULL DEFAULT 0,
    success_count INT NOT NULL DEFAULT 0,
    error_count INT NOT NULL DEFAULT 0,
    input_tokens BIGINT NOT NULL DEFAULT 0,
    output_tokens BIGINT NOT NULL DEFAULT 0,
    total_tokens BIGINT NOT NULL DEFAULT 0,
    cost_usd DECIMAL(14,8) NOT NULL DEFAULT 0,
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE INDEX uk_tenant_day (tenant_id, day),
    INDEX idx_daily_day (day)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
