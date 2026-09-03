# opsagent

`opsagent` 是一个前后端分离的运维工单、知识库与智能问答系统。后端已重构为 Java 17 / Spring Boot 3.5 / Spring Cloud 多模块工程；前端位于 `ops-web`，采用 Vue 3、Vite、TypeScript、Pinia 和 Vue Router。

当前已形成“登录 → 工单 → Outbox → RabbitMQ → 平台审计”和“文档上传 → RabbitMQ → 切片 → OpenAI Embedding → Elasticsearch → 权限检索 → LLM SSE → 真实来源”两条业务闭环，并实现文档软删除和 Elasticsearch 持久化补偿。MySQL、Redis、Nacos、Sentinel、RabbitMQ、Elasticsearch、Prometheus 和 Grafana 由 Docker Compose 运行，六个 Java 服务仍在宿主机运行。LLM 已接入 OpenAI、DeepSeek 和 Kimi，可通过配置切换，API Key 只在仓库外保存并于进程启动时注入。

## 项目结构

```text
opsagent
├─ ops-common                 公共响应、Web、安全、MyBatis、Redis/MQ 与可观测模块
├─ ops-gateway                统一入口（8080）
├─ ops-auth-service           认证服务（8101）
├─ ops-ticket-service         工单服务（8102）
├─ ops-knowledge-service      知识服务（8103）
├─ ops-rag-service            RAG 服务（8104）
├─ ops-platform-service       平台服务（8105）
├─ ops-web                    Vue 3 前端项目
├─ demo-data                  企业 Runbook、附件和一键初始化脚本
├─ sql                        分库初始化脚本
├─ compose.yaml               全部中间件容器（不包含 Java 应用）
├─ opsAgent使用文档.md          完整功能使用说明
└─ pom.xml                     后端 Maven 配置
```

## 快速操作

首次运行或代码修改后，先停止旧 Java 进程，再完成构建和启动：

```powershell
powershell -ExecutionPolicy Bypass -File D:\middleware\scripts\stop-opsagent.ps1
powershell -ExecutionPolicy Bypass -File D:\middleware\scripts\start-opsagent.ps1 -Build
```

代码没有变化时直接执行 `D:\middleware\scripts\start-opsagent.ps1`。查看状态和停止：

```powershell
powershell -ExecutionPolicy Bypass -File D:\middleware\scripts\status-opsagent.ps1
powershell -ExecutionPolicy Bypass -File D:\middleware\scripts\stop-opsagent.ps1
```

主要入口：

| 功能 | 地址 | 当前用途 |
|---|---|---|
| OpsAgent 前端 | `http://127.0.0.1:5173/` | 用户操作入口 |
| Gateway | `http://127.0.0.1:8080/` | 统一 API 入口 |
| Nacos | `http://127.0.0.1:8849/` | 服务发现和六个 Data ID |
| Sentinel | `http://127.0.0.1:8858/` | 六个服务的实时监控和簇点链路 |
| RabbitMQ | `http://127.0.0.1:15672/` | 队列、消费者和 DLQ |
| Prometheus | `http://127.0.0.1:9090/targets` | 六个 Java 服务抓取状态 |
| Grafana | `http://127.0.0.1:3000/` | `OpsAgent Overview` 指标面板 |
| Elasticsearch | `http://127.0.0.1:9200/_cluster/health` | `dense_vector` 知识检索索引 |

账号和密码不写入 Git 文档，统一查看本机仓库外文件 `D:\middleware\docs\OpsAgent本地地址与密码.md`。

## 角色与权限

- `USER`：创建并查看自己的工单、上传文档、提问、关闭已解决工单。
- `OPS`：查看待处理工单和自己负责的工单，接单、填写结构化处置记录、上传文档、提问和解决工单。
- `ADMIN`：访问全部工单和真实后台链路，执行所有状态操作，并管理通知和审计记录。

认证沿用现有 Spring Security 与 JWT。登录、注册、健康检查和 Swagger 之外的接口默认要求 `Authorization: Bearer <token>`。JWT 默认有效期为 120 分钟；服务端每次请求仍会校验签名、有效期并恢复 `SecurityContext`。

## 主要接口

