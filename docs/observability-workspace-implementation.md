# 服务拓扑与可观测工作台实施记录

基线：dev / 54449a8。正式源码：D:/myselfProject/opsagent。

本轮按导航修复、拓扑、配置与流量治理、巡检与 AI、大屏及真实链路验证的顺序渐进改造。沿用既有五个产品入口及事件工作区，不复制告警、审批、自动化或知识事实源。

## 产品变化

- 服务与观测默认进入拓扑总览，提供服务目录、配置中心、流量治理、持续巡检五个内部入口。
- 全局路由增加模块归属、面包屑与确定的父级返回。原始告警、知识审核、索引管理、SLA、值班等深链保持对应主菜单选中。旧链接保留兼容。
- 拓扑使用 G6 定向图、类型图标、节点健康、真实 RED 与实例指标、搜索、环境和时间窗口、异常过滤、缩放/适应画布和布局保存。默认按服务类型排序为五列网格，避免全系统长链布局被适应画布缩得过小。管理员可维护节点、绑定与关系，其他角色只读。
- 节点详情抽屉保留服务上下文，连接指标、配置、流量、活动告警、关系与演练恢复记录。进入完整告警页面仍带服务筛选。
- 配置中心标记 Nacos 与部署管理来源，提供脱敏预览、版本与差异；可变业务配置复用现有受控发布与回退。没有开放宿主机文件编辑。
- 流量治理接入真实 RAG Sentinel 资源，覆盖完整同步/流式生命周期。Flow、Degrade、System 的受控变更持久化到 Nacos，再核对客户端加载状态，记录版本与审计。
- 持续巡检显示本服务检查结果、连续失败、历史和当前观测。复用已有健康巡检工作流，追加服务级证据快照；支持手动只读检查。计划仍由部署配置和原自动化任务管理。
- 顶部 AI、右下浮球与完整问答共享一个会话状态。发送前冻结当前服务/事件上下文，按当前账号读取服务与流量快照；标识、来源、采样时间和证据不足说明一起进入只读分析。
- CMDB 意图判断只读取用户问题本身，剥离自动附加的页面上下文；含诊断、异常、分析、证据、健康、故障、原因或核对等意图时，不再因上下文中的“服务/依赖/拓扑”误走目录查询。明确的服务清单、目录与依赖问题仍使用真实 CMDB 只读事实。
- 大屏复用拓扑数据和组件，自动刷新，展示服务健康和活动告警，保持浅色风格和只读行为。

## 状态与数据真实性

CMDB 登记启用不等于运行健康。节点采用 HEALTHY/DEGRADED/CRITICAL/UNKNOWN/MAINTENANCE；演练用独立 drilling 标记叠加，不能覆盖真实故障状态。Prometheus 与 Alertmanager 独立降级，缺失指标为 null；告警读取失败不是零告警。

RPS、错误率、P95 来自 Prometheus。错误率、CPU 和堆使用率单位均为百分数。P95 只在存在直方图数据时展示；无请求基数时错误率未知。订单和通知另结合新鲜真实业务探针，历史恢复不替代当前健康。

健康检查使用 `up[90s]`（窗口可配置）的最后一个真实采样点，不使用 instant query 的求值时间冒充采样时间；采样过期后变为未知。短缓存消费时仍会再检查样本年龄。

完整 RAG 请求的平均 RT 只在当前窗口存在完成样本时返回；已创建 Sentinel 节点但请求尚未完成时为 null。`ops-rag-ask` 仅表示入口校验，不展示为模型响应耗时；`ops-rag-request` 覆盖同步及会话 SSE 的实际生命周期，成功、失败、超时、断开及会话持久化异常均结束对应 Entry。

关系来源为 CONFIGURED；HYBRID 当前叠加 CMDB 与运行指标，OBSERVED 明确显示暂无运行时关系。没有 Trace 时不生成边流量、延迟或动态调用关系。新登记 Qdrant、Sentinel、Grafana、外部 LLM 与 Embedding 等实际依赖，未绑定采集的中间件显示待观测。

## API 与路由

