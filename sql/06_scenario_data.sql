-- OpsAgent 可重复执行的业务演示数据。
-- 场景覆盖：并发接单、完整工单生命周期、缓存/配置/容量故障、知识检索和 Outbox 待投递事件。

USE ops_ticket;

INSERT INTO ticket(
    id, ticket_no, title, description, priority, status, creator_id, assignee_id,
    version, create_time, update_time, deleted
) VALUES
    (1001, 'OPS-SCENE-1001', '支付服务数据库连接池耗尽',
     '高峰期 active connection 达到上限，接口 P99 超过 8 秒。',
     'URGENT', 'CREATED', 3, NULL, 0, '2026-08-25 09:10:00', '2026-08-25 09:10:00', 0),
    (1002, 'OPS-SCENE-1002', 'Redis 缓存命中率骤降',
     '商品缓存命中率从 92% 降至 38%，数据库 QPS 同步升高。',
     'HIGH', 'ASSIGNED', 3, 2, 1, '2026-08-26 10:00:00', '2026-08-26 10:08:00', 0),
    (1003, 'OPS-SCENE-1003', 'Nacos 配置变更后实例不一致',
     '部分实例读取到旧限流阈值，需要核对 dataId、group 和配置刷新日志。',
     'HIGH', 'PROCESSING', 3, 2, 2, '2026-08-27 14:20:00', '2026-08-27 14:45:00', 0),
    (1004, 'OPS-SCENE-1004', '知识服务磁盘使用率超过 85%',
     '上传目录持续增长，需要清理孤立文件并规划对象存储迁移。',
     'MEDIUM', 'SUSPENDED', 3, 2, 3, '2026-08-28 11:30:00', '2026-08-28 12:00:00', 0),
    (1005, 'OPS-SCENE-1005', '网关接口出现间歇性 429',
     'Sentinel 规则触发频率异常，需确认调用峰值和热点参数。',
     'MEDIUM', 'WAITING_CONFIRM', 3, 2, 3, '2026-08-29 16:00:00', '2026-08-29 17:10:00', 0),
    (1006, 'OPS-SCENE-1006', '夜间批处理导致 CPU 持续升高',
     '02:00 至 02:20 CPU 达到 95%，线程池队列出现积压。',
     'HIGH', 'RESOLVED', 3, 2, 4, '2026-08-30 02:05:00', '2026-08-30 03:00:00', 0),
    (1007, 'OPS-SCENE-1007', '登录 Refresh Token 无法续期',
     '个别用户刷新令牌已撤销，客户端仍重复发起续期请求。',
     'LOW', 'CLOSED', 3, 2, 5, '2026-08-31 09:00:00', '2026-08-31 11:20:00', 0)
ON DUPLICATE KEY UPDATE
    title = VALUES(title), description = VALUES(description), priority = VALUES(priority),
    status = VALUES(status), creator_id = VALUES(creator_id), assignee_id = VALUES(assignee_id),
    version = VALUES(version), update_time = VALUES(update_time), deleted = 0;

INSERT INTO ticket_comment(id, ticket_id, user_id, content, create_time, update_time, deleted) VALUES
    (1001, 1002, 2, '已临时扩大热点缓存 TTL，正在观察数据库负载。',
     '2026-08-26 10:20:00', '2026-08-26 10:20:00', 0),
    (1002, 1003, 3, '确认两个实例读取到的限流值不同。',
     '2026-08-27 14:32:00', '2026-08-27 14:32:00', 0),
    (1003, 1006, 2, '已限制批处理线程池为 8 个工作线程，CPU 恢复正常。',
     '2026-08-30 02:45:00', '2026-08-30 02:45:00', 0)
ON DUPLICATE KEY UPDATE content = VALUES(content), update_time = VALUES(update_time), deleted = 0;

