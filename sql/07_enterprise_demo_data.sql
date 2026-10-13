-- OpsAgent企业级演示数据。脚本可重复执行，仅重建约定的 2000～2999 演示区间。

USE ops_auth;

INSERT INTO sys_user(id,username,password,display_name,status,deleted) VALUES
    (2001,'zhangqi','$2a$10$9RNwMWDkqh2WcERYUhODUOKyHRqLvezGU4T1seOxT6HL8/IhK7h6q','张琪（订单研发）','enable',0),
    (2002,'liyan','$2a$10$9RNwMWDkqh2WcERYUhODUOKyHRqLvezGU4T1seOxT6HL8/IhK7h6q','李妍（客服主管）','enable',0),
    (2003,'chenyu','$2a$10$9RNwMWDkqh2WcERYUhODUOKyHRqLvezGU4T1seOxT6HL8/IhK7h6q','陈宇（支付研发）','enable',0),
    (2004,'zhaolei','$2a$10$9RNwMWDkqh2WcERYUhODUOKyHRqLvezGU4T1seOxT6HL8/IhK7h6q','赵蕾（质量保障）','enable',0),
    (2005,'wangwei','$2a$10$9RNwMWDkqh2WcERYUhODUOKyHRqLvezGU4T1seOxT6HL8/IhK7h6q','王伟（SRE）','enable',0),
    (2006,'liuming','$2a$10$9RNwMWDkqh2WcERYUhODUOKyHRqLvezGU4T1seOxT6HL8/IhK7h6q','刘明（数据库运维）','enable',0),
    (2007,'sunhao','$2a$10$9RNwMWDkqh2WcERYUhODUOKyHRqLvezGU4T1seOxT6HL8/IhK7h6q','孙浩（中间件运维）','enable',0)
ON DUPLICATE KEY UPDATE password=VALUES(password),display_name=VALUES(display_name),status='enable',deleted=0;

INSERT IGNORE INTO sys_user_role(user_id,role_id) VALUES
    (2001,3),(2002,3),(2003,3),(2004,3),(2005,2),(2006,2),(2007,2);

USE ops_ticket;

DELETE FROM ticket_comment WHERE ticket_id BETWEEN 2000 AND 2999;
DELETE FROM ticket_attachment WHERE ticket_id BETWEEN 2000 AND 2999;
DELETE FROM ticket_history WHERE ticket_id BETWEEN 2000 AND 2999;
DELETE FROM ticket_assignment WHERE ticket_id BETWEEN 2000 AND 2999;
DELETE FROM ticket_operation_log WHERE ticket_id BETWEEN 2000 AND 2999;
DELETE FROM event_outbox WHERE aggregate_type='TICKET' AND aggregate_id BETWEEN 2000 AND 2999;
DELETE FROM ticket WHERE id BETWEEN 2000 AND 2999;

INSERT INTO ticket(
    id,ticket_no,title,description,priority,status,creator_id,assignee_id,
    version,create_time,update_time,deleted
) VALUES (
    2000,'OPS-20260828-2000','大促期间订单创建接口大量超时并出现429',
    'OpsAgent大促流量上涨后，订单创建接口 P99 从 420ms 升至 8.6s，网关同时出现大量 429。需要联合检查 Sentinel 限流、Redis 热点键、数据库连接池和下游库存接口。',
    'URGENT','CLOSED',2001,2005,5,'2026-08-28 20:03:00','2026-08-29 10:20:00',0
);

