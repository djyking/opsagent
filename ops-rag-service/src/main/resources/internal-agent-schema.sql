-- Explicit additive migration for the retained legacy model ledger.
-- Apply using deployment credentials before setting ops.rag.schema-auto-initialize=false.
CREATE TABLE IF NOT EXISTS rag_agent_model_capability (
  provider VARCHAR(20) NOT NULL, model VARCHAR(128) NOT NULL,
  profile_hash VARCHAR(64) NOT NULL, status VARCHAR(24) NOT NULL,
  verified_at VARCHAR(40) NOT NULL, PRIMARY KEY(provider,model,profile_hash)
);
CREATE TABLE IF NOT EXISTS rag_agent_model_turn (
  call_id VARCHAR(160) PRIMARY KEY, actor_id BIGINT NOT NULL,
  run_id VARCHAR(128) NOT NULL, request_hash VARCHAR(64) NOT NULL,
  status VARCHAR(24) NOT NULL, result_json LONGTEXT, error_code VARCHAR(80),
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS rag_agent_model_attempt (
  call_id VARCHAR(160) NOT NULL, attempt_no INT NOT NULL, provider VARCHAR(20) NOT NULL,
  status VARCHAR(24) NOT NULL, input_tokens BIGINT, output_tokens BIGINT, total_tokens BIGINT,
  reserved_tokens INT NOT NULL, error_code VARCHAR(80), PRIMARY KEY(call_id,attempt_no)
);