INSERT INTO ticket_history(
    id, ticket_id, operator_id, operation_type, from_status, to_status, remark, create_time
) VALUES
    (1001, 1002, 3, 'CREATE', NULL, 'CREATED', NULL, '2026-08-26 10:00:00'),
    (1002, 1002, 2, 'CLAIM', 'CREATED', 'ASSIGNED', '开始排查缓存指标', '2026-08-26 10:08:00'),
    (1003, 1003, 2, 'CLAIM', 'CREATED', 'ASSIGNED', NULL, '2026-08-27 14:25:00'),
    (1004, 1003, 2, 'PROCESSING', 'ASSIGNED', 'PROCESSING', '对比各实例配置', '2026-08-27 14:45:00'),
    (1005, 1006, 2, 'RESOLVED', 'PROCESSING', 'RESOLVED', '限制线程池并错峰执行', '2026-08-30 03:00:00'),
    (1006, 1007, 3, 'CLOSED', 'RESOLVED', 'CLOSED', '客户端升级后确认恢复', '2026-08-31 11:20:00')
ON DUPLICATE KEY UPDATE remark = VALUES(remark), create_time = VALUES(create_time);

INSERT INTO event_outbox(
    id, event_id, aggregate_type, aggregate_id, event_type, payload, status,
    retry_count, next_retry_time, create_time, update_time
) VALUES
    (1001, '00000000-0000-0000-0000-000000001001', 'TICKET', 1001,
     'ticket.created', JSON_OBJECT('ticketId', 1001), 'PENDING', 0,
     '2026-09-03 12:00:00', '2026-09-03 12:00:00', '2026-09-03 12:00:00'),
    (1002, '00000000-0000-0000-0000-000000001002', 'TICKET', 1006,
     'ticket.resolved', JSON_OBJECT('ticketId', 1006), 'PENDING', 0,
     '2026-09-03 12:00:00', '2026-09-03 12:00:00', '2026-09-03 12:00:00')
ON DUPLICATE KEY UPDATE payload = VALUES(payload), status = VALUES(status),
    retry_count = VALUES(retry_count), next_retry_time = VALUES(next_retry_time);

USE ops_knowledge;

INSERT INTO knowledge_base(id, name, description, status, create_by, update_by, deleted) VALUES
    (101, '数据库与连接池处置手册', 'MySQL、连接池与慢查询的标准排障流程。', 'enable', 1, 1, 0),
    (102, '缓存与配置中心手册', 'Redis、Nacos 和 Sentinel 常见异常处理。', 'enable', 1, 1, 0),
    (103, '主机与 JVM 运行手册', '磁盘、CPU、线程池和 JVM 指标处置。', 'enable', 1, 1, 0)
ON DUPLICATE KEY UPDATE description = VALUES(description), status = VALUES(status), deleted = 0;

INSERT INTO knowledge_document(
    id, knowledge_base_id, file_name, original_name, file_type, file_size, storage_path,
    status, content_hash, version, create_by, create_time, update_time, deleted
) VALUES
    (1001, 101, 'mysql-pool-runbook.md', 'MySQL连接池排障手册.md', 'md', 2048,
     'scenario/mysql-pool-runbook.md', 'PARSED',
     '1111111111111111111111111111111111111111111111111111111111111111', 1, 1,
     '2026-08-20 09:00:00', '2026-08-20 09:05:00', 0),
    (1002, 102, 'redis-nacos-runbook.md', 'Redis与Nacos排障手册.md', 'md', 3072,
     'scenario/redis-nacos-runbook.md', 'PARSED',
     '2222222222222222222222222222222222222222222222222222222222222222', 1, 1,
     '2026-08-21 09:00:00', '2026-08-21 09:05:00', 0),
    (1003, 103, 'jvm-thread-runbook.md', 'JVM线程池排障手册.md', 'md', 2560,
     'scenario/jvm-thread-runbook.md', 'PARSED',
     '3333333333333333333333333333333333333333333333333333333333333333', 1, 1,
     '2026-08-22 09:00:00', '2026-08-22 09:05:00', 0)
ON DUPLICATE KEY UPDATE original_name = VALUES(original_name), status = VALUES(status),
    content_hash = VALUES(content_hash), deleted = 0;