INSERT INTO ticket(
    id,ticket_no,title,description,priority,status,creator_id,assignee_id,
    version,create_time,update_time,deleted
)
WITH RECURSIVE seq(n) AS (
    SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 39
)
SELECT
    2000+n,
    CONCAT('OPS-202608-',LPAD(2000+n,4,'0')),
    CASE MOD(n,10)
        WHEN 0 THEN CONCAT('支付回调重复消费演练 #',n)
        WHEN 1 THEN CONCAT('商品详情缓存命中率下降 #',n)
        WHEN 2 THEN CONCAT('库存服务数据库连接池告警 #',n)
        WHEN 3 THEN CONCAT('Nacos 配置灰度后实例参数不一致 #',n)
        WHEN 4 THEN CONCAT('RabbitMQ 消费积压超过阈值 #',n)
        WHEN 5 THEN CONCAT('网关 P99 延迟持续升高 #',n)
        WHEN 6 THEN CONCAT('营销任务线程池队列积压 #',n)
        WHEN 7 THEN CONCAT('知识文档解析失败待人工复核 #',n)
        WHEN 8 THEN CONCAT('订单服务 JVM Old GC 频繁 #',n)
        ELSE CONCAT('Prometheus 目标抓取中断 #',n)
    END,
    CASE MOD(n,10)
        WHEN 0 THEN '支付回调出现重复消息，需用业务唯一键校验幂等表、确认 ACK 时机并回放隔离消息。'
        WHEN 1 THEN '商品缓存命中率低于 60%，回源 QPS 上升，需检查集中失效、淘汰策略和热点键。'
        WHEN 2 THEN 'Hikari pending 持续增长，需核查慢 SQL、长事务和 MySQL max_connections。'
        WHEN 3 THEN '同一 dataId 在不同实例读取值不一致，需核对 namespace、group 和监听日志。'
        WHEN 4 THEN '队列 ready 消息持续增长，需确认消费速率、失败重试、死信数量和下游容量。'
        WHEN 5 THEN '网关请求延迟超过 SLO，需按入口、应用、缓存、数据库链路逐层定位。'
        WHEN 6 THEN '定时营销任务占满平台线程池，需要限制并发、启用有界队列并错峰执行。'
        WHEN 7 THEN '上传文件解析异常，需查看解析任务重试次数及 DLQ，并确认文件格式是否损坏。'
        WHEN 8 THEN '堆内存回收频率上升，需保留 GC 日志和线程转储后再执行限流或扩容。'
        ELSE '监控抓取失败，需检查容器到宿主机地址、Actuator 暴露范围与网络连通性。'
    END,
    CASE MOD(n,4) WHEN 0 THEN 'LOW' WHEN 1 THEN 'MEDIUM' WHEN 2 THEN 'HIGH' ELSE 'URGENT' END,
    CASE MOD(n,8)
        WHEN 0 THEN 'CREATED' WHEN 1 THEN 'ASSIGNED' WHEN 2 THEN 'PROCESSING'
        WHEN 3 THEN 'SUSPENDED' WHEN 4 THEN 'WAITING_CONFIRM' WHEN 5 THEN 'RESOLVED'
        WHEN 6 THEN 'CLOSED' ELSE 'REJECTED'
    END,
    2001+MOD(n,4),
    CASE WHEN MOD(n,8) IN (0,7) THEN NULL ELSE 2005+MOD(n,3) END,
    CASE MOD(n,8)
        WHEN 0 THEN 0 WHEN 1 THEN 1 WHEN 2 THEN 2 WHEN 3 THEN 3
        WHEN 4 THEN 3 WHEN 5 THEN 3 WHEN 6 THEN 4 ELSE 1
    END,
    TIMESTAMP('2026-08-01 09:00:00') + INTERVAL n * 13 HOUR,
    TIMESTAMP('2026-08-01 09:00:00') + INTERVAL (n * 13 + MOD(n,8) + 1) HOUR,
    0
FROM seq;

INSERT INTO ticket_history(ticket_id,operator_id,operation_type,from_status,to_status,remark,create_time)
SELECT id,creator_id,'CREATE',NULL,'CREATED','业务方提交生产异常',create_time
FROM ticket WHERE id BETWEEN 2000 AND 2039;

INSERT INTO ticket_history(ticket_id,operator_id,operation_type,from_status,to_status,remark,create_time)
SELECT id,creator_id,'REJECT','CREATED','REJECTED','确认属于重复告警，关联已有工单',create_time + INTERVAL 20 MINUTE
FROM ticket WHERE id BETWEEN 2001 AND 2039 AND status='REJECTED';