新增观测 API 前缀 `/api/platform/observability`：

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET | /topology、/wallboard | 服务、关系、来源、布局与当前告警聚合 |
| GET | /services/{ciCode} | 节点指标及按权限取得的关联摘要 |
| PUT | /topology/layout | ADMIN 保存共享环境布局 |
| GET | /inspections | 当前服务检查列表及今日真实检查次数 |
| GET | /inspections/{ciCode}/history | 最近 50 次检查证据 |
| POST | /inspections/{ciCode}/run | OPS/ADMIN/DEMO 执行一次只读检查 |

CMDB 增加 `DELETE /api/platform/cmdb/cis/{id}` 和 `PUT /api/platform/cmdb/relations/{id}`。节点与关系删除为逻辑删除，保留原始记录；CI 编码作为已有事件稳定关联标识，不允许重命名。新增绑定、所属系统和标签字段。

配置与流量 API：

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET | /api/platform/config-center、/summary | 配置目录与服务摘要 |
| GET | /api/platform/config-center/{id}、/{id}/history、/{id}/diff | 脱敏配置、历史与差异 |
| GET | /api/platform/traffic、/summary | Sentinel 资源和服务摘要 |
| GET | /api/platform/traffic/rules/{type}、/history | 规则、来源与发布历史 |
| POST | /api/platform/traffic/rules/{type}/validate、/publish、/rollback | ADMIN 校验、CAS 发布、受控回退 |
| GET | /api/rag/runtime/traffic | 认证只读的 RAG Sentinel 运行快照 |

具体请求定义见对应 Controller 与前端 API 文件。

新增路由：`/observability/topology`、`/observability/catalog`、`/observability/config`、`/observability/traffic`、`/observability/inspections`、`/observability/wallboard`。

原受控配置页面迁入 `/observability/config/managed`；原 `/configurations`、`/itsm/cmdb`、`/system/monitor` 等入口保留兼容，并明确父模块。

服务内 `DemoAccessPolicy` 同步补齐观测、配置目录/脱敏预览/历史、流量只读与 RAG 运行快照的 GET 白名单，以及一次性只读巡检 POST。白名单没有开放 CMDB、共享布局、配置或流量规则修改；ADMIN 方法权限及服务层角色检查继续生效。云端已核对 DEMO 可读、规则校验/发布/回退均为 HTTP 403 / 40300。

`/observability/metrics` 保留原真实历史趋势、采集目标和证据表，复用原实现的指标专用模式；从拓扑和节点指标页进入，带服务上下文。没有把旧版趋势功能隐藏或替换成静态图。

## 数据库、依赖与配置

- `sql/upgrade/20260906_observability_workspace.sql`：观测元数据、关系逻辑删除标记、共享布局、巡检结果四张辅助表。
- `sql/upgrade/20260906_observability_catalog.sql`：幂等补全已配置的核心依赖，不覆盖用户修改。
- `sql/upgrade/20260906_traffic_governance.sql`：流量治理变更审计表，启动时也会幂等初始化，保留既有 Nacos 和配置历史。
- 前端新增 `@antv/g6`，继续使用现有 ECharts，不增加第二套趋势图库。
- Platform 增加 `OPS_ALERTMANAGER_URL`、`OPS_OBSERVABILITY_ERROR_RATE_THRESHOLD`（默认 5%）、`OPS_OBSERVABILITY_P95_THRESHOLD_MS`（默认 1000ms）、`OPS_OBSERVABILITY_SAMPLE_MAX_AGE_SECONDS`（默认 90s）。公共 Compose 使用内部 `http://alertmanager:9093`。
- RAG 新增 Nacos Degrade/System 规则动态数据源，Flow 保留原数据源。密钥继续来自部署环境，不返回到前端或加入源码包。
- 拓扑与告警短缓存 10 秒，前端通常每 15 秒刷新，离开页面停止；配置手动刷新。没有增加常驻中间件。

## 本轮范围之外

