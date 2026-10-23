-- Offline recovery for one reviewed request; all defaults are inert.
-- Before setting @all_rag_writers_stopped=1: close ingress, drain and stop EVERY RAG
-- process using this database. This prevents an old preparer from racing a new claim.
-- Save the metadata-only candidate output and reviewed reason in the operations record.
-- Keep the original request key: no command below changes ownership or idempotency.
SELECT id,input_digest,execution_owner,state,platform_run_id,created_epoch,
       context_json IS NULL AS context_absent,dispatch_json IS NULL AS dispatch_absent,
       prompt_sha256 IS NULL AS prompt_absent,answer_json IS NULL AS answer_absent
FROM ops_harness_answer
WHERE state='PREPARING' AND created_epoch < UNIX_TIMESTAMP() - 3600
ORDER BY created_epoch,id LIMIT 100;

SET @all_rag_writers_stopped = NULL;
SET @reviewed_id = NULL;
SET @reviewed_input_digest = NULL;
SET @reviewed_created_epoch = NULL;
SET @reviewed_action = NULL; -- HARNESS_REPREPARE or LEGACY_MARK_UNKNOWN

-- HARNESS prepares read-only domain context before this atomic durable dispatch boundary.
-- All payload fields must still be absent, proving this row never reached READY.
-- The caller may then restart RAG and submit exactly the original request and key.
UPDATE ops_harness_answer
SET state='NEW'
WHERE @all_rag_writers_stopped=1 AND @reviewed_action='HARNESS_REPREPARE'
  AND id=@reviewed_id AND input_digest=@reviewed_input_digest
  AND created_epoch=@reviewed_created_epoch AND created_epoch < UNIX_TIMESTAMP() - 3600
  AND execution_owner='HARNESS' AND state='PREPARING'
  AND context_json IS NULL AND dispatch_json IS NULL AND prompt_sha256 IS NULL
  AND platform_run_id IS NULL AND answer_json IS NULL AND completed_epoch IS NULL;
SELECT ROW_COUNT() AS harness_rows_recovered;

-- LEGACY may already have performed a provider call. Only make uncertainty explicit;
-- never reset to NEW, change executor, clear the key, or replay that call.
UPDATE ops_harness_answer
SET state='UNKNOWN'
WHERE @all_rag_writers_stopped=1 AND @reviewed_action='LEGACY_MARK_UNKNOWN'
  AND id=@reviewed_id AND input_digest=@reviewed_input_digest
  AND created_epoch=@reviewed_created_epoch AND created_epoch < UNIX_TIMESTAMP() - 3600
  AND execution_owner='LEGACY' AND state='PREPARING'
  AND platform_run_id IS NULL AND answer_json IS NULL AND completed_epoch IS NULL;
SELECT ROW_COUNT() AS legacy_rows_marked_unknown;

-- READY is deliberately excluded: retry the exact existing request/key through the API.
-- SUBMITTED, UNKNOWN, REJECTED and all terminal rows are never reset by this operation.
