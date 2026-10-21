# OpsAgent 与 Agent 平台第六阶段接入

执行基础迁移保留现有 Spring Cloud 领域边界。Auth 仍负责账号、验证码、角色和项目授权；Knowledge 仍负责文档检索、版本和 ACL；RAG 仍负责准备上下文、来源与最终引用验证。集中平台负责模型执行、共享预算、并发、持久 Run、控制、审批、未知结果对账和审计。

## 启动与权限

使用真实 `ops-auth-service`、`ops-knowledge-service`、`ops-rag-service` Spring Boot JAR，加 `--spring.profiles.active=harness-local`。默认仅绑定 loopback，端口分别为 18401、18403、18404；所有数据库、内部签名密钥、JWT 密钥、应用凭据从环境或受限外部配置读取。不得将私密配置提交到 Git。此 profile 不关闭登录验证码、不注入替身 Auth 或绕过 Spring Security。

部署管理员先创建独立数据库并导入各服务常规表结构，再按需执行：

- Auth 新库：`ops-auth-service/src/main/resources/harness-identity-schema.sql`。
- 已有第一、二阶段桥接库：仅执行 `harness-identity-upgrade-v2.sql` 补应用与授权修订列。
- RAG：`harness-route-schema.sql`（原只读检索试点）和 `harness-answer-schema.sql`（问答接入）。

应用运行账号仅使用 DML 权限。以上迁移不在请求中自动执行。已有 ownership/UNKNOWN 记录必须保留，不通过删表清理重试。

Auth 表中应用凭据仅存 SHA-256。`ops_harness_grant` 的 `(application_id,project_id,user_id)` 必须显式授权，角色匹配当前数据库角色与原登录/委托角色的交集。`revision` 用于离线部署管理员 CAS 修改；应用禁用、凭据轮换、成员撤权和角色移除都在后续身份/委托读取时生效。新加角色不会扩大旧委托权限。

平台业务应用最小权限是 `runs:create`、`runs:read`、`runs:list`、`runs:events:read`、`runs:control`、`runs:output:read`、`model:invoke`、`opsagent:rag:search`。审批与治理、自助接入权限按职责另授，不能通过客户端请求自授。

配置 `OPS_AGENT_INTERNAL_SECRET`、`OPS_AUTH_INTERNAL_URL`、`OPS_KNOWLEDGE_INTERNAL_URL`，使三个服务的内部调用具有相同独立签名根和正确受信地址。内部接口由服务控制器检查专用 audience token；公开 JWT 不可替代内部凭据。`/internal/**` 不应通过公网代理暴露。

## 问答迁移开关和 API

配置 `OPS_HARNESS_APPLICATION_ID`、`OPS_HARNESS_PROJECT`、`OPS_HARNESS_APPLICATION_CREDENTIAL`、`OPS_HARNESS_AUTH_ORIGIN`、`OPS_HARNESS_PLATFORM_ORIGIN`。默认 `OPS_HARNESS_NEW_OWNER=LEGACY`；设置为 `HARNESS` 前需要发布并填写准确的 `OPS_HARNESS_RELEASE_ID`、`OPS_HARNESS_RELEASE_DIGEST`、`OPS_HARNESS_PROVIDER`、`OPS_HARNESS_MODEL`。

`POST /api/rag/ask` 和 `/api/rag/chat` 的原请求字段不变。启用桥接后必须带长度 8–128 的 `Idempotency-Key`：

1. 服务先校验真实登录身份和应用授权，再持久化请求归属。相同主体、应用、项目和 key 的不同输入返回冲突。
2. LEGACY 只允许一次生成 claim；已捕获的生成异常记为 UNKNOWN，进程突然中断时保留 PREPARING，均不会再次生成或调用平台重放。PREPARING 需要人工核查，不能据此推断模型尚未执行。
3. HARNESS 在 SQL 中保存服务端生成的准确上下文、全部来源、原请求、prompt SHA-256 和固定 release。创建 Run 只使用冻结 payload 与同一个 key，丢失创建响应后可用同 key 恢复。
4. 202 响应含 `owner`、`routeId`、`status`、`statusUrl`。完成后 GET statusUrl 返回原 `RagService.Answer` 形状，HTTP 200。中间状态不伪装成答案。
5. 前端工单问答已支持异步读取；sessionStorage 仅保存请求摘要和 opaque id/key，网络失败后保留同一次执行。不会保存 prompt、token 或答案。

