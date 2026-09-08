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
CREATE TABLE IF NOT EXISTS observability_personal_layout (
    environment VARCHAR(32) NOT NULL,
    actor_id BIGINT NOT NULL,
    positions_json MEDIUMTEXT NOT NULL,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (environment, actor_id)
);
CREATE TABLE IF NOT EXISTS observability_inspection_plan (
    plan_key VARCHAR(64) PRIMARY KEY,
    next_run_at DATETIME(3) NOT NULL,
    lease_owner VARCHAR(64),
    lease_until DATETIME(3),
    updated_at DATETIME(3) NOT NULL
);
CREATE TABLE IF NOT EXISTS observability_inspection_execution (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    check_id VARCHAR(128) NOT NULL,
    target_id VARCHAR(64) NOT NULL,
    environment VARCHAR(32) NOT NULL,
    scheduled_for DATETIME(3) NOT NULL,
    run_id VARCHAR(64) NOT NULL,
    executor VARCHAR(96) NOT NULL,
    started_at DATETIME(3),
    finished_at DATETIME(3),
    execution_status VARCHAR(16) NOT NULL,
    result VARCHAR(24) NOT NULL,
    reason_code VARCHAR(64) NOT NULL,
    summary VARCHAR(500) NOT NULL,
    evidence_json MEDIUMTEXT NOT NULL,
    next_run_at DATETIME(3),
    lease_until DATETIME(3),
    actor_id BIGINT NOT NULL,
    source VARCHAR(24) NOT NULL,
    workflow_run_id BIGINT,
    UNIQUE KEY uk_inspection_run_target (run_id,target_id,environment),
    KEY idx_inspection_target_time (target_id,scheduled_for),
    KEY idx_inspection_lease (execution_status,lease_until)
);
