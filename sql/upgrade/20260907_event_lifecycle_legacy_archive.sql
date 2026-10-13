-- 一次性标记升级前的终态工单；历史归档不等于事件验证或事件关闭。
-- 在上线新事件生命周期时由部署负责人运行。本轮开发不执行此脚本。
USE ops_ticket;

CREATE TABLE IF NOT EXISTS ops_ticket_schema_migration (
  migration_id VARCHAR(128) PRIMARY KEY,
  applied_at DATETIME NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

START TRANSACTION;
INSERT IGNORE INTO ops_ticket_schema_migration(migration_id,applied_at)
VALUES('20260907_event_lifecycle_legacy_archive_v1',NOW());
SET @apply_event_legacy_archive = ROW_COUNT();

INSERT INTO ticket_work_record(ticket_id,record_type,content,evidence,create_by,create_time)
SELECT t.id,'EVENT_LEGACY_ARCHIVE','升级前终态工单，保留为历史档案',
       '一次性升级快照；未补造技术验证、业务确认或事件关闭记录。',0,NOW()
FROM ticket t
WHERE @apply_event_legacy_archive=1 AND t.deleted=0 AND t.status IN ('CLOSED','REJECTED')
  AND NOT EXISTS (SELECT 1 FROM ticket_work_record r WHERE r.ticket_id=t.id AND r.record_type LIKE 'EVENT_%');
COMMIT;
