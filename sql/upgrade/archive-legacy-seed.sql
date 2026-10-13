-- 仅对已审阅的精确 JSON manifest 执行。默认 [] 不更改任何数据。
-- 先运行 archive-legacy-seed-candidates.sql，并保存云端全库备份。
-- 调用示例仅展示结构：SET @approved_legacy_manifest='[{"id":1,"ticketNo":"...","version":0,"rowHash":"..."}]';
-- 不要通过重跑 seed 脚本清理数据。此脚本保留子记录及来源审计，不制造已解决状态。
USE ops_ticket;
CREATE TABLE IF NOT EXISTS legacy_ticket_archive (
 ticket_id BIGINT PRIMARY KEY, ticket_no VARCHAR(64) NOT NULL,
 previous_version INT NOT NULL, row_hash CHAR(64) NOT NULL,
 snapshot_json JSON NOT NULL, archived_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
DROP PROCEDURE IF EXISTS archive_reviewed_legacy_tickets;
DELIMITER $$
CREATE PROCEDURE archive_reviewed_legacy_tickets(IN manifest JSON)
BEGIN
 DECLARE requested INT DEFAULT 0;
 DECLARE matched INT DEFAULT 0;
 DECLARE touched INT DEFAULT 0;
 DECLARE EXIT HANDLER FOR SQLEXCEPTION BEGIN ROLLBACK; RESIGNAL; END;
 SET requested=JSON_LENGTH(manifest);
 IF requested>0 THEN
  DROP TEMPORARY TABLE IF EXISTS reviewed_legacy_tickets;
  CREATE TEMPORARY TABLE reviewed_legacy_tickets(
    id BIGINT PRIMARY KEY,ticket_no VARCHAR(64) NOT NULL,version INT NOT NULL,row_hash CHAR(64) NOT NULL);
  INSERT INTO reviewed_legacy_tickets(id,ticket_no,version,row_hash)
  SELECT id,ticketNo,version,rowHash FROM JSON_TABLE(manifest,'$[*]' COLUMNS(
    id BIGINT PATH '$.id',ticketNo VARCHAR(64) PATH '$.ticketNo',
    version INT PATH '$.version',rowHash CHAR(64) PATH '$.rowHash')) manifest_rows;
  START TRANSACTION;
  SELECT COUNT(*) INTO matched FROM ticket t JOIN reviewed_legacy_tickets m
   ON m.id=t.id AND m.ticket_no=t.ticket_no AND m.version=t.version AND m.row_hash=SHA2(CONCAT_WS('|',t.id,t.ticket_no,t.version,t.title,t.description,t.priority,t.status,t.creator_id,COALESCE(t.assignee_id,0),t.update_time),256)
   WHERE t.deleted=0 AND t.source_type='MANUAL' AND t.episode_id IS NULL AND t.incident_id IS NULL
 AND NOT EXISTS(SELECT 1 FROM monitor_alert a WHERE a.ticket_id=t.id)
 AND NOT EXISTS(SELECT 1 FROM monitor_alert_episode e WHERE e.ticket_id=t.id)
 AND NOT EXISTS(SELECT 1 FROM agent_ticket_effect e WHERE e.ticket_id=t.id)
 AND ((t.id=1 AND t.ticket_no='OPS-DEMO-0001')
 OR (t.id IN (1001,1002,1003,1004,1005,1006,1007) AND t.ticket_no=CONCAT('OPS-SCENE-',t.id))
 OR (t.id=2000 AND t.ticket_no='OPS-20260828-2000')
 OR (t.id IN (2001,2002,2003,2004,2005,2006,2007,2008,2009,2010,2011,2012,2013,2014,2015,2016,2017,2018,2019,2020,2021,2022,2023,2024,2025,2026,2027,2028,2029,2030,2031,2032,2033,2034,2035,2036,2037,2038,2039) AND t.ticket_no=CONCAT('OPS-202608-',t.id)
   AND EXISTS (SELECT 1 FROM ticket_operation_log l WHERE l.ticket_id=t.id
       AND l.request_id=CONCAT('demo-create-',t.id) AND JSON_UNQUOTE(JSON_EXTRACT(l.detail_json,'$.source'))='enterprise-demo')
   AND EXISTS (SELECT 1 FROM event_outbox o WHERE o.aggregate_id=t.id AND o.aggregate_type='TICKET'
       AND o.event_type='ticket.demo.initialized' AND o.event_id=CONCAT('07000000-0000-0000-0000-',LPAD(t.id,12,'0')))));
  IF matched<>requested THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Manifest changed or provenance check failed'; END IF;
  INSERT INTO legacy_ticket_archive(ticket_id,ticket_no,previous_version,row_hash,snapshot_json)
  SELECT t.id,t.ticket_no,t.version,m.row_hash,
   JSON_OBJECT('id',t.id,'ticketNo',t.ticket_no,'title',t.title,'description',t.description,
    'status',t.status,'priority',t.priority,'creatorId',t.creator_id,'assigneeId',t.assignee_id,
    'sourceType',t.source_type,'version',t.version,'updateTime',t.update_time,
    'slaNextCheck',(SELECT s.next_check_time FROM ticket_sla s WHERE s.ticket_id=t.id LIMIT 1))
  FROM ticket t JOIN reviewed_legacy_tickets m ON m.id=t.id;
  UPDATE ticket t JOIN reviewed_legacy_tickets m ON m.id=t.id
   SET t.deleted=1,t.source_type='LEGACY_SEED',t.version=t.version+1,t.update_time=NOW()
   WHERE t.deleted=0 AND t.version=m.version AND m.row_hash=SHA2(CONCAT_WS('|',t.id,t.ticket_no,t.version,t.title,t.description,t.priority,t.status,t.creator_id,COALESCE(t.assignee_id,0),t.update_time),256);
  SET touched=ROW_COUNT();
  IF touched<>requested THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Concurrent ticket change; archive rolled back'; END IF;
  UPDATE ticket_sla s JOIN reviewed_legacy_tickets m ON m.id=s.ticket_id
   SET s.next_check_time=NULL,s.version=s.version+1,s.update_time=NOW();
  UPDATE event_outbox o JOIN reviewed_legacy_tickets m ON m.id=o.aggregate_id
   SET o.status='CANCELLED',o.next_retry_time=NULL,o.update_time=NOW()
   WHERE o.aggregate_type='TICKET' AND o.status IN ('PENDING','FAILED');
  INSERT INTO ticket_history(ticket_id,operator_id,operation_type,from_status,to_status,remark,create_time)
  SELECT t.id,0,'ARCHIVE_LEGACY_SEED',t.status,t.status,'按已审核精确清单归档旧样本；保留历史和审计',NOW()
  FROM ticket t JOIN reviewed_legacy_tickets m ON m.id=t.id;
  COMMIT;
  SELECT touched archivedCount;
 END IF;
END$$
DELIMITER ;
SET @approved_legacy_manifest=COALESCE(@approved_legacy_manifest,JSON_ARRAY());
CALL archive_reviewed_legacy_tickets(@approved_legacy_manifest);
DROP PROCEDURE archive_reviewed_legacy_tickets;