- OpenTelemetry/SkyWalking 调用发现、实例/Pod 下钻、历史拓扑回放属于文档 P3。当前没有 Trace 数据源，OBSERVED 返回空运行时关系并给出说明；不把 CMDB 边作为已采集调用链。
- 未配置 exporter、缺少抓取绑定或缺少真实样本的中间件仍显示 UNKNOWN/待观测；未采集到的 P95、调用量及健康实例不能填零或推测健康。
- ParamFlow 缺少稳定热点参数，Authority 缺少可信调用方来源；链路流控模式缺少稳定调用上下文，均不开放无效规则发布。当前真实纳管 Sentinel 服务是 RAG，其他 CI 明确返回 NOT_INTEGRATED 与 null 指标。
- Degrade/System 的动态源与受控发布代码已接入，但云端验收时未配置具体规则。验收保持原有空规则，没有为制造“启用成功”记录而新增或收紧策略；因此本轮线上发布/回退证明针对已有非空 Flow 规则。
- OPS 没有可用的真实线上会话，线上 OPS 权限验收明确记为 NOT_EXECUTED；本地服务层角色拒绝测试通过，不能据此声称已完成线上 OPS 验证。没有为了测试创建用户或更改角色。
- 配置未纳管的部署文件保持只读。演示链路沿用隔离订单/通知真实目标，未对承载公众问答的 RAG 服务直接注入高延迟故障。Golden Path 的另一独立访客会话隔离检查因无有效第二会话跳过，公开历史事件越权检查通过；不把此跳过写成通过。

## 验证与发布

以下时间除明确标注 UTC 外均为北京时间（UTC+8）。本节依据已有构建与验收产物整理，文档更新没有重新执行测试或部署。

### 构建与回归

| 验证 | 结果 | 证据 |
| --- | --- | --- |
| 全模块 `clean verify` | 2026-09-06 18:54:38 BUILD SUCCESS；95 个测试套件、508 项：507 通过、1 项可选外部实时检查跳过、0 失败/错误；Checkstyle 通过 | `data/public-deploy/observability-clean-verify.log`、`observability-test-summary.json` |
| 前端全量测试 | 15 个脚本全部通过；包括导航、共享 AI、上下文隔离、配置/流量响应乱序、事件与审批、来源与流式响应 | `data/public-deploy/observability-frontend-regressions.log` |
| 拓扑网格布局回归 | 实际 G6 GridLayout：23 节点、33 边、五列五行，边界 1128×728；保守 1440 视口适应比例约 70.3%；既有交互和只读契约通过 | `data/public-deploy/observability-grid-test.log` |
| 类型与生产构建 | `vue-tsc -b` 和 Vite 生产构建通过；最后一项来源栏 JSON 展示修复已构建 | `data/public-deploy/observability-web-final-build.log` |
| 公网工作台 API | 11/11 通过：真实拓扑/环境/来源语义、服务目录与详情、配置/流量、DEMO 可读与修改拒绝、一次只读巡检及历史 | `data/public-deploy/observability-public-smoke-final.json` |
| 公网 Sentinel / Nacos | 8/8 通过：真实三类规则源一致性、缺失指标边界、DEMO 403、同规则 Flow 发布/版本冲突/幂等/审计/回退 | `data/public-deploy/traffic-public-acceptance-final.json` |

跳过的后端用例来自 `OfficialDocsSearchTest` 的可选实时外部检查；不是失败或已执行通过。图形依赖按路由懒加载，构建仍提示单个 G6 块较大（约 396 KB gzip），该提示未阻止构建；没有把适应比例测试当成浏览器全量截图证明。

2026-09-06 18:32:43–18:32:59，线上 Flow 以完全相同的有效规则执行 CAS 发布（operation #1）和回退（operation #2，指向 #1）；两次均 APPLIED，REQUESTED/APPLIED 审计存在，同请求 ID 返回同一操作，错误版本返回 40900。发布前后有效规则指纹相同，没有调整业务限额。Degrade/System 保持 NOT_CONFIGURED，未新增规则源。此验收没有 LLM 调用、压测或故障注入。