```text
POST /api/auth/login
POST /api/auth/refresh
GET  /api/auth/me

POST /api/tickets
GET  /api/tickets
GET  /api/tickets/{id}
PUT  /api/tickets/{id}
POST /api/tickets/{id}/claim
POST /api/tickets/{id}/transition
GET  /api/tickets/{id}/trace                ticket/assignment/operation/outbox 链路
GET  /api/tickets/{id}/work-records
POST /api/tickets/{id}/work-records         诊断、动作、根因、验证、业务回复

POST /api/knowledge/bases
GET  /api/knowledge/bases
POST /api/knowledge/bases/{id}/documents
GET  /api/knowledge/tickets/{ticketId}/documents
POST /api/knowledge/documents/{id}/parse
GET  /api/knowledge/parse-tasks/{id}
GET  /api/knowledge/documents/{id}/chunks
DELETE /api/knowledge/documents/{id}       创建人或 ADMIN，软删除并清理 ES
GET  /api/knowledge/internal/index-tasks/{id} ADMIN，查看 ES 补偿任务
POST /api/rag/ask
POST /api/rag/stream                       POST SSE，真实 Token 流
POST /api/rag/chat                         兼容旧客户端
GET  /api/rag/admin/providers              ADMIN，脱敏配置状态
POST /api/rag/admin/providers/{name}/probe ADMIN，真实连通性诊断
POST /api/knowledge/internal/reindex       ADMIN，全量重新向量化
GET  /api/platform/admin/audits
GET  /api/platform/admin/notifications
PUT  /api/platform/admin/notifications/read-all
GET  /api/platform/monitor/summary
```

各 MVC 服务启用 SpringDoc；经 Gateway 暴露 Swagger 聚合仍是后续项。

## 文档和问答

支持文本型 PDF、DOCX、TXT、MD/Markdown，前后端统一限制单文件最大 10 MB。文件使用 UUID 服务端名称和相对路径保存，并校验扩展名、声明类型及 Tika 检测类型，同时记录 SHA-256。扫描版 PDF 不支持 OCR。工单上传会把 `ticket_id` 写入知识文档，知识库工作台则管理对应业务库下的全部文档。

解析 API 创建 `QUEUED` 任务并发布到 RabbitMQ；消费者最多尝试三次，成功后文档为 `PARSED`、任务为 `SUCCESS`，最终失败时任务为 `FAILED` 且消息进入 DLQ。Tika 文件读取在数据库事务外执行，切片与幂等记录使用短事务保存。

问答默认经过以下链路：

```text
Document → Parse/Chunk → text-embedding-3-small → Elasticsearch dense_vector
Question → 同模型 Embedding → RBAC/文档范围过滤 → TopK/最低相似度
→ 版本化 YAML Prompt → OpenAI / DeepSeek / Kimi → Citation 校验 → Answer + Sources
```

OpenAI 使用 Responses API，DeepSeek 和 Kimi 使用兼容 Chat Completions。`POST /api/rag/stream` 返回 `status`、`token`、`sources`、`done` 或 `error` 事件，前端用 Fetch `ReadableStream` 逐 Token 渲染；最终 `done.answer` 是 Citation 校验后的权威文本。Prompt 位于 `ops-rag-service/src/main/resources/prompts/rag-answer.yml`。返回来源完全由真实检索结果生成；模型声明但检索结果中不存在的 `[chunk:id]` 会被剔除。问题最大 2000 字符，默认 TopK 5、最大 20、上下文 16000 字符。RAG 入口使用 Sentinel `ops-rag-ask` 资源，当前 Nacos 规则为每实例 5 QPS。

`POST /api/rag/ask` 可选传 `documentId`，用于把问答严格限定在用户有权访问的单个文档。文档访问规则为：`PUBLIC` 对已认证用户可见，`PRIVATE` 仅创建者和 ADMIN 可见；权限过滤在内容进入 Prompt 之前完成。

删除文档时数据库立即写入 `deleted=1/status=DELETED`，并创建 `knowledge_index_task`。ES 同步删除失败时任务按指数退避重试，最多 10 次；本地原文件暂时保留，供恢复或后续保留期清理任务使用。

## 配置

配置位于各服务的 `src/main/resources/application.yml`。常用环境变量：

```text
OPS_AUTH_DB_URL / OPS_AUTH_DB_USERNAME / OPS_AUTH_DB_PASSWORD
OPS_TICKET_DB_URL / OPS_TICKET_DB_USERNAME / OPS_TICKET_DB_PASSWORD
OPS_KNOWLEDGE_DB_URL / OPS_KNOWLEDGE_DB_USERNAME / OPS_KNOWLEDGE_DB_PASSWORD
OPS_RAG_DB_URL / OPS_RAG_DB_USERNAME / OPS_RAG_DB_PASSWORD
OPS_PLATFORM_DB_URL / OPS_PLATFORM_DB_USERNAME / OPS_PLATFORM_DB_PASSWORD
OPS_JWT_SECRET                 至少 32 个 UTF-8 字节
OPS_UPLOAD_DIR                 默认 ./data/uploads
OPS_RABBITMQ_HOST / OPS_RABBITMQ_PORT / OPS_RABBITMQ_USERNAME / OPS_RABBITMQ_PASSWORD
NACOS_ENABLED                  默认 false
NACOS_CONFIG_ENABLED           默认 false
NACOS_SERVER_ADDR              默认 localhost:8848
SENTINEL_ENABLED               默认 false
SENTINEL_EAGER                 默认 false
SENTINEL_DASHBOARD             默认 localhost:8858
SENTINEL_RULE_DATA_ID          默认 ops-rag-sentinel-flow-rules
OPS_VECTOR_ENABLED             是否启用 Embedding/向量检索
OPS_ES_URL / OPS_ES_KNOWLEDGE_INDEX / OPS_VECTOR_MINIMUM_SCORE
OPS_AI_ENABLED / OPS_AI_PROVIDER
OPENAI_API_KEY / OPENAI_MODEL / OPENAI_EMBEDDING_MODEL
DEEPSEEK_API_KEY / DEEPSEEK_MODEL
MOONSHOT_API_KEY / MOONSHOT_MODEL
OPS_AI_TIMEOUT_SECONDS / OPS_AI_MAX_ATTEMPTS / OPS_AI_MAX_OUTPUT_TOKENS
```