`rag-eval/scripts/evaluate.py --include-answers` 同样支持桥接：每次评测先输出一个 `answer-run-id`，按该 ID 和固定 case ID 生成幂等键，提交一次后仅轮询同一条本地状态路径。失败、UNKNOWN、待人工处理或超时会停止评测，不把 202 当作空答案计分，也不自动创建另一条请求。保留输出的 ID，后续显式使用 `--answer-run-id <原 UUID>` 继续同一批案例；修改同一 case 的输入会由服务返回冲突。`--answer-timeout` 默认为每个案例 300 秒。API 重定向一律拒绝，令牌不会随状态 URL 或重定向传给其他地址。

控制端点位于 `/api/rag/harness/answers/{routeId}`，包括 `events`、`approval`、`unknown-invocation`、`pause|cancel|resume` 和 `approvals/{approval}/decision`。命令必须透传平台给出的准确 `If-Match` 与新 `Idempotency-Key`。身份、拥有者/审批分配及每项权限由平台再次校验。UNKNOWN 工具对账由平台管理控制台完成，不提供不受约束的强制恢复接口。

切回 LEGACY 只改变新请求的归属。已有 HARNESS 请求继续指向同一个 Run；已有 LEGACY 请求不转移。停用桥接前必须排空在途任务，并保留旧服务/路由用于查询和控制。原会话流式接口、旧 AgentLoop 与工作流不会因为开启问答开关而隐式切换。

持续超过一小时的 PREPARING 可使用 `harness-answer-recovery.sql` 离线处理。先关闭入口、排空并停止所有使用同一数据库的 RAG 实例，再核对请求 id、input digest、创建时间和恢复动作；脚本默认全部 NULL，不会修改数据。仅 HARNESS 且 context、dispatch、prompt hash、Run、答案均尚未落库时允许回到 NEW，重启后使用原始请求和原始 key 继续。LEGACY 只能标记 UNKNOWN，绝不重放。READY 使用原 key 向平台复核，SUBMITTED 与其他状态均不由该脚本重置。每次操作须保存候选元数据、理由和影响行数。

生成请求会按整段冻结 dispatch JSON 的 UTF-8 字节数检查平台 65,536 字节上限，超限在 READY 前明确拒绝并标记 REJECTED，不截断证据，也不留下永远无法发送的 READY 请求。

## 领域范围与输出安全

受控模型 release 必须使用 `agentId=ops-rag-answer`、`publicOutput=false`、无工具的 Model→End 图，静态 system、user=`{{prompt}}`，输入为 `{prompt,contextId}`。不能用 publicOutput 绕过知识权限。

平台在每次读取 HARNESS 生成输出时，先核验当前读取者与原 Run 的应用、项目、主体及输出读取/检索权限，再向 Auth 的 `POST /internal/harness/delegations/{id}/output-token` 提交 `{projectId,subject,runId}`。Auth 校验原委托绑定、未撤销状态、当前应用/用户/项目授权，以及当前角色和权限与原委托上限的交集；必须同时拥有 `runs:output:read` 和 `opsagent:rag:search`。只有这条输出读取路径允许原执行委托已经过期，不续期或修改该委托，也不恢复其执行权限。

签发的令牌固定使用 `rag-output` audience，最长 120 秒且不超过当前访客期限。平台携带该令牌调用 `/internal/rag/harness/output-answer-access`，仅提交 `{contextId,promptSha256}`；Ops 检查上下文所属用户、配置应用/项目、精确 Run 绑定、原始 prompt hash，再通过 `/internal/rag/output/validate-citations` 复核 **所有进入 prompt 的来源**。只检查模型最终展示的引用不够。输出令牌不能用于旧 `rag` audience 的模型调用、检索或执行端点；旧 `/internal/rag/harness/answer-access` 与 `/internal/rag/validate-citations` 的执行授权约束保留。

