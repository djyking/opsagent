# 隔离订单演示目标

Java 17 / Spring Boot 3.5.16。独立Redis中的目录读取构成真实业务请求；没有MySQL、MQ、LLM依赖，也没有Docker控制接口。

## 运行配置

- OPS_DEMO_CONTROL_TOKEN：至少32字符随机值，仅平台和目标共享，不能提交Git。
- OPS_DEMO_REDIS_HOST：独立Redis容器名，默认demo-redis。
- OPS_DEMO_REDIS_PASSWORD：仅独立Redis密码，不复用正式会话Redis。
- NACOS_SERVER_ADDR：现有Nacos 3.0.3地址，默认localhost:8848。
- OPS_DEMO_NACOS_NAMESPACE：可选已有namespace，默认public空值。
- OPS_DEMO_NACOS_ENABLED：默认true，false仅用于不连接中间件的测试。
- 专用配置固定为group=OPSAGENT_DEMO，dataId=ops-demo-order-runtime.json。
- 专用Sentinel资源固定为ops-demo-order-query，正常QPS=5，故障QPS=0。

目标服务8110端口只在Docker内部开放。镜像可复用根Dockerfile，APP_MODULE=ops-demo-order-service。
建议512MiB上限，Java参数：
`-Xms32m -Xmx192m -XX:+UseSerialGC -XX:ActiveProcessorCount=2 -XX:MaxDirectMemorySize=48m -XX:ReservedCodeCacheSize=48m`。
Docker healthcheck使用 `http://localhost:8110/actuator/health/liveness`，故障时管理端点仍存活。

独立Redis建议64MiB上限、16MiB maxmemory，无AOF和快照，只保存可重建演示目录。
无需挂载Docker socket、发布宿主机端口或提供正式业务数据库凭据。
目标以非root运行，read_only，cap_drop ALL；为Nacos日志和快照提供有界/tmp及用户日志目录tmpfs。

## 真实故障

- NACOS_REDIS_CONFIG_DRIFT：专用Nacos配置将实际Redis连接端口从6379改为固定6380。真实订单读取发生连接失败，HTTP503。
- SENTINEL_RULE_REGRESSION：专用配置加载到FlowRuleManager，真实SphU.entry拦截订单查询，HTTP429。
- 业务：GET /demo/orders/preview。成功必须完成真实Redis GET。
- 内部控制：GET /internal/demo/snapshot，POST /internal/demo/scenarios，POST /internal/demo/actions。
- 所有内部控制端点验证X-Demo-Control-Token；外部工具不能选择任意主机、端口、配置键和命令。
- 故障期限180～900秒（平台默认600秒）；目标自身每秒检查期限，Agent/平台退出不影响TTL恢复。
- TTL_GUARD、MANUAL和AGENT_TOOL分别标记，不能把安全恢复或人工恢复认定为Agent修复。

## 平台与部署

平台新增OPS_DEMO_TARGET_URL=http://ops-demo-order-app:8110、OPS_DEMO_CONTROL_TOKEN、
OPS_AGENT_INTERNAL_SECRET、OPS_AUTH_INTERNAL_URL=http://ops-auth-app:8101。
执行sql/upgrade/20260906_demo_targets.sql；平台初始化器也会幂等创建3张演练表，但CMDB种子由迁移写入。
平台每5秒执行固定真实HTTP业务探针。现有Prometheus/Alertmanager规则会创建真实告警工单。
没有周期造故障任务，演练仅手动创建，同一目标最多一个活动故障；每用户每小时最多6次。

平台公共接口GET/POST /api/platform/operations/demo/scenarios、GET /api/platform/operations/demo/target。
POST /api/platform/operations/demo/actions允许所属用户执行固定人工恢复，仍复核实时身份。
恢复DTO为incidentId/action/expectedRevision/idempotencyKey；action只允许RESTORE_CONFIGURATION或RESTORE_FLOW_RULE。
快照含business.httpStatus、business.reasonCode、business.consecutiveSuccesses、observedAt和配置版本。
内部Agent合同位于/internal/platform/demo-targets/order下的snapshot、scenarios、actions、incidents/{incidentId}。
告警归属接口incident-owner以targetCode/startsAt解析已持久化演练；仅接受专用SYSTEM签名身份，返回ownerId等必要字段。

访客创建、恢复和查看具体演练受所有权约束。内部请求使用aud=platform的短期服务令牌，
通过Auth重新复核当前用户和租约；高风险审批由Agent Runtime执行，平台再次校验目标和所有权。