18:33:18–18:33:27 的工作台 API 采样返回 23 个 CI、33 条登记关系，配置来源共 24 项（Nacos 10 项）；RAG 2 个真实 Sentinel 资源。只读巡检新记录 #47 为 SUCCESS，并可在历史中查询。该窗口与已授权通知演练并行，存在 1 条 DEMO 活动告警及对应 CRITICAL 节点，属于实际现场状态，不是工作台接口失败。

### 隔离通知 Golden Path

验收目录：`data/public-deploy/observability-goldenpath-smoke-notification-2026-09-06T10-32-47-864Z-18b47c07`。

| 关联对象 | 实际结果 |
| --- | --- |
| 隔离目标 | `ops-demo-notification-service` |
| Incident | `c9f3fcaa-020e-47c8-9472-8ecda73dd5cd` |
| 工单 | #2071，最终 RESOLVED |
| Agent Run | `ab168270-d394-4c3a-b491-0e5ac207525b`，最终 COMPLETED |
| 修复闭环 | 真实队列/消费者/配置证据 → 模型分析 → 明确审批记录 → 受控消费者恢复 → 业务探针与队列核对 → 工单状态更新 |
| 最终业务探针 | 18:45:11 UTC+8：HTTP 200 / NOTIFICATION_DELIVERED，连续成功 121 次，消费者 1、积压 0，预期与应用版本一致 |
| 观测工作台恢复 | 18:45:21 捕获：HEALTHY、drilling=false、活动告警 0；节点 observedAt=18:45:10.835，业务 probe 采样=18:44:58 |

恢复依据为 `2026-09-06T10-45-15-803Z-final-5bd2d07e.json` 的最终真实业务探针与 `2026-09-06T10-45-21-537Z-observability-recovered-149f8133.json` 的观测快照；不能仅凭 Run COMPLETED 或历史恢复标签推断当前业务健康。此链路使用隔离通知目标，没有向公共 RAG 服务注入故障。

### 发布版本与浏览器验收

- 公网：<https://opsagent.cloud>。
- 云端版本标签：`observability-workspace-20260906-r1`，涉及 Platform、RAG、Web；首次激活后补发 DEMO 只读白名单、拓扑网格及 CMDB 意图分流修复。
- 发布前在线备份已核验：`/opt/opsagent/backups/opsagent-backup-20260906T102405Z-9oi7vhE0`，包含六个应用数据库及知识附件，未清空数据库或 Docker 卷。
- 19:00:34 的云端健康记录显示 Platform、RAG、Web 及主要业务服务 healthy；无 Docker healthcheck 的中间件仅记录 Up，不把 Up 等同为业务健康。证据：`data/public-deploy/observability-final-cloud-health.log`。
- 最终 Web 构建修复了完整问答页参考栏的“对应问题”：只展示用户问题正文，页面上下文证据继续按已有折叠方式呈现，避免在来源栏展开整段 JSON。该展示修复不改变已验收的后端接口与故障恢复链路。
- 浏览器实测覆盖拓扑选择与节点六个详情 Tab、配置中心、流量治理、AI 浮层与完整页面共享会话。修复后的真实模型诊断完整返回五部分及证据引用，没有再次误分流为服务目录列表。
- 1440×1000 视口下拓扑默认适应比例约 85%，节点名称与指标可读。19:15 的 1920×1080 大屏读取到 23 节点、9 个有效健康观测、14 个待观测和 0 条活动告警；DOM 实测 scrollWidth=clientWidth=1905，无横向溢出。点击全屏后大屏边界为 (0,0)–(1920,1080)，再次点击可返回原工作台布局。截图工具裁切不能等同于页面溢出，也未据此添加无依据的 CSS 覆盖。

所有采集、日志、会话、截图、验收 JSON 和打包产物留在 Git 忽略的 `data/public-deploy`，正文只列证据路径与安全结果，不复制令牌、配置正文、密码或原始模型请求。


## 前端组件和测试

本轮工作台代码位于 `ops-web/src/views/observability`，沿用统一浅蓝 Surface、字体和控件基础样式，没有在总 CSS 尾部追加覆盖层。

