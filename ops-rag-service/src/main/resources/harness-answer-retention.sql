-- Reviewed, one-record payload scrub. Never deletes ownership or idempotency tombstones.
-- First inspect this bounded candidate list. No payloads or credentials are included.
SELECT id,input_digest,execution_owner,state,platform_run_id,platform_status,created_epoch,completed_epoch
FROM ops_harness_answer
WHERE completed_epoch IS NOT NULL AND completed_epoch < UNIX_TIMESTAMP() - 30 * 86400
  AND (state='COMPLETED' OR (state='SUBMITTED' AND platform_status IN ('COMPLETED','FAILED','CANCELLED','EXPIRED')))
ORDER BY completed_epoch,id LIMIT 100;

-- The operator supplies exact reviewed values out of band. Null defaults perform no mutation.
SET @reviewed_id = NULL;
SET @reviewed_input_digest = NULL;
SET @reviewed_completed_epoch = NULL;
UPDATE ops_harness_answer
SET state='ARCHIVED',request_json='{}',context_json=NULL,dispatch_json=NULL,answer_json=NULL
WHERE id=@reviewed_id AND input_digest=@reviewed_input_digest
  AND completed_epoch=@reviewed_completed_epoch
  AND completed_epoch < UNIX_TIMESTAMP() - 30 * 86400
  AND (state='COMPLETED' OR (state='SUBMITTED' AND platform_status IN ('COMPLETED','FAILED','CANCELLED','EXPIRED')));
