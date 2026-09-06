-- 只读候选清单：先备份数据库，再人工核对本查询输出；本文件不会归档。
-- 候选仍可能含后续人工修改；history/workRecord/outbox 计数供审阅，来源不明确者从批准清单排除。
USE ops_ticket;
SELECT t.id,t.ticket_no,t.title,t.status,t.version,t.create_time,t.update_time,
       SHA2(CONCAT_WS('|',t.id,t.ticket_no,t.version,t.title,t.description,t.priority,t.status,t.creator_id,COALESCE(t.assignee_id,0),t.update_time),256) AS rowHash,
       (SELECT COUNT(*) FROM ticket_history h WHERE h.ticket_id=t.id) historyCount,
       (SELECT COUNT(*) FROM ticket_work_record w WHERE w.ticket_id=t.id) workRecordCount,
       (SELECT COUNT(*) FROM event_outbox o WHERE o.aggregate_id=t.id AND o.aggregate_type='TICKET') eventCount,
       JSON_OBJECT('id',t.id,'ticketNo',t.ticket_no,'version',t.version,'rowHash',SHA2(CONCAT_WS('|',t.id,t.ticket_no,t.version,t.title,t.description,t.priority,t.status,t.creator_id,COALESCE(t.assignee_id,0),t.update_time),256)) manifestEntry
FROM ticket t WHERE t.deleted=0 AND t.source_type='MANUAL' AND t.episode_id IS NULL AND t.incident_id IS NULL
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
       AND o.event_type='ticket.demo.initialized' AND o.event_id=CONCAT('07000000-0000-0000-0000-',LPAD(t.id,12,'0')))))
ORDER BY t.id;
