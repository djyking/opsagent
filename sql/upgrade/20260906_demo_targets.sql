-- Incremental isolated demonstration tables in ops_platform; preserves all existing data.
SET NAMES utf8mb4;
USE ops_platform;
CREATE TABLE IF NOT EXISTS operations_demo_incident (
    incident_id VARCHAR(36) PRIMARY KEY,
    target_code VARCHAR(64) NOT NULL,
    scenario_code VARCHAR(64) NOT NULL,
    owner_id BIGINT NOT NULL,
    owner_name VARCHAR(128) NOT NULL,
    owner_kind VARCHAR(16) NOT NULL,
    status VARCHAR(32) NOT NULL,
    expected_revision VARCHAR(64) NOT NULL DEFAULT '',
    started_at TIMESTAMP(3) NOT NULL,
    expires_at TIMESTAMP(3) NOT NULL,
    recovered_at TIMESTAMP(3) NULL,
    recovery_source VARCHAR(32) NOT NULL DEFAULT '',
    last_http_status INT NOT NULL DEFAULT 0,
    last_reason VARCHAR(64) NOT NULL DEFAULT '',
    last_observed_at TIMESTAMP(3) NULL
);
CREATE TABLE IF NOT EXISTS operations_demo_target_lease (
    target_code VARCHAR(64) PRIMARY KEY,
    active_incident VARCHAR(36) NULL,
    available_after TIMESTAMP(3) NULL
);
CREATE TABLE IF NOT EXISTS operations_demo_action (
    idempotency_key VARCHAR(160) PRIMARY KEY,
    incident_id VARCHAR(36) NOT NULL,
    action_code VARCHAR(40) NOT NULL,
    expected_revision VARCHAR(64) NOT NULL,
    actor_id BIGINT NOT NULL,
    run_id VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL,
    finished_at TIMESTAMP(3) NULL,
    result_json TEXT NULL
);
INSERT IGNORE INTO cmdb_ci(ci_code,ci_name,ci_type,environment,owner_name,endpoint,status,description)
VALUES ('ops-demo-order-service','隔离订单演示服务','SERVICE','DEMO','OpsAgent',
        'http://ops-demo-order-app:8110','ACTIVE','真实Redis业务请求、Nacos配置和Sentinel限流演练'),
       ('ops-demo-redis','隔离演示Redis','CACHE','DEMO','OpsAgent',
        'demo-redis:6379','ACTIVE','仅保存可重建订单目录，不承载OpsAgent会话或锁');
INSERT IGNORE INTO cmdb_relation(source_ci_code,target_ci_code,relation_type,description)
VALUES ('ops-demo-order-service','ops-demo-redis','DEPENDS_ON','真实订单目录读取'),
       ('ops-demo-order-service','nacos','DEPENDS_ON','OPSAGENT_DEMO专用配置和服务注册'),
       ('ops-demo-order-service','sentinel','DEPENDS_ON','订单查询资源真实流量控制');
INSERT IGNORE INTO cmdb_ci(ci_code,ci_name,ci_type,environment,owner_name,endpoint,status,description)
VALUES ('ops-agent-service','Agent 执行服务','SERVICE','PROD','OpsAgent',
        'http://ops-agent-app:8106','ACTIVE','持久化工作流、模型工具决策、精确审批和执行证据');
