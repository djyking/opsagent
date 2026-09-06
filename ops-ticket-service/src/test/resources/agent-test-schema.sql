CREATE TABLE monitor_alert(
 id BIGINT AUTO_INCREMENT PRIMARY KEY,fingerprint VARCHAR(128) NOT NULL UNIQUE,ticket_id BIGINT,
 alert_name VARCHAR(128),severity VARCHAR(32),service_code VARCHAR(64),current_status VARCHAR(16),
 occurrence_count INT,first_seen_time TIMESTAMP,last_seen_time TIMESTAMP,resolved_time TIMESTAMP,
 labels_json CLOB,annotations_json CLOB);
CREATE TABLE monitor_alert_event(id BIGINT AUTO_INCREMENT PRIMARY KEY,alert_id BIGINT,event_status VARCHAR(16),payload_json CLOB,create_time TIMESTAMP);
CREATE TABLE IF NOT EXISTS monitor_alert_episode (
    episode_id VARCHAR(64) NOT NULL PRIMARY KEY,
    alert_id BIGINT NOT NULL,
    fingerprint VARCHAR(128) NOT NULL,
    starts_at DATETIME(6) NOT NULL,
    last_event_time DATETIME(6) NOT NULL,
    current_status VARCHAR(16) NOT NULL,
    ticket_id BIGINT NULL,
    incident_id VARCHAR(128) NULL,
    owner_actor_id BIGINT NOT NULL DEFAULT 0,
    environment VARCHAR(16) NOT NULL DEFAULT 'CORE',
    resolved_at DATETIME(6) NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_episode_alert_start(alert_id,starts_at),
    KEY idx_episode_ticket(ticket_id)
);

CREATE TABLE IF NOT EXISTS monitor_alert_delivery (
    delivery_key VARCHAR(64) PRIMARY KEY,
    episode_id VARCHAR(64) NOT NULL,
    event_status VARCHAR(16) NOT NULL,
    event_time DATETIME(6) NOT NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_delivery_episode(episode_id)
);

CREATE TABLE IF NOT EXISTS agent_ticket_effect (
    idempotency_key VARCHAR(128) PRIMARY KEY,
    ticket_id BIGINT NOT NULL,
    actor_id BIGINT NOT NULL,
    run_id VARCHAR(128) NOT NULL,
    tool_call_id VARCHAR(128) NOT NULL,
    operation VARCHAR(32) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    result_json CLOB NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_effect_ticket(ticket_id,create_time)
);
