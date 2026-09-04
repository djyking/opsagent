-- OpsAgent ITSM 能力补全：SLA、Alertmanager、CMDB Lite、值班和知识审核。
-- 本脚本只做幂等增量变更，不删除数据库和现有数据。
SET NAMES utf8mb4;

USE ops_platform;
CREATE TABLE IF NOT EXISTS cmdb_ci (
    id BIGINT NOT NULL AUTO_INCREMENT,
    ci_code VARCHAR(64) NOT NULL,
    ci_name VARCHAR(128) NOT NULL,
    ci_type VARCHAR(32) NOT NULL,
    environment VARCHAR(32) NOT NULL DEFAULT 'PROD',
    owner_name VARCHAR(64),
    endpoint VARCHAR(255),
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    description VARCHAR(500),
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY(id),
    UNIQUE KEY uk_cmdb_ci_code(ci_code),
    KEY idx_cmdb_ci_type_status(ci_type,status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS cmdb_relation (
    id BIGINT NOT NULL AUTO_INCREMENT,
    source_ci_code VARCHAR(64) NOT NULL,
    target_ci_code VARCHAR(64) NOT NULL,
    relation_type VARCHAR(32) NOT NULL DEFAULT 'DEPENDS_ON',
    description VARCHAR(500),
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(id),
    UNIQUE KEY uk_cmdb_relation(source_ci_code,target_ci_code,relation_type),
    KEY idx_cmdb_relation_target(target_ci_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS oncall_schedule (
    id BIGINT NOT NULL AUTO_INCREMENT,
    schedule_code VARCHAR(64) NOT NULL,
    schedule_name VARCHAR(128) NOT NULL,
    service_ci_code VARCHAR(64),
    timezone VARCHAR(64) NOT NULL DEFAULT 'Asia/Shanghai',
    enabled TINYINT NOT NULL DEFAULT 1,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY(id),
    UNIQUE KEY uk_oncall_schedule_code(schedule_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS oncall_shift (
    id BIGINT NOT NULL AUTO_INCREMENT,
    schedule_id BIGINT NOT NULL,
    role_type VARCHAR(16) NOT NULL,
    user_id BIGINT NOT NULL,
    user_name VARCHAR(64) NOT NULL,
    start_time DATETIME NOT NULL,
    end_time DATETIME NOT NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(id),
    UNIQUE KEY uk_oncall_shift(schedule_id,role_type,start_time),
    KEY idx_oncall_current(schedule_id,start_time,end_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO cmdb_ci(ci_code,ci_name,ci_type,environment,owner_name,endpoint,status,description) VALUES
('ops-gateway','API 网关','SERVICE','PROD','平台组','http://127.0.0.1:18080','ACTIVE','统一入口与鉴权'),
('ops-auth-service','认证服务','SERVICE','PROD','平台组','http://127.0.0.1:8101','ACTIVE','用户认证和权限'),
('ops-ticket-service','工单服务','SERVICE','PROD','运维组','http://127.0.0.1:8102','ACTIVE','ITSM 工单、SLA 和告警接入'),
('ops-knowledge-service','知识服务','SERVICE','PROD','知识组','http://127.0.0.1:8103','ACTIVE','知识解析、审核和检索'),
('ops-rag-service','RAG 服务','SERVICE','PROD','AI 组','http://127.0.0.1:8104','ACTIVE','检索增强问答'),
('ops-platform-service','平台服务','SERVICE','PROD','平台组','http://127.0.0.1:8105','ACTIVE','CMDB、值班、通知和审计'),
('mysql','MySQL','DATABASE','PROD','DBA','127.0.0.1:3306','ACTIVE','业务数据存储'),
('redis','Redis','CACHE','PROD','中间件组','127.0.0.1:6379','ACTIVE','缓存、限流和分布式锁'),
('rabbitmq','RabbitMQ','MESSAGE_QUEUE','PROD','中间件组','127.0.0.1:5672','ACTIVE','领域事件总线'),
('elasticsearch','Elasticsearch','SEARCH','PROD','中间件组','http://127.0.0.1:9200','ACTIVE','知识向量索引'),
('nacos','Nacos','REGISTRY','PROD','中间件组','http://127.0.0.1:8848','ACTIVE','注册与配置中心'),
('prometheus','Prometheus','MONITOR','PROD','SRE','http://127.0.0.1:9090','ACTIVE','指标采集')
ON DUPLICATE KEY UPDATE ci_name=VALUES(ci_name),endpoint=VALUES(endpoint),description=VALUES(description);

INSERT IGNORE INTO cmdb_relation(source_ci_code,target_ci_code,relation_type,description) VALUES
('ops-gateway','ops-auth-service','ROUTES_TO','认证路由'),
('ops-gateway','ops-ticket-service','ROUTES_TO','工单路由'),
('ops-gateway','ops-knowledge-service','ROUTES_TO','知识路由'),
('ops-gateway','ops-rag-service','ROUTES_TO','问答路由'),
('ops-ticket-service','mysql','DEPENDS_ON','保存工单与 SLA'),
('ops-ticket-service','redis','DEPENDS_ON','SLA 扫描分布式锁'),
('ops-ticket-service','rabbitmq','DEPENDS_ON','Outbox 事件投递'),
('ops-ticket-service','nacos','REGISTERS_TO','服务注册'),
('ops-knowledge-service','elasticsearch','DEPENDS_ON','向量检索'),
('ops-rag-service','ops-knowledge-service','CALLS','检索知识切片');

INSERT INTO oncall_schedule(schedule_code,schedule_name,service_ci_code,timezone,enabled)
VALUES('OPS-PRIMARY','OpsAgent 生产值班','ops-ticket-service','Asia/Shanghai',1)
ON DUPLICATE KEY UPDATE schedule_name=VALUES(schedule_name),enabled=1;
SET @schedule_id=(SELECT id FROM oncall_schedule WHERE schedule_code='OPS-PRIMARY');
DELETE FROM oncall_shift WHERE schedule_id=@schedule_id AND start_time>=CURDATE();
INSERT INTO oncall_shift(schedule_id,role_type,user_id,user_name,start_time,end_time)
SELECT @schedule_id,'PRIMARY',2,'运维工程师',DATE_ADD(CURDATE(),INTERVAL n DAY),DATE_ADD(CURDATE(),INTERVAL n+1 DAY)
FROM (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9 UNION ALL SELECT 10 UNION ALL SELECT 11 UNION ALL SELECT 12 UNION ALL SELECT 13) days;
INSERT INTO oncall_shift(schedule_id,role_type,user_id,user_name,start_time,end_time)
SELECT @schedule_id,'SECONDARY',1,'系统管理员',DATE_ADD(CURDATE(),INTERVAL n DAY),DATE_ADD(CURDATE(),INTERVAL n+1 DAY)
FROM (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9 UNION ALL SELECT 10 UNION ALL SELECT 11 UNION ALL SELECT 12 UNION ALL SELECT 13) days;

USE ops_ticket;
SET @sql=(SELECT IF(COUNT(*)=0,'ALTER TABLE ticket ADD COLUMN affected_ci_code VARCHAR(64) NULL AFTER assignee_id, ADD COLUMN source_type VARCHAR(32) NOT NULL DEFAULT ''MANUAL'' AFTER affected_ci_code, ADD KEY idx_ticket_ci_time(affected_ci_code,create_time), ADD KEY idx_ticket_source_time(source_type,create_time)','SELECT 1') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='ops_ticket' AND TABLE_NAME='ticket' AND COLUMN_NAME='affected_ci_code');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

CREATE TABLE IF NOT EXISTS sla_policy (
    id BIGINT NOT NULL AUTO_INCREMENT,
    policy_name VARCHAR(128) NOT NULL,
    priority VARCHAR(16) NOT NULL,
    response_minutes INT NOT NULL,
    resolution_minutes INT NOT NULL,
    warning_percent INT NOT NULL DEFAULT 80,
    enabled TINYINT NOT NULL DEFAULT 1,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY(id), UNIQUE KEY uk_sla_policy_priority(priority)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO sla_policy(policy_name,priority,response_minutes,resolution_minutes,warning_percent,enabled) VALUES
('紧急事件 SLA','URGENT',5,30,80,1),('高优先级 SLA','HIGH',15,120,80,1),
('中优先级 SLA','MEDIUM',60,480,80,1),('低优先级 SLA','LOW',240,1440,80,1)
ON DUPLICATE KEY UPDATE response_minutes=VALUES(response_minutes),resolution_minutes=VALUES(resolution_minutes),warning_percent=VALUES(warning_percent),enabled=1;

CREATE TABLE IF NOT EXISTS ticket_sla (
    id BIGINT NOT NULL AUTO_INCREMENT,
    ticket_id BIGINT NOT NULL,
    policy_id BIGINT NOT NULL,
    response_deadline DATETIME NOT NULL,
    resolution_deadline DATETIME NOT NULL,
    response_status VARCHAR(16) NOT NULL DEFAULT 'RUNNING',
    resolution_status VARCHAR(16) NOT NULL DEFAULT 'RUNNING',
    response_time DATETIME,
    resolution_time DATETIME,
    escalation_level INT NOT NULL DEFAULT 0,
    next_check_time DATETIME,
    version INT NOT NULL DEFAULT 0,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY(id), UNIQUE KEY uk_ticket_sla_ticket(ticket_id), KEY idx_ticket_sla_next(next_check_time,resolution_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS ticket_sla_event (
    id BIGINT NOT NULL AUTO_INCREMENT,
    sla_id BIGINT NOT NULL,
    ticket_id BIGINT NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    escalation_level INT NOT NULL DEFAULT 0,
    detail VARCHAR(500),
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(id), UNIQUE KEY uk_sla_event(sla_id,event_type,escalation_level), KEY idx_sla_event_ticket(ticket_id,create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 为存量未关闭工单补建 SLA，便于升级后立即在看板中观察计时状态。
INSERT IGNORE INTO ticket_sla(
    ticket_id,policy_id,response_deadline,resolution_deadline,
    response_status,resolution_status,response_time,resolution_time,
    escalation_level,next_check_time,version,create_time,update_time)
SELECT t.id,p.id,
       DATE_ADD(t.create_time,INTERVAL p.response_minutes MINUTE),
       DATE_ADD(t.create_time,INTERVAL p.resolution_minutes MINUTE),
       IF(t.status='CREATED','RUNNING','COMPLETED'),
       IF(t.status IN ('RESOLVED','CLOSED'),'COMPLETED','RUNNING'),
       IF(t.status='CREATED',NULL,t.update_time),
       IF(t.status IN ('RESOLVED','CLOSED'),t.update_time,NULL),
       0,
       IF(t.status IN ('RESOLVED','CLOSED'),NULL,NOW()),
       0,t.create_time,NOW()
FROM ticket t JOIN sla_policy p ON p.priority=t.priority AND p.enabled=1
WHERE t.deleted=0;

CREATE TABLE IF NOT EXISTS monitor_alert (
    id BIGINT NOT NULL AUTO_INCREMENT,
    fingerprint VARCHAR(128) NOT NULL,
    ticket_id BIGINT,
    alert_name VARCHAR(128) NOT NULL,
    severity VARCHAR(32),
    service_code VARCHAR(64),
    current_status VARCHAR(16) NOT NULL,
    occurrence_count INT NOT NULL DEFAULT 1,
    first_seen_time DATETIME NOT NULL,
    last_seen_time DATETIME NOT NULL,
    resolved_time DATETIME,
    labels_json JSON,
    annotations_json JSON,
    PRIMARY KEY(id), UNIQUE KEY uk_monitor_alert_fingerprint(fingerprint), KEY idx_monitor_alert_status_time(current_status,last_seen_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS monitor_alert_event (
    id BIGINT NOT NULL AUTO_INCREMENT,
    alert_id BIGINT NOT NULL,
    event_status VARCHAR(16) NOT NULL,
    payload_json JSON,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(id), KEY idx_alert_event_alert_time(alert_id,create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

USE ops_knowledge;
SET @sql=(SELECT IF(COUNT(*)=0,'ALTER TABLE knowledge_document ADD COLUMN review_status VARCHAR(16) NOT NULL DEFAULT ''DRAFT'' AFTER status, ADD COLUMN submitted_by BIGINT NULL, ADD COLUMN submitted_time DATETIME NULL, ADD COLUMN reviewer_id BIGINT NULL, ADD COLUMN review_time DATETIME NULL, ADD COLUMN publish_time DATETIME NULL, ADD COLUMN review_comment VARCHAR(1000) NULL, ADD KEY idx_document_review_time(review_status,update_time)','SELECT 1') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='ops_knowledge' AND TABLE_NAME='knowledge_document' AND COLUMN_NAME='review_status');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
CREATE TABLE IF NOT EXISTS knowledge_review_history (
    id BIGINT NOT NULL AUTO_INCREMENT,
    document_id BIGINT NOT NULL,
    from_status VARCHAR(16),
    to_status VARCHAR(16) NOT NULL,
    operator_id BIGINT NOT NULL,
    comment VARCHAR(1000),
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(id), KEY idx_review_history_document(document_id,create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
UPDATE knowledge_document SET review_status='PUBLISHED',reviewer_id=1,review_time=COALESCE(update_time,create_time),publish_time=COALESCE(update_time,create_time),review_comment='历史正式知识迁移为已发布' WHERE review_status='DRAFT' AND status IN ('PARSED','INDEXED') AND deleted=0;

-- 审核治理演示数据。未发布状态没有向量切片，不会进入生产 RAG 上下文。
INSERT INTO knowledge_document(
    knowledge_base_id,ticket_id,file_name,original_name,file_type,file_size,storage_path,
    status,review_status,content_hash,version,visibility,create_by,submitted_by,
    submitted_time,reviewer_id,review_time,review_comment,create_time,update_time,deleted)
SELECT 1,NULL,'postmortem-order-latency-draft.md','事故复盘_订单延迟_草稿.md','md',1024,
       'demo-data/knowledge-review/postmortem-order-latency-draft.md','PARSED','DRAFT',
       REPEAT('a',64),1,'PUBLIC',1,NULL,NULL,NULL,NULL,NULL,NOW(),NOW(),0
WHERE NOT EXISTS(SELECT 1 FROM knowledge_document WHERE content_hash=REPEAT('a',64) AND deleted=0);
INSERT INTO knowledge_document(
    knowledge_base_id,ticket_id,file_name,original_name,file_type,file_size,storage_path,
    status,review_status,content_hash,version,visibility,create_by,submitted_by,
    submitted_time,reviewer_id,review_time,review_comment,create_time,update_time,deleted)
SELECT 1,NULL,'redis-capacity-review.md','Redis容量评估_待审核.md','md',1024,
       'demo-data/knowledge-review/redis-capacity-review.md','PARSED','IN_REVIEW',
       REPEAT('b',64),1,'PUBLIC',2,2,DATE_SUB(NOW(),INTERVAL 2 HOUR),NULL,NULL,NULL,
       DATE_SUB(NOW(),INTERVAL 1 DAY),NOW(),0
WHERE NOT EXISTS(SELECT 1 FROM knowledge_document WHERE content_hash=REPEAT('b',64) AND deleted=0);
INSERT INTO knowledge_document(
    knowledge_base_id,ticket_id,file_name,original_name,file_type,file_size,storage_path,
    status,review_status,content_hash,version,visibility,create_by,submitted_by,
    submitted_time,reviewer_id,review_time,review_comment,create_time,update_time,deleted)
SELECT 1,NULL,'nacos-canary-review.md','Nacos灰度发布规范_待审核.md','md',1024,
       'demo-data/knowledge-review/nacos-canary-review.md','PARSED','IN_REVIEW',
       REPEAT('c',64),1,'PUBLIC',2,2,DATE_SUB(NOW(),INTERVAL 1 HOUR),NULL,NULL,NULL,
       DATE_SUB(NOW(),INTERVAL 1 DAY),NOW(),0
WHERE NOT EXISTS(SELECT 1 FROM knowledge_document WHERE content_hash=REPEAT('c',64) AND deleted=0);
INSERT INTO knowledge_document(
    knowledge_base_id,ticket_id,file_name,original_name,file_type,file_size,storage_path,
    status,review_status,content_hash,version,visibility,create_by,submitted_by,
    submitted_time,reviewer_id,review_time,review_comment,create_time,update_time,deleted)
SELECT 1,NULL,'unsafe-cleanup-rejected.md','未验证磁盘清理脚本_已驳回.md','md',1024,
       'demo-data/knowledge-review/unsafe-cleanup-rejected.md','PARSED','REJECTED',
       REPEAT('d',64),1,'PUBLIC',2,2,DATE_SUB(NOW(),INTERVAL 2 DAY),1,
       DATE_SUB(NOW(),INTERVAL 1 DAY),'缺少回滚步骤和数据目录保护说明',
       DATE_SUB(NOW(),INTERVAL 3 DAY),NOW(),0
WHERE NOT EXISTS(SELECT 1 FROM knowledge_document WHERE content_hash=REPEAT('d',64) AND deleted=0);
INSERT INTO knowledge_document(
    knowledge_base_id,ticket_id,file_name,original_name,file_type,file_size,storage_path,
    status,review_status,content_hash,version,visibility,create_by,submitted_by,
    submitted_time,reviewer_id,review_time,review_comment,create_time,update_time,deleted)
SELECT 1,NULL,'legacy-disk-guide-archived.md','旧版磁盘清理手册_已归档.md','md',1024,
       'demo-data/knowledge-review/legacy-disk-guide-archived.md','PARSED','ARCHIVED',
       REPEAT('e',64),1,'PUBLIC',1,1,DATE_SUB(NOW(),INTERVAL 10 DAY),1,
       DATE_SUB(NOW(),INTERVAL 8 DAY),'已由 201-06 磁盘容量应急处置手册替代',
       DATE_SUB(NOW(),INTERVAL 30 DAY),NOW(),0
WHERE NOT EXISTS(SELECT 1 FROM knowledge_document WHERE content_hash=REPEAT('e',64) AND deleted=0);
