SET NAMES utf8mb4;
USE ops_platform;
CREATE TABLE IF NOT EXISTS observability_ci_metadata (
    ci_code VARCHAR(64) PRIMARY KEY,
    metadata_json TEXT,
    deleted TINYINT NOT NULL DEFAULT 0,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
);
CREATE TABLE IF NOT EXISTS observability_relation_archive (
    relation_id BIGINT PRIMARY KEY,
    deleted TINYINT NOT NULL DEFAULT 1
);
CREATE TABLE IF NOT EXISTS observability_topology_layout (
    environment VARCHAR(32) PRIMARY KEY,
    positions_json MEDIUMTEXT NOT NULL,
    actor_id BIGINT NOT NULL,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
);
CREATE TABLE IF NOT EXISTS observability_inspection_result (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    ci_code VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    summary VARCHAR(500) NOT NULL,
    evidence_json TEXT NOT NULL,
    checked_at DATETIME(3) NOT NULL,
    duration_ms BIGINT NOT NULL,
    actor_id BIGINT NOT NULL,
    source VARCHAR(16) NOT NULL,
    KEY idx_observation_ci_time (ci_code,checked_at),
    KEY idx_observation_time (checked_at)
);
