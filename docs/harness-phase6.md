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

平台在每次读取生成输出时，使用本次 Run 的当前委托调用 `/internal/rag/harness/answer-access`，仅提交 `{contextId,promptSha256}`。Ops 检查上下文所属用户、配置应用/项目、精确 Run 绑定、原始 prompt hash，再验证 **所有进入 prompt 的来源**。只检查模型最终展示的引用不够。

- 知识文档：现查文档 ACL、版本以及 chunk 属于该文档。全局检索要求仍为已发布；明确选择文档/工单附件时沿用原领域的授权草稿/体验文档规则，归档文档始终拒绝。工单附件使用独立 `ticket-knowledge` audience 向真实 Ticket 服务复核读取权限，不把内部 token 冒充用户 JWT。
- 官方公开网页：只接受服务端固定官方文档 CATALOG 中的 URL；客户端不能上传来源或覆盖上下文。
- 观测证据：在当前委托主体下重新读取原 evidenceBundleId，匹配原服务/环境/时间窗和全部 evidenceId；不可用或不再授权时拒绝。
- CMDB 目录：保留领域确定性只读回答，当前领域读权限为已登录用户；不调用模型伪造目录。
- 旧实时 Operations 聚合问答包含未保存实体 ID 的账号告警摘要，无法按条重新验证历史权限。因此在开始请求、分配归属前明确选择 **LEGACY**，继续使用现有完整领域链路。该例外由 `RagService.legacyDomainRequired` 决定，发生在调用平台前，不是平台故障时临时回退。带明确 `observabilityContext` 的新证据链可以走 HARNESS。

旧会话 SSE、旧运维聚合和业务工作流的执行权保留是显式迁移边界；未把整个 OpsAgent 业务系统迁入中台。没有真实业务订单后端时不把运维 demo-order 当作客户订单服务。

问答上下文保留 30 天；`harness-answer-retention.sql` 先列出终态候选，再由管理员填入逐条核对的 id、input digest、首次观察到终态的时间作条件清理。默认参数全为 NULL，不会清理任何内容。UNKNOWN、PREPARING、READY 和未确认平台终态的行不进入候选。清理仅擦除 prompt、上下文与答案，保留 owner、请求摘要、Run 关联及时间等 tombstone。首次观察到完成状态的时间只用于保留周期和端到端等待耗时，不冒称模型服务端耗时。

## 验证

新增测试覆盖持久所有权、并发单次 claim、UNKNOWN 防重、创建幂等与回退、原 prompt/context/run/user 绑定、逐引用撤权、Auth 失效拒绝，以及既有身份桥/只读检索测试。实际服务联调还应验证验证码登录、真实 SQL 授权、真实 Knowledge ACL、模型 Run、读结果后的撤权、旧请求回退归属和错误的 If-Match。

本机真实服务联调报告由中台仓库统一保存。VPS 部署、Linux 持续观察和跨主机灾备的执行结果不能由本地测试代替。

## 部署交付核对

1. 同批交付 Auth、Knowledge、RAG 和 Ticket 新版本。Ticket 的 `/internal/knowledge/tickets/{id}` 是工单附件撤权复核的依赖；只升级 RAG 会导致该类证据拒绝展示。
2. 使用部署管理员先执行所需的显式 schema/upgrade SQL，包括保留旧模型回执的 `internal-agent-schema.sql`，再以仅具 DML 权限的服务账号运行。Harness profile 设置 `ops.rag.schema-auto-initialize=false`，旧模型存储也只读检查 schema；非 Harness 的旧环境保留原自动初始化默认。Auth 与 RAG 缺列时停止上线，不授予服务账号自动 DDL 权限。
3. 在仓库外注入登录签名密钥、独立内部签名根、应用凭据和模型密钥。核对 `OPS_AUTH_INTERNAL_URL`、`OPS_KNOWLEDGE_INTERNAL_URL`、`OPS_TICKET_URL`；这些地址必须指向真实服务并留在受信网络。反向代理不暴露 `/internal/**`。
4. 用即将部署的平台可执行包输出 releaseRef，核对 Model→End 图、来源保护、模型绑定和准确 digest，再配置到 RAG。先以 LEGACY 保持新请求归属，完成身份、授权、委托、公开/私有/工单附件引用、幂等和控制命令检查后，切换新请求到 HARNESS。
5. 回退仅切换新请求归属，保留原版本查询能力、SQL ownership tombstone 与平台 Run。UNKNOWN 不自动重放；超过一小时的 PREPARING 按离线恢复规程审阅。不得用删除旧请求行的方式“恢复”。
6. 运维包同时保留 `harness-answer-recovery.sql`、`harness-answer-retention.sql`、变更前备份与审核记录。保留清理、恢复演练和 Linux 持续观察必须记录实际环境与执行时间，不以本机单元测试代替。
