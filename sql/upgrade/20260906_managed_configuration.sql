-- Incremental controlled Nacos configuration history and short target guard.
SET NAMES utf8mb4;
USE ops_platform;
CREATE TABLE IF NOT EXISTS operations_managed_config_guard (
    target_code VARCHAR(64) PRIMARY KEY,
    request_id VARCHAR(36) NOT NULL,
    expires_at TIMESTAMP(3) NOT NULL
);
CREATE TABLE IF NOT EXISTS operations_managed_config_change (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    configuration_id VARCHAR(64) NOT NULL,
    request_id VARCHAR(36) NOT NULL UNIQUE,
    request_hash VARCHAR(64) NOT NULL,
    action VARCHAR(16) NOT NULL,
    status VARCHAR(32) NOT NULL,
    expected_revision VARCHAR(64) NOT NULL,
    result_revision VARCHAR(64) NOT NULL DEFAULT '',
    content_json TEXT NOT NULL,
    previous_json TEXT NOT NULL,
    actor_id BIGINT NOT NULL,
    actor_name VARCHAR(128) NOT NULL,
    comment VARCHAR(500) NOT NULL,
    rollback_version_id BIGINT NULL,
    created_at TIMESTAMP(3) NOT NULL,
    finished_at TIMESTAMP(3) NULL,
    message VARCHAR(300) NOT NULL DEFAULT ''
);
