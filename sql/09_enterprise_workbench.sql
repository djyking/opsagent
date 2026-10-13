-- 工单处置工作台：保存结构化诊断、执行、根因和验证记录。
SET NAMES utf8mb4;
USE ops_ticket;

CREATE TABLE IF NOT EXISTS ticket_work_record (
    id BIGINT NOT NULL AUTO_INCREMENT,
    ticket_id BIGINT NOT NULL,
    record_type VARCHAR(32) NOT NULL,
    content VARCHAR(2000) NOT NULL,
    evidence VARCHAR(1000),
    create_by BIGINT NOT NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(id),
    KEY idx_work_record_ticket_time(ticket_id,create_time),
    KEY idx_work_record_type_time(record_type,create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

USE ops_knowledge;
SET @ticket_column_exists = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema='ops_knowledge' AND table_name='knowledge_document'
      AND column_name='ticket_id'
);
SET @ticket_column_sql = IF(
    @ticket_column_exists=0,
    'ALTER TABLE knowledge_document ADD COLUMN ticket_id BIGINT NULL AFTER knowledge_base_id, ADD KEY idx_document_ticket_time(ticket_id,create_time)',
    'SELECT 1'
);
PREPARE ticket_column_statement FROM @ticket_column_sql;
EXECUTE ticket_column_statement;
DEALLOCATE PREPARE ticket_column_statement;
