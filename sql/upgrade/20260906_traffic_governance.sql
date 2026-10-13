SET NAMES utf8mb4;
USE ops_platform;

CREATE TABLE IF NOT EXISTS traffic_governance_change (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  request_id VARCHAR(36) NOT NULL,
  request_hash VARCHAR(64) NOT NULL,
  rule_type VARCHAR(20) NOT NULL,
  action VARCHAR(20) NOT NULL,
  status VARCHAR(24) NOT NULL,
  before_json TEXT NOT NULL,
  after_json TEXT NOT NULL,
  expected_revision VARCHAR(64) NOT NULL,
  revision VARCHAR(64) NOT NULL,
  comment VARCHAR(500) NOT NULL,
  actor_id BIGINT NOT NULL,
  actor_name VARCHAR(100) NOT NULL,
  rollback_version_id BIGINT NULL,
  message VARCHAR(500) NOT NULL,
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  finish_time TIMESTAMP NULL,
  UNIQUE KEY uk_traffic_request (request_id),
  KEY idx_traffic_type_time (rule_type, create_time)
);