密钥和生产数据库密码不要写入仓库。PowerShell 本地示例：

```powershell
$env:OPS_JWT_SECRET = '请替换为至少32字节的随机开发密钥'
$env:OPS_AI_PROVIDER = 'openai' # openai、deepseek 或 kimi，修改后重启 RAG 服务
```

本机启动脚本从 `C:\Users\77190\Desktop\api-key.txt` 解析三个 Key，只写入子进程环境且不打印值；还会读取当前 Windows HTTPS 代理供 Java 访问外部 API。仓库、Nacos、数据库、前端和日志均不保存真实 Key。生产环境应改用 Vault、Kubernetes Secret 或云密钥管理服务。

## 数据库与容器

- 全新开发库：Compose 首次创建 MySQL 卷时按文件名自动执行 `sql/01` 至 `sql/09` 和 `sql/init_data.sql`。
- Compose 只会在 MySQL 数据卷首次创建时自动执行上述脚本，不会清空已有库。
- 本地演示账号由 SQL 初始化，账号列表及本地密码只记录在仓库外密码文档中。

`compose.yaml` 只包含中间件：MySQL、Redis、Nacos、Sentinel、RabbitMQ、Elasticsearch、Prometheus 和 Grafana，不包含 Java 服务。

`ops_rag.ai_usage_log` 仅记录用户 ID、Provider、模型、问题 SHA-256、Token、耗时、成功状态和脱敏错误码，不保存问题正文、Prompt、回答或 Key。

解析新文档时，启用向量功能会自动生成 Embedding 并索引。2026-09-03 已在用户明确授权后执行全量重建：22 篇可用文档、25 个切片均使用 OpenAI `text-embedding-3-small`，另有 1 篇损坏 PDF 保持 `FAILED`。今后再次导入内部文档时仍需单独确认数据分级和第三方处理范围；生成阶段会把检索上下文发送给当前 `OPS_AI_PROVIDER`，不要把 Embedding 授权等同于任意生成 Provider 授权。

```powershell
docker compose up -d
docker compose ps
```

推荐使用 `D:\middleware\scripts\start-opsagent.ps1` 启动全部中间件和本机应用；只启动中间件时添加 `-MiddlewareOnly`。停止脚本不会删除 Docker named volumes。

启动脚本会启用 Nacos 服务发现和配置订阅，并把六个服务配置及 `ops-rag-sentinel-flow-rules` 发布到 `DEFAULT_GROUP`。Gateway 的 `lb://` 路由依赖 Nacos 实例列表，因此 Nacos 已被实际使用。RAG 服务订阅 Sentinel FlowRule，`ops-rag-ask` 当前限制为每实例 5 QPS。Nacos 实现规则统一下发，但普通 Sentinel 客户端仍是每实例计数；多实例严格共享总额度需要部署 Sentinel Cluster Token Server。

## 构建和运行

完整构建与启动：

```powershell
.\mvnw.cmd clean verify
D:\middleware\scripts\start-opsagent.ps1
```

后端是六个独立进程，分别运行各模块 `target` 下的可执行 Jar。统一 API 入口为 `http://localhost:8080`。

只有修改前端源码并需要热更新时，才单独启动 Vite：

```powershell
cd ops-web
pnpm install
pnpm dev
```

Vite 开发模式访问 `http://localhost:5173`，并把 `/api` 和 `/actuator` 请求代理到 `http://localhost:8080`。直接访问 `http://localhost:8080/api/auth/login` 时，后端会跳转到同一服务的 `/login`；Axios 等 API 请求未认证时仍返回标准401 JSON。

前端生产构建：

```powershell
cd ops-web
pnpm build
```

构建结果位于 `ops-web/dist`，前端应由 Nginx 或静态站点独立发布；当前 Maven 不会把它打入某个业务服务 Jar。

项目默认启用 `local` Profile，也可以用 `SPRING_PROFILES_ACTIVE` 覆盖。接口访问日志只记录请求方法、路径、响应状态、耗时和 Trace ID，不记录请求体、密码或完整 JWT。

从数据库初始化到完整工单闭环、Nacos/Sentinel 控制台操作和故障排查步骤见 [opsAgent使用文档.md](opsAgent使用文档.md)。手册同时标明了当前前端仍需通过 API 完成的步骤，避免把未接通功能写成已完成。