- 知识文档：现查文档 ACL、版本以及 chunk 属于该文档。全局检索要求仍为已发布；明确选择文档/工单附件时沿用原领域的授权草稿/体验文档规则，归档文档始终拒绝。工单附件使用独立 `ticket-knowledge` audience 向真实 Ticket 服务复核读取权限，不把内部 token 冒充用户 JWT。
- 官方公开网页：只接受服务端固定官方文档 CATALOG 中的 URL；客户端不能上传来源或覆盖上下文。
- 观测证据：在本次输出读取主体下重新读取原 evidenceBundleId，匹配原服务/环境/时间窗和全部 evidenceId；不可用或不再授权时拒绝。
- CMDB 目录：保留领域确定性只读回答，当前领域读权限为已登录用户；不调用模型伪造目录。
- 旧实时 Operations 聚合问答包含未保存实体 ID 的账号告警摘要，无法按条重新验证历史权限。因此在开始请求、分配归属前明确选择 **LEGACY**，继续使用现有完整领域链路。该例外由 `RagService.legacyDomainRequired` 决定，发生在调用平台前，不是平台故障时临时回退。带明确 `observabilityContext` 的新证据链可以走 HARNESS。

旧会话 SSE、旧运维聚合和业务工作流的执行权保留是显式迁移边界；未把整个 OpsAgent 业务系统迁入中台。历史输出授权修复限定 HARNESS 平台输出，LEGACY 和立即答复的原有委托与保留期限未变，不提供无期限历史访问保证。没有真实业务订单后端时不把运维 demo-order 当作客户订单服务。

问答上下文保留 30 天；`harness-answer-retention.sql` 先列出终态候选，再由管理员填入逐条核对的 id、input digest、首次观察到终态的时间作条件清理。默认参数全为 NULL，不会清理任何内容。UNKNOWN、PREPARING、READY 和未确认平台终态的行不进入候选。清理仅擦除 prompt、上下文与答案，保留 owner、请求摘要、Run 关联及时间等 tombstone。首次观察到完成状态的时间只用于保留周期和端到端等待耗时，不冒称模型服务端耗时。

## 验证

新增测试覆盖持久所有权、并发单次 claim、UNKNOWN 防重、创建幂等与回退、原 prompt/context/run/user 绑定、逐引用撤权、Auth 失效拒绝，以及既有身份桥/只读检索测试。实际服务联调还应验证验证码登录、真实 SQL 授权、真实 Knowledge ACL、模型 Run、读结果后的撤权、旧请求回退归属和错误的 If-Match。

本机真实服务联调报告由中台仓库统一保存。VPS 部署、Linux 持续观察和跨主机灾备的执行结果不能由本地测试代替。

GitHub 的 `.github/workflows/verify.yml` 在 push、pull request 或手动触发时执行两项独立验证：Java 17 使用仓库 Maven Wrapper 固定的 Maven 3.9.16 执行 `clean verify`，包含单元测试和 Checkstyle；前端使用 Node.js 22 与 pnpm 10，按既有 `pnpm-lock.yaml` 执行 `pnpm install --frozen-lockfile`，逐个运行 `ops-web/tests/*.test.mjs`，再执行 `pnpm run build`（包含 Vue/TypeScript 检查及 Vite 构建）。仓库没有 npm 锁文件，因此 CI 沿用既有 pnpm 锁，不生成第二套锁文件。需要外部环境的既有门控测试仍按原条件执行，不能将跳过计为通过。流程仅有 `contents: read` 权限，不使用生产 secret，不部署、发布 release 或上传构建制品；实际远端运行成功后才能登记 GitHub 构建通过。

2026-10-20 的 VPS 前补充修复将 RAG 的中台调用、身份委托和引用权限核验统一接入有完整正文等待时限的 HTTP 传输。时限覆盖取得响应头之后的正文等待，超时或中断取消未完成请求；按实际字节数限制正文，包括没有 `Content-Length` 的分块响应。权限响应不可核验时拒绝展示，写请求响应未知时保留既有未知结果语义，不自动重试或切换执行器。身份响应的业务码还需满足 32 位整数范围，不能因大整数截断而被判为成功。验证码、登录流程、会话有效期、请求归属和来源 ACL 规则未改变。

第一轮 HTTP 修复取得 16 项定向回归和 167 suites、1,061 tests 的全量构建证据。随后真实联调发现，旧 HARNESS Run 的输出读取错误依赖已到期的执行委托；新鲜登录态与仍可读的来源文档不能解除这一依赖。上述独立历史输出授权修复增加过期委托读取、撤销与权限上限、跨应用/项目/主体/Run、访客期限、专属 audience 及当前来源拒绝测试。旧执行接口仍拒绝过期委托，输出读取不会重新调用模型。

