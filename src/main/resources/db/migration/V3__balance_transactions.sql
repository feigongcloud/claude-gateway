-- V3__balance_transactions.sql
-- Two-phase balance deduction system
-- Audit trail for all balance operations

CREATE TABLE balance_transaction (
    transaction_id BIGINT NOT NULL PRIMARY KEY AUTO_INCREMENT COMMENT 'Transaction ID',
    uid VARCHAR(20) NOT NULL COMMENT 'User unique ID (references users.uid)',
    request_id VARCHAR(64) NULL COMMENT 'Links to usage_request_log.request_id',
    transaction_type VARCHAR(16) NOT NULL COMMENT 'RESERVE, COMMIT, REFUND, ADJUST',
    amount DECIMAL(12,8) NOT NULL COMMENT 'Transaction amount (negative for deductions, positive for refunds)',
    balance_before DECIMAL(12,8) NOT NULL COMMENT 'Balance before transaction',
    balance_after DECIMAL(12,8) NOT NULL COMMENT 'Balance after transaction',
    estimated_cost DECIMAL(12,8) NULL COMMENT 'Estimated cost for RESERVE transactions',
    actual_cost DECIMAL(12,8) NULL COMMENT 'Actual cost for COMMIT transactions',
    model VARCHAR(64) NULL COMMENT 'Model used for the request',
    tokens_estimated INT NULL COMMENT 'Estimated tokens (for reserve)',
    tokens_actual INT NULL COMMENT 'Actual tokens used',
    status VARCHAR(16) NOT NULL DEFAULT 'pending' COMMENT 'pending, completed, failed, rolled_back',
    error_message VARCHAR(512) NULL COMMENT 'Error details if failed',
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'Transaction timestamp',

    INDEX idx_uid_created (uid, created_at),
    INDEX idx_request_id (request_id),
    INDEX idx_transaction_type (transaction_type),
    INDEX idx_status (status),
    INDEX idx_status_created (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
COMMENT='Audit trail for all balance operations in two-phase deduction system';