INSERT INTO ticket_history(ticket_id,operator_id,operation_type,from_status,to_status,remark,create_time)
SELECT id,assignee_id,'CLAIM','CREATED','ASSIGNED','SRE 接单并开始收集指标',create_time + INTERVAL 10 MINUTE
FROM ticket WHERE id BETWEEN 2000 AND 2039 AND assignee_id IS NOT NULL;

INSERT INTO ticket_history(ticket_id,operator_id,operation_type,from_status,to_status,remark,create_time)
SELECT id,assignee_id,'PROCESSING','ASSIGNED','PROCESSING','已建立排障群并按手册定位',create_time + INTERVAL 25 MINUTE
FROM ticket WHERE id BETWEEN 2000 AND 2039 AND status IN ('PROCESSING','SUSPENDED','WAITING_CONFIRM','RESOLVED','CLOSED');

INSERT INTO ticket_history(ticket_id,operator_id,operation_type,from_status,to_status,remark,create_time)
SELECT id,assignee_id,'SUSPENDED','PROCESSING','SUSPENDED','等待业务低峰执行变更',create_time + INTERVAL 40 MINUTE
FROM ticket WHERE id BETWEEN 2001 AND 2039 AND status='SUSPENDED';

INSERT INTO ticket_history(ticket_id,operator_id,operation_type,from_status,to_status,remark,create_time)
SELECT id,assignee_id,'WAITING_CONFIRM','PROCESSING','WAITING_CONFIRM','技术指标恢复，等待业务确认',create_time + INTERVAL 55 MINUTE
FROM ticket WHERE id BETWEEN 2001 AND 2039 AND status='WAITING_CONFIRM';

INSERT INTO ticket_history(ticket_id,operator_id,operation_type,from_status,to_status,remark,create_time)
SELECT id,assignee_id,'RESOLVED','PROCESSING','RESOLVED','处置完成且核心指标恢复',create_time + INTERVAL 70 MINUTE
FROM ticket WHERE id BETWEEN 2000 AND 2039 AND status IN ('RESOLVED','CLOSED');

INSERT INTO ticket_history(ticket_id,operator_id,operation_type,from_status,to_status,remark,create_time)
SELECT id,creator_id,'CLOSED','RESOLVED','CLOSED','业务回归通过并关闭工单',update_time
FROM ticket WHERE id BETWEEN 2000 AND 2039 AND status='CLOSED';

INSERT INTO ticket_assignment(ticket_id,assignee_id,assigned_by,assignment_type,create_time)
SELECT id,assignee_id,assignee_id,'CLAIM',create_time + INTERVAL 10 MINUTE
FROM ticket WHERE id BETWEEN 2000 AND 2039 AND assignee_id IS NOT NULL;

INSERT INTO ticket_comment(ticket_id,user_id,content,create_time,update_time,deleted)
SELECT id,creator_id,'已补充发生时间、影响范围和一组脱敏请求样本。',create_time + INTERVAL 5 MINUTE,create_time + INTERVAL 5 MINUTE,0
FROM ticket WHERE id BETWEEN 2000 AND 2039;

INSERT INTO ticket_comment(ticket_id,user_id,content,create_time,update_time,deleted)
SELECT id,assignee_id,'已核对 Grafana 指标并关联内部 Runbook，处理过程持续同步。',create_time + INTERVAL 35 MINUTE,create_time + INTERVAL 35 MINUTE,0
FROM ticket WHERE id BETWEEN 2000 AND 2039 AND assignee_id IS NOT NULL;