2026-10-20 该轮源码快照的 17 模块 `clean verify` 汇总为 **168 suites、1,071 tests，零失败、零错误，2 项既有环境门控测试跳过，Checkstyle 零违规**；1,129 个构建输入（含测试、不含文档）已逐文件核对一致。跳过项分别需要 `OPS_AGENT_DIAGNOSIS_REVIEW_DIRECTORY` 和 `opsagent.docs.live`，未将其计为已执行通过。构建报告由中台仓库保存在忽略目录 `.work/phase6/ops-bugfix2-build-report.json`。

本次 `bugfix2` 制品另行取得真实服务联调证据：原已完成 Run 的只读回读 **8 项通过**，确认原答案、事件时间线、拒绝越权和调用账本不变；新建独立 DeepSeek Run 的问答接入 **10 项通过**，覆盖幂等复用、输入冲突、不同主体拒绝、只读用户拒绝、过期 If-Match 拒绝，以及单个完成 Run 的带引用答案；真实 Ticket 附件权限复核 **3 项通过**；来源 ACL 与版本复核 **5 项通过**，确认归档后 Ops 与平台同时拒绝历史答案、恢复后读取同一答案、来源版本变更后拒绝旧答案，并恢复原测试来源的 ACL 与版本。对应报告分别为 `.work/phase6/ops-bugfix2-readonly-report.json`、`ops-bugfix2-integration-report.json`、`ops-bugfix2-ticket-report.json`、`ops-bugfix2-acl-report.json`，均保存在中台仓库忽略目录。这些结果不替代浏览器验收、VPS 部署或新的完整 24 小时观察；长时观察须独立取得完整时长，尚未取得通过证据。

第一轮新制品联调另有一次真实 DeepSeek 调用进入 `NEEDS_ATTENTION / UNKNOWN`，持久错误码为 `MODEL_CALL_OUTCOME_UNKNOWN`。该 Run 仅有一次模型调用、零工具调用，原 Run、调用账本及失败报告保留且未重放；具体传输根因尚未确定，不能据此认定为超时或供应商故障，也不能将历史输出委托修复当作其已证实原因。中台忽略目录中的 `ops-bugfix-integration-report.json`、`ops-bugfix-readonly-report.json` 和对应私有诊断证据继续保留，后续联调另建报告。

## 部署交付核对

1. 同批交付 Auth、Knowledge、RAG 和 Ticket 新版本。Ticket 的 `/internal/knowledge/tickets/{id}` 是工单附件撤权复核的依赖；只升级 RAG 会导致该类证据拒绝展示。
2. 使用部署管理员先执行所需的显式 schema/upgrade SQL，包括保留旧模型回执的 `internal-agent-schema.sql`，再以仅具 DML 权限的服务账号运行。Harness profile 设置 `ops.rag.schema-auto-initialize=false`，旧模型存储也只读检查 schema；非 Harness 的旧环境保留原自动初始化默认。Auth 与 RAG 缺列时停止上线，不授予服务账号自动 DDL 权限。
3. 在仓库外注入登录签名密钥、独立内部签名根、应用凭据和模型密钥。核对 `OPS_AUTH_INTERNAL_URL`、`OPS_KNOWLEDGE_INTERNAL_URL`、`OPS_TICKET_URL`；这些地址必须指向真实服务并留在受信网络。反向代理不暴露 `/internal/**`。
4. 用即将部署的平台可执行包输出 releaseRef，核对 Model→End 图、来源保护、模型绑定和准确 digest，再配置到 RAG。先以 LEGACY 保持新请求归属，完成身份、授权、委托、公开/私有/工单附件引用、幂等和控制命令检查后，切换新请求到 HARNESS。
5. 回退仅切换新请求归属，保留原版本查询能力、SQL ownership tombstone 与平台 Run。UNKNOWN 不自动重放；超过一小时的 PREPARING 按离线恢复规程审阅。不得用删除旧请求行的方式“恢复”。
6. 运维包同时保留 `harness-answer-recovery.sql`、`harness-answer-retention.sql`、变更前备份与审核记录。保留清理、恢复演练和 Linux 持续观察必须记录实际环境与执行时间，不以本机单元测试代替。

