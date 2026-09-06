-- 增量迁移：保留原数据，不执行初始化/模拟数据脚本。
USE ops_auth;
CREATE TABLE IF NOT EXISTS visitor_lease (
    user_id BIGINT NOT NULL PRIMARY KEY,
    username VARCHAR(64) NOT NULL,
    expires_at DATETIME(6) NOT NULL COMMENT 'UTC',
    revoked TINYINT NOT NULL DEFAULT 0,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_visitor_expiry(expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

USE ops_ticket;
SET @ddl=(SELECT IF(COUNT(*)=0,'ALTER TABLE ticket ADD COLUMN environment VARCHAR(16) NOT NULL DEFAULT ''CORE''','SELECT 1') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='ops_ticket' AND TABLE_NAME='ticket' AND COLUMN_NAME='environment');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
SET @ddl=(SELECT IF(COUNT(*)=0,'ALTER TABLE ticket ADD COLUMN owner_actor_id BIGINT NULL','SELECT 1') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='ops_ticket' AND TABLE_NAME='ticket' AND COLUMN_NAME='owner_actor_id');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
SET @ddl=(SELECT IF(COUNT(*)=0,'ALTER TABLE ticket ADD COLUMN incident_id VARCHAR(128) NULL','SELECT 1') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='ops_ticket' AND TABLE_NAME='ticket' AND COLUMN_NAME='incident_id');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
SET @ddl=(SELECT IF(COUNT(*)=0,'ALTER TABLE ticket ADD COLUMN episode_id VARCHAR(64) NULL','SELECT 1') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='ops_ticket' AND TABLE_NAME='ticket' AND COLUMN_NAME='episode_id');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
SET @ddl=(SELECT IF(COUNT(*)=0,'ALTER TABLE ticket ADD COLUMN public_demo TINYINT NOT NULL DEFAULT 0','SELECT 1') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='ops_ticket' AND TABLE_NAME='ticket' AND COLUMN_NAME='public_demo');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
UPDATE ticket SET owner_actor_id=creator_id,update_time=update_time WHERE owner_actor_id IS NULL;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS monitor_alert_delivery (
    delivery_key VARCHAR(64) PRIMARY KEY,
    episode_id VARCHAR(64) NOT NULL,
    event_status VARCHAR(16) NOT NULL,
    event_time DATETIME(6) NOT NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_delivery_episode(episode_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS agent_ticket_effect (
    idempotency_key VARCHAR(128) PRIMARY KEY,
    ticket_id BIGINT NOT NULL,
    actor_id BIGINT NOT NULL,
    run_id VARCHAR(128) NOT NULL,
    tool_call_id VARCHAR(128) NOT NULL,
    operation VARCHAR(32) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    result_json JSON NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_effect_ticket(ticket_id,create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
