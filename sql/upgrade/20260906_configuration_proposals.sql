-- Immutable configuration intent, using the existing Agent approval and publication history.
SET NAMES utf8mb4;
USE ops_platform;
CREATE TABLE IF NOT EXISTS operations_managed_config_proposal (
    proposal_id VARCHAR(36) PRIMARY KEY,
    request_id VARCHAR(36) NOT NULL UNIQUE,
    request_hash VARCHAR(64) NOT NULL,
    owner_id BIGINT NOT NULL,
    immutable_digest VARCHAR(64) NOT NULL,
    proposal_json TEXT NOT NULL,
    expires_at TIMESTAMP(3) NOT NULL,
    run_id VARCHAR(36) NULL,
    INDEX idx_config_proposal_owner (owner_id, expires_at)
);