## 2026-10-21 公网集成验收与追加修复

本节记录 `https://opsagent.cloud` 上实际执行的集成验收，时间均为 UTC。服务使用真实 Auth、Knowledge、Ticket、RAG、Agent、Platform 和既有中间件；生产配置、凭据与完整请求证据留在中台仓库忽略目录，不写入 Git。

### 已验证的集成边界

- 新的非流式文档与工单问答已切到 HARNESS，固定 release `3c101ddc-9461-4aef-8744-238ef6e073f5`，digest `sha256:381716233624488517080583498c19dd55b02c40a80fa355c85b473d43d1fdc1`。文档 1036 和工单 2099 的真实附件 1043 分别产生已完成的平台 Run，保留版本引用。切换前生成的 LEGACY 请求仍能读取完全相同的原答案，没有迁移或重放旧请求。
- 主会话 `POST /api/rag/conversations/{id}/stream` 仍由原会话执行器承担；实际 DeepSeek SSE 完成，引用 1036 v1 并持久化历史。旧实时运维聚合和 Agent 业务工作流也仍由 Ops 的领域链路执行。不能把问答开关开启表述为整个 OpsAgent 已迁入 Harness。
- 使用实际验证码登录的独立访客身份验证：管理员私有文档、工单、Harness 答案与 Agent Run 的跨用户读取均拒绝，业务码为 `40300`。部分领域响应的 HTTP 状态仍为 200，验收同时检查 HTTP 与业务码，没有将这些拒绝计为成功读取。
- 原维护手册 1036 及两份明确标注为部署验收事实的文档 1042、1043 完成解析、人工复核、发布与索引。02:45:47 的一致性检查为 29 个已发布文档、62 个已发布 chunk，ES/Qdrant 缺失与孤儿均为 0；合法草稿预索引不算孤儿。使用真实手册 chunk 的查询取得 `HYBRID_RRF`，短关键词查询在当前阈值下可能只有 BM25。被删除文档 1034/1035 的历史失败任务保留。
- DeepSeek 取得真实生成证据；Ops 原生 OpenAI Responses 探针也成功。Kimi 因目标未配置密钥未验证；不能由原生 OpenAI 探针推断 Harness 已支持 OpenAI Responses。

### 三类恢复演练

验收串行执行，每场使用独立 incident。只批准当前事件、目标、配置 revision 与工具完全匹配的修复，随后检查新鲜业务探针、同一个告警 episode 的恢复及工单状态。以下通过结果来自逐步修复过程中的不同 Agent 镜像；最后一列明确对应版本，避免将早先结果冒称为最终镜像重跑。

| 场景 | 成功工单 / Run | 实际恢复证据 | Agent 镜像前缀 |
| --- | --- | --- | --- |
| Nacos Redis 配置漂移 | 2099 / `631c6d4d-9a28-448c-a315-263bbefe7ab0` | 503→200；`demo_config_restore`；AGENT_TOOL；同 episode resolved；工单 RESOLVED、Run COMPLETED | `cc992ae2ccb5` |
| Sentinel 规则回归，新独立重验 | 2101 / `4f621c90-8352-45b4-926b-a6b371ec8eba` | 429→200，QPS 0→5；`demo_flow_restore`；AGENT_TOOL；同 episode resolved；工单 RESOLVED、Run COMPLETED | `f1c1dedf06f3` |
| RabbitMQ 消费暂停，新独立重验 | 2103 / `021f7b48-681f-48ab-90d3-e0d8ea9d01bf` | 503→200；`demo_queue_restore`；AGENT_TOOL；`notifications` vhost 的 `opsagent.demo.notification.v1` 消费者恢复为 1、积压 0、真实投递及 Redis 回执读回；同 episode resolved；工单 RESOLVED、Run COMPLETED | `9d06f119a554` |

最终 RabbitMQ 场在 03:36:33 观察到发布/投递计数均为 3925、`queueDrained=true`、连续成功 34 次，目标恢复 `canStart=true`。这是隔离通知业务队列的验证，不表示主 RabbitMQ 所有队列都已清空；主审计死信队列另行记录与排查，不确认、重放或删除其中消息。

两次先行失败没有被覆盖：

