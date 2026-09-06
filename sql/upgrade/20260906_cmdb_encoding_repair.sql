-- Repair only the exact encoding-corrupted values introduced by the isolated-demo migration.
-- Hash guards preserve later user edits; repeat execution is a no-op.
SET NAMES utf8mb4;
USE ops_platform;
START TRANSACTION;
UPDATE cmdb_ci SET ci_name='隔离订单演示服务' WHERE ci_code='ops-demo-order-service' AND SHA2(ci_name,256)='588bb7644e7b8f46b8d4d8a4c5840115f83f539c7026476209dd6bbff329e35c';
UPDATE cmdb_ci SET description='真实Redis业务请求、Nacos配置和Sentinel限流演练' WHERE ci_code='ops-demo-order-service' AND SHA2(description,256)='fc7331790a9df9fc0a87e835cc493d56de5d5f6570b88ca0914ff97658e8f05a';
UPDATE cmdb_ci SET ci_name='隔离演示Redis' WHERE ci_code='ops-demo-redis' AND SHA2(ci_name,256)='9d9138a01aebdba3f5c10baa3679f467baa79f09879e738f7a6386981c5ed367';
UPDATE cmdb_ci SET description='仅保存可重建订单目录，不承载OpsAgent会话或锁' WHERE ci_code='ops-demo-redis' AND SHA2(description,256)='1206e4c17c233351cdb02d324c1c4034bdb48d37450c3822d116dbbd0a256a98';
UPDATE cmdb_ci SET ci_name='Agent 执行服务' WHERE ci_code='ops-agent-service' AND SHA2(ci_name,256)='9c06ea61d93353f2098c7c33ad596f1e79af9b7cdfb6f27ef749c062bdf930f4';
UPDATE cmdb_ci SET description='持久化工作流、模型工具决策、精确审批和执行证据' WHERE ci_code='ops-agent-service' AND SHA2(description,256)='54941b1d0f21796d0ff3f60fb591da009fa4e9f914d9da923b5f842d04fd1062';
UPDATE cmdb_relation SET description='OPSAGENT_DEMO专用配置和服务注册' WHERE source_ci_code='ops-demo-order-service' AND target_ci_code='nacos' AND relation_type='DEPENDS_ON' AND SHA2(description,256)='e1d2815a22f94b58992180e637f5d7746a66acb536f5b6ebadf3ee59da9866e2';
UPDATE cmdb_relation SET description='真实订单目录读取' WHERE source_ci_code='ops-demo-order-service' AND target_ci_code='ops-demo-redis' AND relation_type='DEPENDS_ON' AND SHA2(description,256)='7e94e6b1c940b3d77df8468e71ab3f7323c865ae4bdf522a90159b057e7afbbc';
UPDATE cmdb_relation SET description='订单查询资源真实流量控制' WHERE source_ci_code='ops-demo-order-service' AND target_ci_code='sentinel' AND relation_type='DEPENDS_ON' AND SHA2(description,256)='6a62ff4cdbf963c23848541862f64ae011abbe26ced7ea0f9318dfc3ee37d745';
COMMIT;
