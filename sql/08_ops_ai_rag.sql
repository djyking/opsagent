CREATE DATABASE IF NOT EXISTS ops_rag CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE ops_rag;

CREATE TABLE IF NOT EXISTS ai_usage_log (
    id BIGINT NOT NULL AUTO_INCREMENT,
    trace_id VARCHAR(64),
    user_id BIGINT NOT NULL,
    provider VARCHAR(32) NOT NULL,
    model VARCHAR(128) NOT NULL,
    question_hash CHAR(64) NOT NULL,
    input_tokens INT NOT NULL DEFAULT 0,
    output_tokens INT NOT NULL DEFAULT 0,
    latency_ms BIGINT NOT NULL,
    success TINYINT NOT NULL,
    error_code VARCHAR(64),
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_ai_usage_user_time (user_id, create_time),
    KEY idx_ai_usage_provider_time (provider, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

USE ops_knowledge;

SET @ddl = IF(
    EXISTS(SELECT 1 FROM information_schema.columns
           WHERE table_schema='ops_knowledge' AND table_name='knowledge_document'
             AND column_name='visibility'),
    'SELECT 1',
    'ALTER TABLE knowledge_document ADD COLUMN visibility VARCHAR(16) NOT NULL DEFAULT ''PUBLIC'' AFTER version');
PREPARE statement FROM @ddl;
EXECUTE statement;
DEALLOCATE PREPARE statement;

SET @ddl = IF(
    EXISTS(SELECT 1 FROM information_schema.columns
           WHERE table_schema='ops_knowledge' AND table_name='knowledge_chunk'
             AND column_name='embedding_model'),
    'SELECT 1',
    'ALTER TABLE knowledge_chunk ADD COLUMN embedding_model VARCHAR(128) AFTER embedding_status');
PREPARE statement FROM @ddl;
EXECUTE statement;
DEALLOCATE PREPARE statement;

SET @ddl = IF(
    EXISTS(SELECT 1 FROM information_schema.columns
           WHERE table_schema='ops_knowledge' AND table_name='knowledge_chunk'
             AND column_name='indexed_at'),
    'SELECT 1',
    'ALTER TABLE knowledge_chunk ADD COLUMN indexed_at DATETIME AFTER embedding_model');
PREPARE statement FROM @ddl;
EXECUTE statement;
DEALLOCATE PREPARE statement;

SET @ddl = IF(
    EXISTS(SELECT 1 FROM information_schema.statistics
           WHERE table_schema='ops_knowledge' AND table_name='knowledge_document'
             AND index_name='idx_document_visibility_owner'),
    'SELECT 1',
    'CREATE INDEX idx_document_visibility_owner ON knowledge_document (visibility, create_by, deleted)');
PREPARE statement FROM @ddl;
EXECUTE statement;
DEALLOCATE PREPARE statement;