- Sentinel 工单 2100 / Run `536eb182-24a3-4c29-b886-5deff87e3a66` 的已知模型结果中，`evidence` 长度 1107 超过冻结上限 700；未准备写入或审批，安全停在 NEEDS_ATTENTION。02:24:16 由精确的人工恢复动作恢复，来源为 MANUAL。
- RabbitMQ 工单 2102 / Run `e560c5df-2ef9-4733-9290-d7c5250b6514` 的三份诊断 `evidence` 长度分别为 856、1159、1012，均超过 700；两次有界纠正耗尽后停在 NEEDS_ATTENTION，未执行同批修复。03:09:23 人工恢复消费者、积压清零并取得实际 200，来源同样为 MANUAL。

这两场保留原 Run、计费、模型结果与工单工作记录，不计作 Agent 或 TTL 恢复成功；后续成功场没有复用、重置或重放它们。既有其他 UNKNOWN/PREPARING 记录也没有为验收清理。

### 诊断参数与 Web 权限修复

`AgentDiagnosticCorrections` 仅处理已知模型返回中尚未执行的 `ticket_add_analysis` 参数错误。整批工具先校验，原始响应与取消的调用持久审计；通过原生 tool-call ID 反馈后，使用新的模型回合纠正，最多两次。纠正未成功落入合法诊断前不能创建修复审批；第三次错误安全停止，resume 不能重置纠正次数。UNKNOWN、已准备写入及修复工具参数不进入这条纠正路径，预算、已计费 token、期限均不重置。

RabbitMQ 失败进一步表明，笼统的“超出协议”反馈不足。最终修复在错误中给出字段、`actualLength` 和当前/冻结 `maxLength`，并从本次冻结 schema 生成紧凑证据指导：保留完整引用 ID、原值和单位，只选支持结论的关键观测。未提高 700 字限制、截断事实或放宽审批。旧 v1 schema 不会收到它没有的诊断字段指导。新 RabbitMQ 场的诊断为 620 字，零参数纠正、零引用纠正，正常进入审批并恢复。

`ops-web/Dockerfile` 另修复 Windows 构建产物带入镜像后的静态文件权限：目录归 root 且为 0755、文件 0644，Nginx worker 可遍历读取而不可写。已核对原 `dist` 的 150 个清单文件及镜像全部 151 个静态文件字节不变，并以真实非特权 Nginx worker 验证页面、JS、CSS、图标读取。该修复没有改动前端业务内容。

### 最终 Agent 构建与部署指纹

最新 17 模块源码快照执行 `clean verify`：**169 suites、1076 tests，0 failures、0 errors、2 项既有条件跳过，Checkstyle 0 违规**。两项跳过仍是外部保存诊断样本与显式联网官方文档测试；其余 1074 项实际执行通过。Windows JDK 17 测试 JVM 显式加载本地 Byte Buddy agent，未改变生产 JVM 配置。最终源码输入清单的 1190 个文件在构建后重新核对一致；本节交付文档在验收后追加，不属于先前冻结构建内容。

| 项目 | SHA-256 |
| --- | --- |
| 最终源码输入 manifest | `8eb4dd148fc7c5a94959a2fa865b61fb002aa3171acf76e5d0e44c1be7cd675a` |
| Agent 可执行 JAR | `72bc0004811b04bb2754a9ffdeaafddc2194c04dde147308b27f95415dd51236` |
| Agent 不可变镜像 | `9d06f119a554520694b88786f027cdf56912b9155bd0ace7258a5ff5a34370b0` |
| 最终 pinned Compose | `cda33cd6eb819a48b3203193e8ed63005baa3408027e578309c1781b325487c2` |

镜像沿用原固定 digest 的 Temurin 17 JRE 与运行时 Dockerfile，上传后和容器内均回读核验 JAR。最后一次部署只替换 Agent 服务，其他容器 ID 不变，`unless-stopped` 策略和旧镜像保留；本机与目标 pinned Compose 已同步，维护入口按最终 Compose 摘要重新绑定。

中台忽略目录中的主要证据：`.work/phase6/target-acceptance-20261021/ops-business/acceptance-summary.json`、`diagnostic-feedback-build-report.json`、`drill3-outcome.json`、`drill5-outcome.json`，以及 `.work/phase6/agent-feedback-image-20261021T032831Z/deployment-report.json`。这些真实短时验收不替代新的完整 24 小时观察或跨主机灾备演练。
