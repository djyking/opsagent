# opsagent

`opsagent` 是一个前后端分离的运维工单、知识库与智能问答系统。后端已重构为 Java 17 / Spring Boot 3.5 / Spring Cloud 多模块工程；前端位于 `ops-web`，采用 Vue 3、Vite、TypeScript、Pinia 和 Vue Router。

当前已形成“登录 → 工单 → Outbox → RabbitMQ → 平台审计”和“文档上传 → RabbitMQ → 重试/DLQ → 切片 → 检索 → RAG 引用”两条业务闭环。MySQL、Redis、Nacos、Sentinel、RabbitMQ、Elasticsearch、Prometheus 和 Grafana 由 Docker Compose 运行，六个 Java 服务仍在宿主机运行。Elasticsearch 已运行但尚未接入 Java 检索，外部 LLM 未配置时明确使用 MySQL 检索降级。

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

## 角色与权限

- `USER`：创建并查看自己的工单、上传文档、提问、关闭已解决工单。
- `OPS`：查看待处理工单和自己负责的工单，接单、上传文档、提问、解决工单。
- `ADMIN`：访问全部工单，执行所有状态操作，并查看通知、审计日志和 AI 后续任务。

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

POST /api/knowledge/bases/{id}/documents
POST /api/knowledge/documents/{id}/parse
GET  /api/knowledge/parse-tasks/{id}
GET  /api/knowledge/documents/{id}/chunks
POST /api/rag/chat
GET  /api/platform/admin/audits
```

各 MVC 服务启用 SpringDoc；经 Gateway 暴露 Swagger 聚合仍是后续项。

## 文档和问答

支持文本型 PDF、DOCX、TXT、MD/Markdown，默认单文件上限 50 MB。文件使用 UUID 服务端名称和相对路径保存，并校验扩展名、声明类型及 Tika 检测类型，同时记录 SHA-256。扫描版 PDF 不支持 OCR。

解析 API 创建 `QUEUED` 任务并发布到 RabbitMQ；消费者最多尝试三次，成功后文档为 `PARSED`、任务为 `SUCCESS`，最终失败时任务为 `FAILED` 且消息进入 DLQ。Tika 文件读取在数据库事务外执行，切片与幂等记录使用短事务保存。

问答先在当前工单（可选限定单个文档）内读取有限候选切片，再进行中英文关键词评分并选取 Top K。默认不调用外部模型，会明确返回本地占位行为；配置 OpenAI 兼容服务后才会访问 `/chat/completions`。

## 配置

配置位于各服务的 `src/main/resources/application.yml`。常用环境变量：

```text
OPS_AUTH_DB_URL / OPS_AUTH_DB_USERNAME / OPS_AUTH_DB_PASSWORD
OPS_TICKET_DB_URL / OPS_TICKET_DB_USERNAME / OPS_TICKET_DB_PASSWORD
OPS_KNOWLEDGE_DB_URL / OPS_KNOWLEDGE_DB_USERNAME / OPS_KNOWLEDGE_DB_PASSWORD
OPS_PLATFORM_DB_URL / OPS_PLATFORM_DB_USERNAME / OPS_PLATFORM_DB_PASSWORD
OPS_JWT_SECRET                 至少 32 个 UTF-8 字节
OPS_UPLOAD_DIR                 默认 ./data/uploads
OPS_RABBITMQ_HOST / OPS_RABBITMQ_PORT / OPS_RABBITMQ_USERNAME / OPS_RABBITMQ_PASSWORD
NACOS_ENABLED                  默认 false
OPS_ES_ENABLED                 默认 false
OPS_LLM_ENABLED                默认 false
OPS_LLM_BASE_URL / OPS_LLM_API_KEY / OPS_LLM_MODEL
```

密钥和生产数据库密码不要写入仓库。PowerShell 本地示例：

```powershell
$env:OPS_JWT_SECRET = '请替换为至少32字节的随机开发密钥'
```

## 数据库与容器

- 全新开发库：Compose 首次创建 MySQL 卷时按文件名自动执行 `sql/01` 至 `sql/07` 和 `sql/init_data.sql`。
- Compose 只会在 MySQL 数据卷首次创建时自动执行上述脚本，不会清空已有库。
- 本地初始管理员为 `admin / Admin@123`，仅用于开发，部署后必须替换。

`compose.yaml` 只包含中间件：MySQL、Redis、Nacos、Sentinel、RabbitMQ、Elasticsearch、Prometheus 和 Grafana，不包含 Java 服务。

```powershell
docker compose up -d
docker compose ps
```

推荐使用 `D:\middleware\scripts\start-opsagent.ps1` 启动全部中间件和本机应用；只启动中间件时添加 `-MiddlewareOnly`。停止脚本不会删除 Docker named volumes。

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

从数据库初始化到完整工单闭环的操作步骤见 [opsAgent使用文档.md](opsAgent使用文档.md)。
