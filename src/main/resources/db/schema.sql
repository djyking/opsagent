-- OpsAgent 第一阶段完整初始化脚本（MySQL 8.x）。
-- 可重复执行；每次执行都会删除并重建下列业务表，因此仅适用于初始化或允许清空数据的环境。
CREATE DATABASE IF NOT EXISTS opsagent DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE opsagent;

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

DROP TABLE IF EXISTS ai_qa_reference;
DROP TABLE IF EXISTS ai_task;
DROP TABLE IF EXISTS notification_record;
DROP TABLE IF EXISTS operation_log;
DROP TABLE IF EXISTS ai_chat_log;
DROP TABLE IF EXISTS document_chunk;
DROP TABLE IF EXISTS document;
DROP TABLE IF EXISTS ticket_status_log;
DROP TABLE IF EXISTS ticket;
DROP TABLE IF EXISTS sys_user_role;
DROP TABLE IF EXISTS sys_role;
DROP TABLE IF EXISTS sys_user;

CREATE TABLE sys_user (
 id BIGINT NOT NULL AUTO_INCREMENT, username VARCHAR(64) NOT NULL, password VARCHAR(128) NOT NULL,
 display_name VARCHAR(64) NOT NULL, status VARCHAR(32) NOT NULL DEFAULT 'enable',
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 deleted TINYINT NOT NULL DEFAULT 0, PRIMARY KEY (id), UNIQUE KEY uk_sys_user_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统用户';

CREATE TABLE sys_role (
 id BIGINT NOT NULL AUTO_INCREMENT, code VARCHAR(64) NOT NULL, name VARCHAR(64) NOT NULL,
 status VARCHAR(32) NOT NULL DEFAULT 'enable', create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 deleted TINYINT NOT NULL DEFAULT 0, PRIMARY KEY (id), UNIQUE KEY uk_sys_role_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统角色';

CREATE TABLE sys_user_role (
 id BIGINT NOT NULL AUTO_INCREMENT, user_id BIGINT NOT NULL, role_id BIGINT NOT NULL,
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY (id),
 UNIQUE KEY uk_sys_user_role (user_id, role_id), KEY idx_sys_user_role_role_id (role_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户角色关系';

-- 基础角色使用唯一键更新语义，独立重复执行本段也不会产生重复数据。
INSERT INTO sys_role (code, name, status, deleted)
VALUES ('USER', '普通用户', 'enable', 0),
       ('OPS', '运维人员', 'enable', 0),
       ('ADMIN', '管理员', 'enable', 0)
ON DUPLICATE KEY UPDATE
 name = VALUES(name), status = VALUES(status), deleted = VALUES(deleted);

CREATE TABLE ticket (
 id BIGINT NOT NULL AUTO_INCREMENT, ticket_no VARCHAR(32) NOT NULL, title VARCHAR(128) NOT NULL,
 description TEXT NOT NULL, priority VARCHAR(32) NOT NULL,
 status VARCHAR(32) NOT NULL DEFAULT 'CREATED', creator_id BIGINT NOT NULL, assignee_id BIGINT NULL,
 version INT NOT NULL DEFAULT 0, create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 deleted TINYINT NOT NULL DEFAULT 0, PRIMARY KEY (id), UNIQUE KEY uk_ticket_ticket_no (ticket_no),
 KEY idx_ticket_creator_time (creator_id, create_time),
 KEY idx_ticket_assignee_status_time (assignee_id, status, update_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='运维工单';

CREATE TABLE ticket_status_log (
 id BIGINT NOT NULL AUTO_INCREMENT, ticket_id BIGINT NOT NULL, operator_id BIGINT NOT NULL,
 operation_type VARCHAR(32) NOT NULL, from_status VARCHAR(32) NULL, to_status VARCHAR(32) NOT NULL,
 remark VARCHAR(512) NULL, create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY (id),
 KEY idx_ticket_status_log_ticket_time (ticket_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工单核心操作记录';

CREATE TABLE document (
 id BIGINT NOT NULL AUTO_INCREMENT, ticket_id BIGINT NOT NULL, original_name VARCHAR(255) NOT NULL,
 storage_name VARCHAR(128) NOT NULL, storage_path VARCHAR(512) NOT NULL, content_type VARCHAR(128) NOT NULL,
 file_extension VARCHAR(16) NOT NULL, file_size BIGINT NOT NULL, file_hash CHAR(64) NOT NULL,
 parse_status VARCHAR(32) NOT NULL DEFAULT 'PENDING', parse_error VARCHAR(1000) NULL,
 create_by BIGINT NOT NULL, create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 deleted TINYINT NOT NULL DEFAULT 0, PRIMARY KEY (id),
 KEY idx_document_ticket_time (ticket_id, create_time), KEY idx_document_hash (file_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工单文档';

CREATE TABLE document_chunk (
 id BIGINT NOT NULL AUTO_INCREMENT, document_id BIGINT NOT NULL, chunk_index INT NOT NULL,
 content MEDIUMTEXT NOT NULL, token_count INT NULL, page_number INT NULL,
 section_title VARCHAR(255) NULL, metadata_json JSON NULL,
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, deleted TINYINT NOT NULL DEFAULT 0,
 PRIMARY KEY (id), UNIQUE KEY uk_document_chunk_document_index (document_id, chunk_index)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文档切片';

CREATE TABLE ai_chat_log (
 id BIGINT NOT NULL AUTO_INCREMENT, ticket_id BIGINT NOT NULL, document_id BIGINT NULL,
 user_id BIGINT NOT NULL, question TEXT NOT NULL, answer MEDIUMTEXT NULL, model_name VARCHAR(128) NULL,
 prompt_tokens INT NULL, completion_tokens INT NULL, status VARCHAR(32) NOT NULL,
 error_message VARCHAR(1000) NULL, cost_time_ms BIGINT NULL,
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY (id),
 KEY idx_ai_chat_ticket_time (ticket_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 问答记录';

CREATE TABLE ai_qa_reference (
 id BIGINT NOT NULL AUTO_INCREMENT, qa_record_id BIGINT NOT NULL, chunk_id BIGINT NOT NULL,
 relevance_score DOUBLE NOT NULL, create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY (id), UNIQUE KEY uk_ai_qa_reference_record_chunk (qa_record_id, chunk_id),
 KEY idx_ai_qa_reference_chunk (chunk_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 问答引用';

CREATE TABLE operation_log (
 id BIGINT NOT NULL AUTO_INCREMENT, biz_type VARCHAR(64) NOT NULL, biz_id BIGINT NOT NULL,
 operation_type VARCHAR(64) NOT NULL, operator VARCHAR(64) NOT NULL, content VARCHAR(1000) NOT NULL,
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY (id),
 KEY idx_operation_log_biz (biz_type, biz_id), KEY idx_operation_log_operator (operator)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='附加审计日志';

CREATE TABLE notification_record (
 id BIGINT NOT NULL AUTO_INCREMENT, ticket_id BIGINT NOT NULL, receiver VARCHAR(64) NOT NULL,
 title VARCHAR(128) NOT NULL, content VARCHAR(1000) NOT NULL,
 status VARCHAR(32) NOT NULL DEFAULT 'PENDING', create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY (id), KEY idx_notification_ticket (ticket_id), KEY idx_notification_receiver (receiver)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通知记录';

CREATE TABLE ai_task (
 id BIGINT NOT NULL AUTO_INCREMENT, biz_type VARCHAR(64) NOT NULL, biz_id BIGINT NOT NULL,
 task_type VARCHAR(64) NOT NULL, status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
 request_payload TEXT NULL, result TEXT NULL, create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 PRIMARY KEY (id), KEY idx_ai_task_biz (biz_type, biz_id), KEY idx_ai_task_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 后续任务记录';

SET FOREIGN_KEY_CHECKS = 1;
