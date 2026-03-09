-- V4__update_users_balance_precision.sql
-- Update users table balance precision to support micro-transactions

-- Modify balance column from DECIMAL(10,2) to DECIMAL(12,8)
-- This allows for precise tracking of small costs (e.g., $0.00261)
ALTER TABLE users MODIFY COLUMN balance DECIMAL(12,8) NOT NULL DEFAULT 0.00000000 COMMENT '账户余额（支持8位小数精度）';

-- Note: Existing balance values will be automatically converted
-- Example: 20.00 → 20.00000000
