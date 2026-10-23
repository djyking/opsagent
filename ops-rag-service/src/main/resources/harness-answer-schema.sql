-- Explicit additive migration. Apply once with deployment credentials before enabling the bridge.
-- Rows are ownership/idempotency tombstones: never delete them to retry an unknown invocation.
CREATE TABLE IF NOT EXISTS ops_harness_answer (
  id VARCHAR(36) PRIMARY KEY,
  request_key CHAR(64) NOT NULL UNIQUE,
  input_digest CHAR(64) NOT NULL,
  application_id VARCHAR(64) NOT NULL,
  project_id VARCHAR(64) NOT NULL,
  user_id BIGINT NOT NULL,
  execution_owner VARCHAR(16) NOT NULL,
  state VARCHAR(32) NOT NULL,
  request_json TEXT NOT NULL,
  context_json MEDIUMTEXT,
  prompt_sha256 CHAR(64),
  dispatch_json MEDIUMTEXT,
  platform_run_id VARCHAR(36) UNIQUE,
  answer_json MEDIUMTEXT,
  created_epoch BIGINT NOT NULL,
  completed_epoch BIGINT,
  platform_status VARCHAR(32)
);
CREATE INDEX idx_harness_answer_owner ON ops_harness_answer(application_id,project_id,user_id,created_epoch,id);
