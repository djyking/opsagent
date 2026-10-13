# AI Agent 与真实故障闭环实施方案

## 已核实的问题

旧 HTTP 实验目标没有被 Prometheus 抓取，故障持续时间也不足以触发告警；现有告警按 fingerprint 永久复用工单，无法清楚区分后续故障。现有工作流是固定同步步骤，模型文本问答不能代表原生工具执行能力。访客的临时负用户 ID 与工单创建人过滤组合后，可见数据过少。

## 本轮设计

保留 Java 17、Spring Boot 3.5.16、Spring Cloud 2025.0.3、Alibaba 2025.0.0.0、现有安全/RAG/工单/运维中心与蓝白视觉体系。新增 ops-agent-service（8106，ops_ai 数据库）及 ops-demo-order-service（8110，独立 Redis），不部署另一套 Dify，也不升级整套依赖。

1. 独立订单预览服务真实读取 Redis，Nacos 专用配置驱动连接参数与 Sentinel 规则。提供 Redis 端口配置漂移和限流规则回退两个场景；实际业务请求产生 503/429。
2. 固定标签的真实业务探针经过 Prometheus 和 Alertmanager 形成告警 episode 与工单。按 fingerprint + startsAt 去重，恢复消息只影响自己的 episode。演练来源和所有者来自服务端场景登记。
3. 新自动化工作区统一目标场景、工作流版本、运行图、节点证据、审批和工具目录。工作流草稿经校验发布为不可变版本，运行保存定义、模型和工具快照。
4. Agent 通过 RAG 服务的原生 tool_calls / Responses function_call 进行多轮调用。模型只选择固定工具；执行器负责参数校验、当前权限、预算、精确审批及幂等。普通文本不会被解析成命令。
5. 数据库持久化 checkpoint、事件、工具意图、审批和 outbox；短租约及 fencing 防止并行推进，MQ 唤醒与数据库恢复扫描结合。等待审批时释放 worker。事件支持断线按序号补发。
6. 访客可浏览展示数据，发起受限演练并处理自己演练范围内的运行和审批；不授予管理员角色。异步动作每一步向 Auth 复核租约/角色。核心配置、密钥与账户权限不开放。

## 内部接口合同

内部端点不配置公网 Gateway 路由；使用独立 OPS_AGENT_INTERNAL_SECRET 签发短期 audience 凭证，和普通用户 JWT 分离。每一步复核当前身份与初始角色交集。

- Auth：GET /internal/agent/actors/{userId}。
- RAG：GET /internal/ai/models、POST /internal/ai/turns、POST /internal/rag/search。
- 工单：/internal/agent/tickets/{id}、history、ai-analyses、comments、work-records、transitions；/internal/agent/alerts/{episodeId}。
- 平台：/internal/platform/demo-targets/order/snapshot、scenarios、actions、incidents/{incidentId}。
- 公共自动化入口：/api/automation/**。

模型调用具有稳定 callId 和持久化回执；单次调用不隐藏重试。工具请求绑定 runId、callId、参数摘要、目标与版本。未知/不完整模型响应不执行动作。审批通过后执行原调用；不让模型重新生成审批参数。

## 执行顺序与验收

先并行实现真实目标、工单权限和模型协议，再接入执行服务及前端，最后执行完整 Maven 校验、前端构建、原生工具 probe 和公网闭环验收。部署前备份服务器数据及配置，增量迁移而非重导数据库；仅逻辑归档精确核验的旧 SQL 种子工单，并停止其 SLA 扫描。

必须区分配置发布成功、业务恢复与 Agent 修复成功。独立 TTL 守护用于清理过期故障，来源标记 TTL_GUARD，不能冒充 Agent 成果。禁止任意 Shell、SQL、URL、Docker socket 和核心中间件故障注入。默认手动启动演练，避免周期演练无意义消耗模型预算。

本轮是可部署的受控执行基础：图编辑采用结构化定义与校验、运行过程可视化；不宣称已实现通用拖拽低代码平台、任意插件市场或生产自主高危处置。
