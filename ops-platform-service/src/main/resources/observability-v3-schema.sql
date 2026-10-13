CREATE TABLE IF NOT EXISTS obs_v3_topology_snapshot (
  id VARCHAR(36) PRIMARY KEY,
  environment VARCHAR(32) NOT NULL,
  observed_minute BIGINT NOT NULL,
  window_start TIMESTAMP(3) NOT NULL,
  window_end TIMESTAMP(3) NOT NULL,
  generated_at TIMESTAMP(3) NOT NULL,
  graph_version VARCHAR(64) NOT NULL,
  data_quality VARCHAR(32) NOT NULL,
  payload_json LONGTEXT NOT NULL,
  UNIQUE (environment, observed_minute)
);
CREATE TABLE IF NOT EXISTS obs_v3_runtime_instance (
  environment VARCHAR(32) NOT NULL,
  ci_code VARCHAR(64) NOT NULL,
  instance_id VARCHAR(150) NOT NULL,
  first_seen_at TIMESTAMP(3) NOT NULL,
  last_seen_at TIMESTAMP(3) NOT NULL,
  metadata_json TEXT NOT NULL,
  PRIMARY KEY (environment, ci_code, instance_id)
);
CREATE TABLE IF NOT EXISTS obs_v3_difference_decision (
  id VARCHAR(36) PRIMARY KEY,
  environment VARCHAR(32) NOT NULL,
  decision VARCHAR(24) NOT NULL,
  note VARCHAR(500) NOT NULL,
  expires_at TIMESTAMP(3),
  actor_id BIGINT NOT NULL,
  updated_at TIMESTAMP(3) NOT NULL
);
CREATE TABLE IF NOT EXISTS obs_v3_evidence_bundle (
  id VARCHAR(36) PRIMARY KEY,
  actor_id BIGINT NOT NULL,
  ci_code VARCHAR(64) NOT NULL,
  environment VARCHAR(32) NOT NULL,
  ticket_id BIGINT,
  created_at TIMESTAMP(3) NOT NULL,
  payload_json LONGTEXT NOT NULL
);