| 位置 | 职责 |
| --- | --- |
| `ObservabilityWorkspaceView.vue` | 工作台页头、五个模块 Tab 和跨页服务/环境/时间上下文 |
| `TopologyView.vue` | 全局拓扑、筛选、编辑模式、自动刷新与保存共享布局 |
| `ServiceCatalogView.vue` | 独立台账列表，RabbitMQ 优先，搜索和每页 12 项分页 |
| `InspectionView.vue` | 今日真实服务检查次数、上次检查/当前健康分离、每页 10 项列表、最近 50 次证据历史和一次性只读检查；折叠复用已有平台巡检步骤 |
| `WallboardView.vue` | 浅色只读大屏、全屏、15 秒刷新、去重活动告警总数与告警标题列表 |
| `components/observability/ServiceTopology.vue` | G6 HTML 卡片、有向关系、类型图标、键盘/鼠标选择、编辑权限下拖拽、缩放、适应和默认五列网格布局 |
| `ServiceNodeDrawer.vue` | 概况/指标/流量/配置/告警/关系六个 Tab，统一助手与唯一事实页面入口 |
| `ServiceEditor.vue` / `RelationEditor.vue` | 管理员节点和关联 CRUD、监控绑定、CI 编码不可改、标签数组转换与删除确认 |
| `ServiceHealthBadge.vue` | 统一健康状态文字、点和颜色；演练标记不替代实际健康 |
| `stores/observability.ts` / `api/observability.ts` | 观测状态和类型化 API，环境/账号切换立即清除旧快照，丢弃晚到响应 |
| `utils/observability.ts` | 采样新鲜度、未知与零值区分、有向筛选、服务上下文和安全指标链接 |
| `utils/observability-layout.ts` | 节点按类型与标识稳定排序，统一卡片尺寸、间距、网格列数和画布留白 |
| `ConfigCenterView.vue` / `useConfigCenter.ts` / `api/configCenter.ts` | 多来源目录、服务筛选、脱敏正文、版本对比和旧受控发布入口；抑制过期响应 |
| `TrafficGovernanceView.vue` / `useTrafficGovernance.ts` / `api/trafficGovernance.ts` | 真实资源与规则表单、发布差异、审计回退、角色切换及幂等重试 |
| `api/ai-observability-context.ts` / `utils/ai-context.ts` / `stores/ai-assistant.ts` | 发送时授权读取观测快照、冻结服务上下文、共享会话及来源元信息 |
| `styles/pages/observability.css` | 工作台、画布、抽屉、目录、巡检和大屏的作用域样式 |
| `styles/pages/observability-governance.css` | 配置目录、规则资源、编辑表单、版本差异与发布审计的组件样式 |

类型映射补充 `VECTOR_DATABASE`（兼容 `VECTOR_DB`）、`EXTERNAL_API`、`GOVERNANCE`，分别使用向量方块、外部网络和治理盾牌图标。节点健康仍仅依赖后端实际观测；新增中间件类型不会默认变绿。

G6 依赖固定为 `@antv/g6@5.0.49`，更新既有 `pnpm-lock.yaml`。拓扑组件按路由懒加载；本地构建时对应图形依赖块约 396 KB gzip，未加入全局首屏入口。趋势图继续使用现有 ECharts。

`ops-web/tests/observability-workspace.test.mjs` 新增六组回归，执行真实 Pinia store 与编译后的 Vue setup；G6 适配层使用图引擎替身检查公共交互契约，不将其冒充浏览器渲染验收：

1. 有效/缺失/过期/未来样本、维护状态、零值与空值、百分数、有向关系筛选和演练叠加。
2. 环境/账号切换期间并发请求乱序、退出时清空、空 API 和错误后的旧快照丢弃。
3. 节点抽屉切换乱序、告警导航保留服务、调用统一 AI、配置摘要独立降级而不覆盖服务事实。
4. 实际节点编辑 handler 对非管理员保存/删除的拒绝与既有标签兼容。
5. G6 节点选择、有向箭头、只在编辑时拖拽、增改节点期间保持未保存坐标、缩放/适应、HTML 转义和大屏禁止选择操作。
6. 全部工作台页面模板编译、大屏只读及巡检当前状态/历史结果分离。

