-- Explicit migration for the optional Harness bridge. No user or credential seed data.
CREATE TABLE IF NOT EXISTS ops_harness_application (
  application_id VARCHAR(64) PRIMARY KEY,
  credential_hash CHAR(64) NOT NULL UNIQUE,
  enabled INT NOT NULL DEFAULT 0,
  revision BIGINT NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS ops_harness_grant (
  application_id VARCHAR(64) NOT NULL,
  project_id VARCHAR(64) NOT NULL,
  user_id BIGINT NOT NULL,
  required_role VARCHAR(64) NOT NULL,
  permissions_json TEXT NOT NULL,
  enabled INT NOT NULL DEFAULT 0,
  revision BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (application_id, project_id, user_id)
);
CREATE TABLE IF NOT EXISTS ops_harness_delegation (
  id VARCHAR(36) PRIMARY KEY,
  application_id VARCHAR(64) NOT NULL,
  project_id VARCHAR(64) NOT NULL,
  user_id BIGINT NOT NULL,
  run_id VARCHAR(128) NOT NULL,
  roles_json TEXT NOT NULL,
  permissions_json TEXT NOT NULL,
  expires_at BIGINT NOT NULL,
  revoked INT NOT NULL DEFAULT 0,
  UNIQUE (application_id, run_id)
);
