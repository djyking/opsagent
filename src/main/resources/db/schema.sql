CREATE DATABASE IF NOT EXISTS opsagent DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

USE opsagent;

DROP TABLE IF EXISTS document;
DROP TABLE IF EXISTS knowledge_base;
DROP TABLE IF EXISTS ticket;

CREATE TABLE ticket (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'primary key',
    title VARCHAR(100) NOT NULL COMMENT 'ticket title',
    description TEXT NULL COMMENT 'ticket description',
    status VARCHAR(32) NOT NULL COMMENT 'ticket status',
    priority VARCHAR(32) NOT NULL COMMENT 'ticket priority',
    source_system VARCHAR(64) NULL COMMENT 'source system',
    assignee VARCHAR(64) NULL COMMENT 'assignee',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'created time',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'updated time',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT 'logical delete flag, 0 normal, 1 deleted',
    PRIMARY KEY (id),
    KEY idx_ticket_status (status),
    KEY idx_ticket_priority (priority),
    KEY idx_ticket_source_system (source_system),
    KEY idx_ticket_created_at (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'ops ticket';

CREATE TABLE knowledge_base (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'primary key',
    name VARCHAR(100) NOT NULL COMMENT 'knowledge base name',
    description VARCHAR(1000) NULL COMMENT 'description',
    owner VARCHAR(64) NULL COMMENT 'owner',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'created time',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'updated time',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT 'logical delete flag, 0 normal, 1 deleted',
    PRIMARY KEY (id),
    KEY idx_knowledge_base_name (name),
    KEY idx_knowledge_base_owner (owner),
    KEY idx_knowledge_base_created_at (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'knowledge base';

CREATE TABLE document (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'primary key',
    knowledge_base_id BIGINT NOT NULL COMMENT 'knowledge base id',
    file_name VARCHAR(255) NOT NULL COMMENT 'file name',
    file_type VARCHAR(32) NULL COMMENT 'file type',
    file_size BIGINT NOT NULL DEFAULT 0 COMMENT 'file size in bytes',
    parse_status VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING, PROCESSING, SUCCESS, FAILED',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'created time',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'updated time',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT 'logical delete flag, 0 normal, 1 deleted',
    PRIMARY KEY (id),
    KEY idx_document_knowledge_base_id (knowledge_base_id),
    KEY idx_document_parse_status (parse_status),
    KEY idx_document_file_type (file_type),
    KEY idx_document_created_at (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'document metadata';

INSERT INTO ticket (title, description, status, priority, source_system, assignee)
VALUES
    ('CPU usage is high', 'Production server CPU usage is above 90%.', 'OPEN', 'HIGH', 'monitoring', 'alice'),
    ('Database slow query', 'Order query response time increased.', 'PROCESSING', 'MEDIUM', 'apm', 'bob'),
    ('Disk usage warning', 'Log disk usage reached 80%.', 'RESOLVED', 'LOW', 'monitoring', 'charlie');

INSERT INTO knowledge_base (name, description, owner)
VALUES
    ('Linux Operations', 'Common Linux troubleshooting guides.', 'ops-team'),
    ('Database Operations', 'MySQL operation and troubleshooting notes.', 'dba-team');

INSERT INTO document (knowledge_base_id, file_name, file_type, file_size, parse_status)
VALUES
    (1, 'linux-cpu-troubleshooting.md', 'md', 2048, 'SUCCESS'),
    (1, 'disk-cleanup-guide.pdf', 'pdf', 102400, 'PENDING'),
    (2, 'mysql-slow-query.md', 'md', 4096, 'SUCCESS');