执行：`node tests/observability-workspace.test.mjs`（工作目录 `ops-web`）。完整构建、前端回归、线上 API 与真实恢复证据见上文“验证与发布”；本记录没有声称完成尚未列出证据的浏览器全量截图验收。

## 正式源码与实施文件清单

按当前基线 54449a8 的已修改文件及新增未跟踪文件核对，共 108 项。排除根目录 data/、ops-web/dist/、target/、node_modules/ 等采集与构建产物；保留真正的 ops-web/src/data/ 导航源码。此清单记录工作区交付内容，不表示已经提交 Git。机器可读副本为 data/public-deploy/observability-changed-files.txt。

```text
.gitignore
compose.yaml
deploy/public/compose.yaml
docs/observability-workspace-implementation.md
ops-common/ops-common-security/src/main/java/com/opsagent/common/security/DemoAccessPolicy.java
ops-common/ops-common-security/src/test/java/com/opsagent/common/security/DemoAccessPolicyTest.java
ops-platform-service/src/main/java/com/opsagent/platform/AlertmanagerAdapter.java
ops-platform-service/src/main/java/com/opsagent/platform/ConfigCenterController.java
ops-platform-service/src/main/java/com/opsagent/platform/ConfigCenterDtos.java
ops-platform-service/src/main/java/com/opsagent/platform/ConfigCenterService.java
ops-platform-service/src/main/java/com/opsagent/platform/ConfigurationMasker.java
ops-platform-service/src/main/java/com/opsagent/platform/ItsmPlatformController.java
ops-platform-service/src/main/java/com/opsagent/platform/ItsmPlatformRepository.java
ops-platform-service/src/main/java/com/opsagent/platform/ItsmPlatformService.java
ops-platform-service/src/main/java/com/opsagent/platform/NacosConfigurationClient.java
ops-platform-service/src/main/java/com/opsagent/platform/NodeHealthService.java
ops-platform-service/src/main/java/com/opsagent/platform/ObservabilityController.java
ops-platform-service/src/main/java/com/opsagent/platform/ObservabilityDtos.java
ops-platform-service/src/main/java/com/opsagent/platform/ObservabilityInspectionService.java
ops-platform-service/src/main/java/com/opsagent/platform/ObservabilityRepository.java
ops-platform-service/src/main/java/com/opsagent/platform/ObservabilitySanitizer.java
ops-platform-service/src/main/java/com/opsagent/platform/ObservabilitySchemaInitializer.java
ops-platform-service/src/main/java/com/opsagent/platform/OperationsWorkflowService.java
ops-platform-service/src/main/java/com/opsagent/platform/PrometheusAdapter.java
ops-platform-service/src/main/java/com/opsagent/platform/SentinelTrafficClient.java
ops-platform-service/src/main/java/com/opsagent/platform/TopologyAggregationService.java
ops-platform-service/src/main/java/com/opsagent/platform/TrafficChangeRepository.java
ops-platform-service/src/main/java/com/opsagent/platform/TrafficGovernanceController.java
ops-platform-service/src/main/java/com/opsagent/platform/TrafficGovernanceDtos.java
ops-platform-service/src/main/java/com/opsagent/platform/TrafficGovernanceService.java
ops-platform-service/src/main/java/com/opsagent/platform/TrafficRuleValidator.java
ops-platform-service/src/main/java/com/opsagent/platform/TrafficSchemaInitializer.java
ops-platform-service/src/main/resources/application.yml
ops-platform-service/src/main/resources/observability-schema.sql
ops-platform-service/src/main/resources/traffic-governance-schema.sql
ops-platform-service/src/test/java/com/opsagent/platform/ConfigurationSafetyTest.java
ops-platform-service/src/test/java/com/opsagent/platform/ObservabilityHealthTest.java
ops-platform-service/src/test/java/com/opsagent/platform/ObservabilityRepositoryTest.java
ops-platform-service/src/test/java/com/opsagent/platform/PrometheusAdapterTimestampTest.java
ops-platform-service/src/test/java/com/opsagent/platform/TopologyAggregationTest.java
ops-platform-service/src/test/java/com/opsagent/platform/TrafficGovernanceTest.java
ops-rag-service/src/main/java/com/opsagent/rag/CmdbAnswerService.java
ops-rag-service/src/main/java/com/opsagent/rag/RagController.java
ops-rag-service/src/main/java/com/opsagent/rag/RagConversationController.java
ops-rag-service/src/main/java/com/opsagent/rag/RagRateLimiter.java
ops-rag-service/src/main/java/com/opsagent/rag/SentinelTrafficRuntimeController.java
ops-rag-service/src/main/resources/application.yml
ops-rag-service/src/test/java/com/opsagent/rag/CmdbAnswerServiceTest.java
ops-rag-service/src/test/java/com/opsagent/rag/RagConversationServiceTest.java
ops-rag-service/src/test/java/com/opsagent/rag/RagTrafficScopeTest.java
ops-rag-service/src/test/java/com/opsagent/rag/RagVisitorIntegrationTest.java
ops-web/package.json
ops-web/pnpm-lock.yaml
ops-web/src/api/ai-observability-context.ts
ops-web/src/api/configCenter.ts
ops-web/src/api/observability.ts
ops-web/src/api/trafficGovernance.ts
ops-web/src/components/ai/AiAssistantDock.vue
ops-web/src/components/ai/AiAssistantOrb.vue
ops-web/src/components/AppBreadcrumb.vue
ops-web/src/components/AppSidebar.vue
ops-web/src/components/cmdb/topology.ts
ops-web/src/components/GlobalTopbar.vue
ops-web/src/components/observability/RelationEditor.vue
ops-web/src/components/observability/ServiceEditor.vue
ops-web/src/components/observability/ServiceHealthBadge.vue
ops-web/src/components/observability/ServiceNodeDrawer.vue
ops-web/src/components/observability/ServiceTopology.vue
ops-web/src/components/PageHeader.vue
ops-web/src/composables/useConfigCenter.ts
ops-web/src/composables/useRagConversations.ts
ops-web/src/composables/useTrafficGovernance.ts
ops-web/src/data/navigation.ts
ops-web/src/data/workspace-actions.ts
ops-web/src/layouts/AppLayout.vue
ops-web/src/router/index.ts
ops-web/src/stores/ai-assistant.ts
ops-web/src/stores/observability.ts
ops-web/src/styles/pages/observability-governance.css
ops-web/src/styles/pages/observability.css
ops-web/src/utils/ai-context.ts
ops-web/src/utils/observability-layout.ts
ops-web/src/utils/observability.ts
ops-web/src/utils/route-navigation.ts
ops-web/src/views/AlertView.vue
ops-web/src/views/AutomationView.vue
ops-web/src/views/observability/ConfigCenterView.vue
ops-web/src/views/observability/InspectionView.vue
ops-web/src/views/observability/ObservabilityWorkspaceView.vue
ops-web/src/views/observability/ServiceCatalogView.vue
ops-web/src/views/observability/TopologyView.vue
ops-web/src/views/observability/TrafficGovernanceView.vue
ops-web/src/views/observability/WallboardView.vue
ops-web/src/views/OperationsView.vue
ops-web/src/views/RagWorkspaceView.vue
ops-web/tests/ai-observability-context.test.mjs
ops-web/tests/alerts-context.test.mjs
ops-web/tests/automation-approvals.test.mjs
ops-web/tests/governance-workspace.test.mjs
ops-web/tests/navigation-convergence.test.mjs
ops-web/tests/observability-workspace.test.mjs
ops-web/tests/rag-navigation.test.mjs
ops-web/tests/support-navigation.test.mjs
ops-web/tests/workspace-actions.test.mjs
README.md
sql/upgrade/20260906_observability_catalog.sql
sql/upgrade/20260906_observability_workspace.sql
sql/upgrade/20260906_traffic_governance.sql
```

