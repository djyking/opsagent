-- Explicit migration for optional authorized search ingress. Never removes or reassigns old routes.
CREATE TABLE IF NOT EXISTS ops_harness_route (
  request_key CHAR(64) PRIMARY KEY,
  id VARCHAR(36) NOT NULL UNIQUE,
  input_digest CHAR(64) NOT NULL,
  execution_owner VARCHAR(16) NOT NULL,
  expires_at BIGINT NOT NULL,
  dispatch_json TEXT NOT NULL
);
