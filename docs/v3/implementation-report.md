# OpsAgent V3 实施报告

本轮依据用户提供的 V3 交付包与实施总纲，在既有正式工程增量实现。当前工作区含此前已授权未提交修改，Git HEAD 不能代表运行版本。运行验收及最终镜像对应关系见同目录验收报告；未核实项不以本实现清单代替验收。

## 产品与实现映射

| 问题 / 阶段 | 实际改动 | 主要实现 |
| --- | --- | --- |
| U-01 助手入口 | 明确 AI 助手文字、账号/版本级首次引导、可取消上下文；打开入口不自动调用模型 | `ops-web/src/components/ai`、`stores/ai-assistant.ts` |
| U-02 导航 | 移除外壳 out-in 离场时序冲突；沿用路由归属、面包屑和深链上下文 | `layouts/AppLayout.vue`、`utils/route-navigation.ts` |
| U-03 拓扑可读性 | 保留 G6 5.0.49；分组、治理边筛选、一/二跳邻域、真实节点类型与只读观测关系 | `components/observability`、`utils/topology-view.ts` |
| U-04 图交互 | 分离结构、指标与视口；指标刷新不 setData/render/fit；用户主动适应画布，保存账号/环境视口 | `stores/observability.ts`、图组件与定向渲染测试 |
| U-05 监控/巡检 | RabbitMQ/Nacos/Qdrant 等真实接入，独立生命周期/观测/业务健康；新鲜时间与指标单位；巡检持久租约、执行和结果分开 | `NodeObservationService`、`PrometheusAdapter`、`InspectionExecutionRepository` |
| U-06 配置 | 展示真实来源完整身份和能力；区分配置片段与运行有效值；白名单差异提案→原 AgentRun 精确审批→CAS→源及实例应用核验 | `ConfigCenter*`、`ConfigurationProposal*`、`ConfigurationRunController` |
| U-07 首页 | 真实拓扑成为主要态势区，覆盖率给出分母，最多五项优先行动，搜索/创建仍可直达 | `DashboardView.vue`、`DashboardTopology.vue` |
| P3-1 调用关系 | OTel Java Agent→Collector→Tempo；仅 Collector 生成服务图，沿用现有 Prometheus；登记/观测/匹配关系分开 | `deploy/public/config/otel`、`TraceEvidenceAdapter` |
| P3-2 实例 | JVM 启动生成实例 ID，展示真实资源/父子 Span/Links；记录 first/lastSeen；订单与通知共享进程明确说明 | `ObservabilityV3Repository`、实例/Trace 页内面板 |
| P3-3 历史 | 后台每分钟不可变拓扑摘要、72 小时清理；历史模式停实时刷新，断档不补造，固定事件证据独立保留 | `ObservabilityHistoryRecorder`、`ObservabilityV3Service` |
| P3-4 差异 | 区分实际未登记、登记未观测、身份未解析、数据不足；管理员核对/限时忽略；不自动改 CMDB | `/api/platform/observability/v3/differences` |
| P3-5 诊断闭环 | 后端重鉴权采集并冻结证据包；RAG/Agent传对象引用；事实/假设/缺口、证据ID与采样时间；复用原审批、幂等、确定性恢复和知识审核 | `DiagnosticEvidence*`、RAG 证据服务、`AgentTools`/`AgentContext`/`AgentRuntime` |

## 关键真实性约束

- `up=0` 代表抓取失败；不能单凭它断言业务宕机。业务探针新鲜成功可与采集失败同时存在。
- `up=1` 不足以证明业务健康，缺少必需指标不能算完整覆盖。Sentinel 的 Block 单项不直接触发业务降级。
- 图上采样配对速率标为采样调用对/秒，不冒充全量 RPS；聚合窗口不能冒充首末实际调用时间。
- 查询固定上游、有限窗口和响应大小；不提供任意 URL/TraceQL/SQL/文件代理。Span 不返回头、正文、SQL、提示词和密钥。
- 配置只有 `APPLIED` 才结束应用验证；`PUBLISHED/UNCONFIRMED/CONFLICT` 保留原运行、提案和审批，进入待核验。恢复对账沿用原幂等意图。
- AI 不能把演练模板当根因证据；`SUPPORTED` 必须引用本运行实际证据ID，模型不能自称人工确认。TTL 恢复、人工恢复与 Agent 工具修复分开记录。
- Compose 只有真实进程/容器，未伪造 Pod；普通拓扑历史从实际启用时刻开始。

## 迁移与部署差异

兼容迁移：`sql/upgrade/20260906_configuration_proposals.sql`、`20260906_observability_v3.sql`。巡检新增表由原 `ObservabilitySchemaInitializer` 幂等建表，不引入第二调度器。原数据库/卷/账号保持。

新增组件仅 Collector 0.160.0 与 Tempo 2.10.8，镜像摘要锁定；Java Agent 2.31.1 二进制 SHA-256 锁定。无新增公网中间件端口、Docker socket、Kubernetes、Loki 或图数据库。

初始灰度为网关、平台、Agent、RAG 与隔离订单。PROD 根请求采样 10%，DEMO 根请求采样 100%，子请求继承父决策。五个 JVM 继续使用原堆限制，新增采集队列与 Trace 留存受限。最终实测数据和边界以验收报告为准。
