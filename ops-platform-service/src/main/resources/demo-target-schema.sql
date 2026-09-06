CREATE TABLE IF NOT EXISTS operations_demo_incident (
    incident_id VARCHAR(36) PRIMARY KEY,
    target_code VARCHAR(64) NOT NULL,
    scenario_code VARCHAR(64) NOT NULL,
    owner_id BIGINT NOT NULL,
    owner_name VARCHAR(128) NOT NULL,
    owner_kind VARCHAR(16) NOT NULL,
    status VARCHAR(32) NOT NULL,
    expected_revision VARCHAR(64) NOT NULL DEFAULT '',
    started_at TIMESTAMP(3) NOT NULL,
    expires_at TIMESTAMP(3) NOT NULL,
    recovered_at TIMESTAMP(3) NULL,
    recovery_source VARCHAR(32) NOT NULL DEFAULT '',
    last_http_status INT NOT NULL DEFAULT 0,
    last_reason VARCHAR(64) NOT NULL DEFAULT '',
    last_observed_at TIMESTAMP(3) NULL
);
CREATE TABLE IF NOT EXISTS operations_demo_target_lease (
    target_code VARCHAR(64) PRIMARY KEY,
    active_incident VARCHAR(36) NULL,
    available_after TIMESTAMP(3) NULL
);
CREATE TABLE IF NOT EXISTS operations_demo_action (
    idempotency_key VARCHAR(160) PRIMARY KEY,
    incident_id VARCHAR(36) NOT NULL,
    action_code VARCHAR(40) NOT NULL,
    expected_revision VARCHAR(64) NOT NULL,
    actor_id BIGINT NOT NULL,
    run_id VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL,
    finished_at TIMESTAMP(3) NULL,
    result_json TEXT NULL
);
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
CREATE TABLE IF NOT EXISTS operations_demo_observation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    target_code VARCHAR(64) NOT NULL,
    incident_id VARCHAR(36) NOT NULL,
    observed_at TIMESTAMP(3) NOT NULL,
    http_status INT NOT NULL,
    reason_code VARCHAR(64) NOT NULL,
    recovery_source VARCHAR(32) NOT NULL,
    revision VARCHAR(64) NOT NULL,
    snapshot_json TEXT NOT NULL,
    INDEX idx_demo_observation_incident (incident_id, observed_at),
    INDEX idx_demo_observation_target (target_code, observed_at)
);
CREATE TABLE IF NOT EXISTS operations_demo_runtime_change (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    target_code VARCHAR(64) NOT NULL,
    revision VARCHAR(64) NOT NULL,
    occurred_at TIMESTAMP(3) NOT NULL,
    observed_at TIMESTAMP(3) NOT NULL,
    kind VARCHAR(40) NOT NULL,
    before_json TEXT NOT NULL,
    after_json TEXT NOT NULL,
    UNIQUE KEY uq_demo_runtime_revision (target_code, revision),
    INDEX idx_demo_runtime_time (target_code, occurred_at)
);