INSERT INTO ticket_comment(ticket_id,user_id,content,create_time,update_time,deleted) VALUES
    (2000,2005,'20:08 确认网关 429 与订单接口超时同时上升，已冻结非必要发布。','2026-08-28 20:08:00','2026-08-28 20:08:00',0),
    (2000,2001,'20:12 业务确认下单成功率从 99.95% 降到 87.4%，主要影响华东区域。','2026-08-28 20:12:00','2026-08-28 20:12:00',0),
    (2000,2007,'20:18 Sentinel 热点参数规则阈值低于大促预案，先灰度调高并观察。','2026-08-28 20:18:00','2026-08-28 20:18:00',0),
    (2000,2006,'20:25 数据库连接池 pending 已回落，未发现长事务和锁等待。','2026-08-28 20:25:00','2026-08-28 20:25:00',0),
    (2000,2005,'20:42 下单成功率恢复到 99.92%，继续观察一个完整流量波峰。','2026-08-28 20:42:00','2026-08-28 20:42:00',0),
    (2000,2001,'次日业务回归通过，同意关闭；请补充阈值变更和复盘项。','2026-08-29 10:15:00','2026-08-29 10:15:00',0);

INSERT INTO ticket_attachment(ticket_id,file_name,storage_path,file_size,content_type,create_by,create_time,deleted) VALUES
    (2000,'gateway-timeout-sample.log','demo-data/generated/attachments/gateway-timeout-sample.log',2048,'text/plain',2001,'2026-08-28 20:05:00',0),
    (2000,'redis-metrics-20260828.txt','demo-data/generated/attachments/redis-metrics-20260828.txt',1024,'text/plain',2005,'2026-08-28 20:15:00',0),
    (2004,'rabbitmq-queue-metrics.txt','demo-data/generated/attachments/rabbitmq-queue-metrics.txt',1024,'text/plain',2007,'2026-08-04 16:30:00',0),
    (2003,'nacos-config-before-after.txt','demo-data/generated/attachments/nacos-config-before-after.txt',800,'text/plain',2007,'2026-08-03 10:30:00',0),
    (2002,'order-service-error-20260828.log','demo-data/generated/attachments/order-service-error-20260828.log',2400,'text/plain',2001,'2026-08-02 12:30:00',0);

INSERT INTO ticket_operation_log(ticket_id,operator_id,operation,request_id,detail_json,create_time)
SELECT id,creator_id,'CREATE',CONCAT('demo-create-',id),JSON_OBJECT('source','enterprise-demo'),create_time
FROM ticket WHERE id BETWEEN 2000 AND 2039;

INSERT INTO event_outbox(event_id,aggregate_type,aggregate_id,event_type,payload,status,retry_count,next_retry_time,create_time,update_time)
SELECT CONCAT('07000000-0000-0000-0000-',LPAD(id,12,'0')),'TICKET',id,'ticket.demo.initialized',
       JSON_OBJECT('ticketId',id,'actorId',creator_id,'status',status),
       'PENDING',0,NOW(),NOW(),NOW()
FROM ticket WHERE id BETWEEN 2000 AND 2039;

USE ops_knowledge;

INSERT INTO knowledge_base(id,name,description,status,create_by,update_by,deleted) VALUES
    (201,'生产故障处置手册','网关、订单、支付、库存等生产故障的标准处置 Runbook。','enable',1,1,0),
    (202,'中间件运维手册','MySQL、Redis、RabbitMQ、Nacos 和 Sentinel 运维规范。','enable',1,1,0),
    (203,'发布与变更规范','灰度发布、回滚、容量评估和变更审核规范。','enable',1,1,0),
    (204,'事故复盘与案例库','OpsAgent脱敏后的生产事故复盘及改进项。','enable',1,1,0)
ON DUPLICATE KEY UPDATE description=VALUES(description),status='enable',deleted=0;

USE ops_platform;

INSERT INTO platform_config(config_key,config_value,description,update_by) VALUES
    ('demo.company.name','OpsAgent 演示环境','演示业务背景，不对应真实公司',1),
    ('mq.document.parse.max-attempts','3','文档解析监听器最大尝试次数',1),
    ('mq.ticket.audit.consumer','platform-ticket-audit','平台工单审计幂等消费者名称',1)
ON DUPLICATE KEY UPDATE config_value=VALUES(config_value),description=VALUES(description),update_by=VALUES(update_by);
