-- OpsAgent Phase 1 MySQL DDL
-- Target: MySQL 8.x

CREATE DATABASE IF NOT EXISTS opsagent
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_general_ci;

USE opsagent;

DROP TABLE IF EXISTS ai_task;
DROP TABLE IF EXISTS notification_record;
DROP TABLE IF EXISTS operation_log;
DROP TABLE IF EXISTS ai_chat_log;
DROP TABLE IF EXISTS document_chunk;
DROP TABLE IF EXISTS document;
DROP TABLE IF EXISTS ticket_status_log;
DROP TABLE IF EXISTS ticket;
DROP TABLE IF EXISTS sys_user;

CREATE TABLE sys_user (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'primary key',
    username VARCHAR(64) NOT NULL COMMENT 'username',
    password VARCHAR(128) NOT NULL COMMENT 'BCrypt password',
    display_name VARCHAR(64) NOT NULL COMMENT 'display name',
    status VARCHAR(32) NOT NULL DEFAULT 'enable' COMMENT 'enable, disable',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'create time',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP COMMENT 'update time',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT 'logical delete flag: 0 normal, 1 deleted',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_user_username (username)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = 'system user';

CREATE TABLE ticket (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'primary key',
    title VARCHAR(128) NOT NULL COMMENT 'ticket title',
    description TEXT NOT NULL COMMENT 'ticket description',
    priority VARCHAR(32) NOT NULL COMMENT 'LOW, MEDIUM, HIGH, URGENT',
    status VARCHAR(32) NOT NULL DEFAULT 'OPEN'
        COMMENT 'OPEN, PROCESSING, RESOLVED, CLOSED',
    create_by VARCHAR(64) NOT NULL COMMENT 'creator username',
    assignee VARCHAR(64) NULL COMMENT 'assignee username',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'create time',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP COMMENT 'update time',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT 'logical delete flag: 0 normal, 1 deleted',
    PRIMARY KEY (id),
    KEY idx_ticket_create_by (create_by),
    KEY idx_ticket_assignee (assignee),
    KEY idx_ticket_create_time (create_time),
    KEY idx_ticket_update_time (update_time)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = 'ops ticket';

CREATE TABLE ticket_status_log (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'primary key',
    ticket_id BIGINT NOT NULL COMMENT 'ticket id',
    from_status VARCHAR(32) NOT NULL COMMENT 'from status',
    to_status VARCHAR(32) NOT NULL COMMENT 'to status',
    operator VARCHAR(64) NOT NULL COMMENT 'operator username',
    reason VARCHAR(512) NULL COMMENT 'change reason',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'create time',
    PRIMARY KEY (id),
    KEY idx_ticket_status_log_ticket_id (ticket_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = 'ticket status change log';

CREATE TABLE document (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'primary key',
    file_name VARCHAR(255) NOT NULL COMMENT 'original file name',
    file_type VARCHAR(32) NOT NULL COMMENT 'file type',
    file_size BIGINT NOT NULL DEFAULT 0 COMMENT 'file size in bytes',
    storage_path VARCHAR(512) NULL COMMENT 'storage path',
    status VARCHAR(32) NOT NULL DEFAULT 'UPLOADED'
        COMMENT 'UPLOADED, PARSED, FAILED',
    create_by VARCHAR(64) NOT NULL COMMENT 'uploader username',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'create time',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP COMMENT 'update time',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT 'logical delete flag: 0 normal, 1 deleted',
    PRIMARY KEY (id),
    KEY idx_document_file_name (file_name),
    KEY idx_document_create_by (create_by),
    KEY idx_document_create_time (create_time)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = 'document metadata';

CREATE TABLE document_chunk (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'primary key',
    document_id BIGINT NOT NULL COMMENT 'document id',
    chunk_index INT NOT NULL COMMENT 'chunk index',
    content TEXT NOT NULL COMMENT 'chunk content',
    token_estimate INT NULL COMMENT 'estimated token count',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'create time',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT 'logical delete flag: 0 normal, 1 deleted',
    PRIMARY KEY (id),
    UNIQUE KEY uk_document_chunk_document_index (document_id, chunk_index)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = 'document chunk';

CREATE TABLE ai_chat_log (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'primary key',
    question TEXT NOT NULL COMMENT 'user question',
    answer TEXT NULL COMMENT 'model answer',
    document_id BIGINT NULL COMMENT 'related document id',
    used_chunks TEXT NULL COMMENT 'used chunk ids, json text',
    cost_time_ms BIGINT NULL COMMENT 'cost time in milliseconds',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'create time',
    PRIMARY KEY (id),
    KEY idx_ai_chat_log_document_id (document_id),
    KEY idx_ai_chat_log_create_time (create_time)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = 'ai chat log';

CREATE TABLE operation_log (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'primary key',
    biz_type VARCHAR(64) NOT NULL COMMENT 'business type',
    biz_id BIGINT NOT NULL COMMENT 'business id',
    operation_type VARCHAR(64) NOT NULL COMMENT 'operation type',
    operator VARCHAR(64) NOT NULL COMMENT 'operator username',
    content VARCHAR(1000) NOT NULL COMMENT 'operation content',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'create time',
    PRIMARY KEY (id),
    KEY idx_operation_log_biz (biz_type, biz_id),
    KEY idx_operation_log_create_time (create_time)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = 'operation audit log';

CREATE TABLE notification_record (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'primary key',
    ticket_id BIGINT NOT NULL COMMENT 'ticket id',
    receiver VARCHAR(64) NOT NULL COMMENT 'receiver username',
    title VARCHAR(128) NOT NULL COMMENT 'notification title',
    content VARCHAR(1000) NOT NULL COMMENT 'notification content',
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING'
        COMMENT 'PENDING, SENT, FAILED',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'create time',
    PRIMARY KEY (id),
    KEY idx_notification_ticket_id (ticket_id),
    KEY idx_notification_receiver (receiver),
    KEY idx_notification_create_time (create_time)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = 'notification record';

CREATE TABLE ai_task (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'primary key',
    biz_type VARCHAR(64) NOT NULL COMMENT 'business type',
    biz_id BIGINT NOT NULL COMMENT 'business id',
    task_type VARCHAR(64) NOT NULL COMMENT 'task type',
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING'
        COMMENT 'PENDING, PROCESSING, SUCCESS, FAILED',
    request_payload TEXT NULL COMMENT 'request payload, json text',
    result TEXT NULL COMMENT 'ai result',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'create time',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP COMMENT 'update time',
    PRIMARY KEY (id),
    KEY idx_ai_task_biz (biz_type, biz_id),
    KEY idx_ai_task_create_time (create_time)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = 'ai task';
