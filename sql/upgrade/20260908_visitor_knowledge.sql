USE ops_knowledge;
CREATE TABLE IF NOT EXISTS visitor_knowledge_space (
 user_id BIGINT NOT NULL PRIMARY KEY,
 base_id BIGINT NOT NULL UNIQUE,
 expires_at DATETIME(6) NOT NULL,
 revoked_at DATETIME(6) NULL,
 checked_at DATETIME(6) NULL,
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS visitor_knowledge_document (
 document_id BIGINT NOT NULL PRIMARY KEY,
 user_id BIGINT NOT NULL,
 stage VARCHAR(32) NOT NULL DEFAULT 'UPLOADED',
 requested_action VARCHAR(16) NULL,
 started_at DATETIME(6) NULL,
 processing_active TINYINT NOT NULL DEFAULT 0,
 deleted_at DATETIME(6) NULL,
 cleaned_at DATETIME(6) NULL,
 error_message VARCHAR(1000) NULL,
 embedding_tokens BIGINT NOT NULL DEFAULT 0,
 embedding_unknown_calls INT NOT NULL DEFAULT 0,
 embedding_calls INT NOT NULL DEFAULT 0,
 update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 KEY idx_visitor_knowledge_owner(user_id,deleted_at),
 KEY idx_visitor_knowledge_stage(stage,update_time)
);
CREATE TABLE IF NOT EXISTS visitor_knowledge_worker_lock (
 id INT NOT NULL PRIMARY KEY
);
INSERT IGNORE INTO visitor_knowledge_worker_lock(id) VALUES(1);
