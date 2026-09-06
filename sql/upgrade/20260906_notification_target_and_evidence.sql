-- Incremental evidence storage and isolated notification catalog; preserves all existing records.
SET NAMES utf8mb4;
USE ops_platform;
CREATE TABLE IF NOT EXISTS operations_demo_observation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    target_code VARCHAR(64) NOT NULL,
    incident_id VARCHAR(36) NOT NULL,
    observed_at TIMESTAMP(3) NOT NULL,
    http_status INT NOT NULL,
    reason_code VARCHAR(64) NOT NULL,
    recovery_source VARCHAR(32) NOT NULL,
    revision VARCHAR(64) NOT NULL,
    snapshot_json TEXT NOT NULL,
    INDEX idx_demo_observation_incident (incident_id, observed_at),
    INDEX idx_demo_observation_target (target_code, observed_at)
);
CREATE TABLE IF NOT EXISTS operations_demo_runtime_change (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    target_code VARCHAR(64) NOT NULL,
    revision VARCHAR(64) NOT NULL,
    occurred_at TIMESTAMP(3) NOT NULL,
    observed_at TIMESTAMP(3) NOT NULL,
    kind VARCHAR(40) NOT NULL,
    before_json TEXT NOT NULL,
    after_json TEXT NOT NULL,
    UNIQUE KEY uq_demo_runtime_revision (target_code, revision),
    INDEX idx_demo_runtime_time (target_code, occurred_at)
);
INSERT IGNORE INTO cmdb_ci(ci_code,ci_name,ci_type,environment,owner_name,endpoint,status,description)
VALUES ('ops-demo-notification-service','隔离通知演示服务','SERVICE','DEMO','OpsAgent',
        'http://ops-demo-order-app:8110/demo/notifications/preview','ACTIVE',
        '真实RabbitMQ消息发布、消费确认和隔离Redis回执读回；消费者暂停产生可恢复积压'),
       ('ops-demo-rabbitmq','隔离通知RabbitMQ','MESSAGE_QUEUE','DEMO','OpsAgent',
        'demo-rabbitmq:5672','ACTIVE','独立通知broker和notifications虚拟主机，不承载平台公共消息');
INSERT IGNORE INTO cmdb_relation(source_ci_code,target_ci_code,relation_type,description)
VALUES ('ops-demo-notification-service','ops-demo-rabbitmq','DEPENDS_ON','固定通知队列发布、消费与确认'),
       ('ops-demo-notification-service','ops-demo-redis','DEPENDS_ON','通知投递回执写入和读回核验'),
       ('ops-demo-notification-service','nacos','DEPENDS_ON','通知消费者独立配置、TTL恢复与服务注册');
UPDATE cmdb_ci
SET description='仅保存可重建订单目录与短期通知回执，不承载OpsAgent会话或锁'
WHERE ci_code='ops-demo-redis' AND description='仅保存可重建订单目录，不承载OpsAgent会话或锁';
