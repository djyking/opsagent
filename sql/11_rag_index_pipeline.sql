USE ops_knowledge;

SET @ddl = IF(
    EXISTS(
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'knowledge_document'
          AND COLUMN_NAME = 'index_status'
    ),
    'SELECT 1',
    'ALTER TABLE knowledge_document ADD COLUMN index_status VARCHAR(32) NOT NULL DEFAULT ''PENDING'' AFTER review_status'
);
PREPARE statement FROM @ddl;
EXECUTE statement;
DEALLOCATE PREPARE statement;

SET @ddl = IF(
    EXISTS(
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'knowledge_index_task'
          AND COLUMN_NAME = 'document_version'
    ),
    'SELECT 1',
    'ALTER TABLE knowledge_index_task ADD COLUMN document_version INT NOT NULL DEFAULT 1 AFTER document_id'
);
PREPARE statement FROM @ddl;
EXECUTE statement;
DEALLOCATE PREPARE statement;

SET @ddl = IF(
    EXISTS(
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'knowledge_index_task'
          AND COLUMN_NAME = 'embedding_model'
    ),
    'SELECT 1',
    'ALTER TABLE knowledge_index_task ADD COLUMN embedding_model VARCHAR(128) AFTER status'
);
PREPARE statement FROM @ddl;
EXECUTE statement;
DEALLOCATE PREPARE statement;

SET @ddl = IF(
    EXISTS(
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'knowledge_index_task'
          AND COLUMN_NAME = 'index_version'
    ),
    'SELECT 1',
    'ALTER TABLE knowledge_index_task ADD COLUMN index_version VARCHAR(128) AFTER embedding_model'
);
PREPARE statement FROM @ddl;
EXECUTE statement;
DEALLOCATE PREPARE statement;

CREATE TABLE IF NOT EXISTS knowledge_event_outbox (
    id BIGINT NOT NULL AUTO_INCREMENT,
    event_id CHAR(36) NOT NULL,
    event_type VARCHAR(128) NOT NULL,
    payload JSON NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    retry_count INT NOT NULL DEFAULT 0,
    next_retry_time DATETIME,
    last_error VARCHAR(1000),
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_knowledge_outbox_event (event_id),
    KEY idx_knowledge_outbox_publish (status, next_retry_time, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS knowledge_reindex_task (
    id BIGINT NOT NULL AUTO_INCREMENT,
    target_index VARCHAR(128),
    status VARCHAR(32) NOT NULL,
    document_total INT NOT NULL DEFAULT 0,
    document_success INT NOT NULL DEFAULT 0,
    document_failure INT NOT NULL DEFAULT 0,
    chunk_total INT NOT NULL DEFAULT 0,
    error_message VARCHAR(1000),
    create_by BIGINT NOT NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    start_time DATETIME,
    finish_time DATETIME,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_reindex_status_time (status, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
