-- Register configured dependencies only. These rows do not claim runtime calls or health.
SET NAMES utf8mb4;
USE ops_platform;
INSERT IGNORE INTO cmdb_ci(ci_code,ci_name,ci_type,environment,owner_name,status,description) VALUES
('qdrant','Qdrant','VECTOR_DATABASE','PROD','AI 组','ACTIVE','知识向量检索；运行指标需另行绑定采集'),
('sentinel','Sentinel','GOVERNANCE','PROD','平台组','ACTIVE','应用流控、熔断及动态规则治理'),
('grafana','Grafana','MONITOR','PROD','SRE','ACTIVE','基于 Prometheus 的指标可视化'),
('external-llm','外部 LLM API','EXTERNAL_API','PROD','AI 组','ACTIVE','当前配置选定的大模型服务，不暴露凭据'),
('embedding-api','Embedding API','EXTERNAL_API','PROD','AI 组','ACTIVE','已配置的向量模型接口，不代表当前调用成功');
INSERT IGNORE INTO cmdb_relation(source_ci_code,target_ci_code,relation_type,description) VALUES
('ops-gateway','ops-platform-service','ROUTES_TO','平台接口路由'),
('ops-gateway','ops-agent-service','ROUTES_TO','自动化接口路由'),
('ops-knowledge-service','mysql','WRITES_TO','文档元数据与审核记录'),
('ops-knowledge-service','rabbitmq','PUBLISHES_TO','文档解析任务'),
('ops-knowledge-service','qdrant','WRITES_TO','知识向量索引'),
('ops-knowledge-service','embedding-api','CALLS','已配置的文档向量化能力'),
('ops-rag-service','qdrant','READS_FROM','授权向量检索'),
('ops-rag-service','elasticsearch','READS_FROM','BM25 混合检索'),
('ops-rag-service','external-llm','CALLS','检索增强回答生成'),
('ops-rag-service','sentinel','MONITORED_BY','资源统计与持久化规则治理'),
('ops-agent-service','external-llm','CALLS','工作流模型节点'),
('ops-agent-service','ops-platform-service','CALLS','受控现场取证与修复工具'),
('ops-agent-service','ops-ticket-service','CALLS','事件与恢复验证'),
('grafana','prometheus','READS_FROM','读取观测时序'),
('prometheus','alertmanager','PUBLISHES_TO','告警路由'),
('alertmanager','ops-ticket-service','CALLS','活动告警关联事件');