INSERT INTO knowledge_chunk(
    id, document_id, chunk_index, content, token_count, embedding_status, page_number, metadata_json
) VALUES
    (1001, 1001, 0,
     '数据库连接超时时，先检查 Hikari active、idle、pending 指标，再核对 MySQL max_connections 与慢查询。不要直接无限扩大连接池。',
     42, 'PENDING', 1, JSON_OBJECT('scenario', 'mysql-pool-exhausted')),
    (1002, 1001, 1,
     '连接池耗尽的临时措施包括限制入口流量、终止异常长事务和降低慢 SQL 并发；恢复后应补充容量评估。',
     38, 'PENDING', 1, JSON_OBJECT('scenario', 'mysql-pool-recovery')),
    (1003, 1002, 0,
     'Redis 命中率下降时，核对 key 过期是否集中、淘汰策略、内存碎片率与回源 QPS，避免缓存雪崩。',
     35, 'PENDING', 1, JSON_OBJECT('scenario', 'redis-hit-rate')),
    (1004, 1002, 1,
     'Nacos 配置不一致时，确认 dataId、group、namespace 和客户端长轮询日志，并比较各实例 actuator info。',
     37, 'PENDING', 1, JSON_OBJECT('scenario', 'nacos-config-drift')),
    (1005, 1003, 0,
     'CPU 升高并伴随线程池队列积压时，采集线程转储，检查活跃线程、队列长度、拒绝次数和下游响应时间。',
     39, 'PENDING', 1, JSON_OBJECT('scenario', 'thread-pool-backlog')),
    (1006, 1003, 1,
     'Java 17 使用有界 ThreadPoolExecutor；虚拟线程在 Java 21 正式发布，迁移后仍需限制数据库等稀缺资源的并发。',
     41, 'PENDING', 1, JSON_OBJECT('scenario', 'java-21-virtual-thread'))
ON DUPLICATE KEY UPDATE content = VALUES(content), token_count = VALUES(token_count),
    embedding_status = VALUES(embedding_status), metadata_json = VALUES(metadata_json);

INSERT INTO document_parse_task(
    id, document_id, status, retry_count, next_retry_time, error_message, create_time, update_time
) VALUES
    (1001, 1001, 'SUCCESS', 0, NULL, NULL, '2026-08-20 09:00:00', '2026-08-20 09:05:00'),
    (1002, 1002, 'SUCCESS', 0, NULL, NULL, '2026-08-21 09:00:00', '2026-08-21 09:05:00'),
    (1003, 1003, 'SUCCESS', 0, NULL, NULL, '2026-08-22 09:00:00', '2026-08-22 09:05:00')
ON DUPLICATE KEY UPDATE status = VALUES(status), retry_count = VALUES(retry_count),
    error_message = VALUES(error_message), update_time = VALUES(update_time);

USE ops_platform;

INSERT INTO system_announcement(id, title, content, status, create_by, create_time, update_time) VALUES
    (1001, '本地演练环境说明', '当前数据仅用于 OpsAgent 故障处理与检索演示。', 'PUBLISHED', 1,
     '2026-09-01 09:00:00', '2026-09-01 09:00:00')
ON DUPLICATE KEY UPDATE content = VALUES(content), status = VALUES(status), update_time = VALUES(update_time);

INSERT INTO platform_config(id, config_key, config_value, description, update_by) VALUES
    (1001, 'ticket.claim.concurrent-workers', '8', '并发接单自动化测试工作线程数', 1),
    (1002, 'knowledge.search.fallback', 'mysql-like', '未接 Elasticsearch 时使用数据库文本检索', 1),
    (1003, 'runtime.java.version', '17', '当前生产基线；虚拟线程迁移目标为 Java 21+', 1)
ON DUPLICATE KEY UPDATE config_value = VALUES(config_value), description = VALUES(description),
    update_by = VALUES(update_by);

INSERT INTO operation_audit(
    id, service_name, biz_type, biz_id, operation, user_id, trace_id, request_id, detail_json, create_time
) VALUES
    (1001, 'ops-ticket-service', 'TICKET', '1002', 'CLAIM', 2,
     'scene-trace-1001', 'scene-request-1001', JSON_OBJECT('version', 0, 'result', 'success'),
     '2026-08-26 10:08:00'),
    (1002, 'ops-knowledge-service', 'DOCUMENT', '1003', 'PARSE', 1,
     'scene-trace-1002', 'scene-request-1002', JSON_OBJECT('chunks', 2, 'result', 'success'),
     '2026-08-22 09:05:00')
ON DUPLICATE KEY UPDATE detail_json = VALUES(detail_json), create_time = VALUES(create_time);
