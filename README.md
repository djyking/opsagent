# opsagent

`opsagent` 是一个前后端分离的运维工单、知识库与智能问答系统。后端已重构为 Java 17 / Spring Boot 3.5 / Spring Cloud 多模块工程；前端位于 `ops-web`，采用 Vue 3、Vite、TypeScript、Pinia 和 Vue Router。

当前已形成“登录 → 工单 → 文档 → 解析 → 切片 → 检索 → 问答 → 引用 → 状态事件”的本地最小闭环。Redis、RabbitMQ、Elasticsearch、Nacos Config 和外部 LLM 默认关闭，属于可接入项，不能视为生产链路已经联调。原根目录 `src` 保存重构前未提交的单体代码作为迁移参照，不参与新的聚合构建。

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
├─ sql                        分库初始化脚本
├─ compose.yaml               MySQL、Redis 与可选中间件
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

POST   /api/tickets/{ticketId}/documents
GET    /api/tickets/{ticketId}/documents
GET    /api/documents/{id}
POST   /api/documents/{id}/parse
GET    /api/documents/{id}/chunks
DELETE /api/documents/{id}

POST /api/tickets/{ticketId}/questions
GET  /api/tickets/{ticketId}/questions
GET  /api/questions/{id}
```

各 MVC 服务启用 SpringDoc；经 Gateway 暴露 Swagger 聚合仍是后续项。

## 文档和问答

支持文本型 PDF、DOCX、TXT、MD/Markdown，默认单文件上限 50 MB。文件使用 UUID 服务端名称和相对路径保存，并校验扩展名、声明类型及 Tika 检测类型，同时记录 SHA-256。扫描版 PDF 不支持 OCR。

解析状态为 `PENDING → PARSING → SUCCESS`，失败时为 `FAILED` 并保存简要原因。Tika 解析和模型调用均在数据库事务外完成，结果使用短事务保存。

问答先在当前工单（可选限定单个文档）内读取有限候选切片，再进行中英文关键词评分并选取 Top K。默认不调用外部模型，会明确返回本地占位行为；配置 OpenAI 兼容服务后才会访问 `/chat/completions`。

## 配置

配置位于各服务的 `src/main/resources/application.yml`。常用环境变量：

```text
OPS_AUTH_DB_URL / OPS_AUTH_DB_USERNAME / OPS_AUTH_DB_PASSWORD
OPS_TICKET_DB_URL / OPS_TICKET_DB_USERNAME / OPS_TICKET_DB_PASSWORD
OPS_KNOWLEDGE_DB_URL / OPS_KNOWLEDGE_DB_USERNAME / OPS_KNOWLEDGE_DB_PASSWORD
OPS_JWT_SECRET                 至少 32 个 UTF-8 字节
OPS_UPLOAD_DIR                 默认 ./data/uploads
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

- 全新开发库：按顺序执行 `sql/01_ops_auth.sql` 至 `sql/05_ops_mq.sql`，最后执行 `sql/init_data.sql`。
- Compose 只会在 MySQL 数据卷首次创建时自动执行上述脚本，不会清空已有库。
- 本地初始管理员为 `admin / Admin@123`，仅用于开发，部署后必须替换。

`compose.yaml` 默认提供 MySQL 8.4 和 Redis 7；`middleware` Profile 另提供 Nacos、RabbitMQ 和 Elasticsearch。

```powershell
docker compose up -d
docker compose --profile middleware up -d
```

默认端口为 MySQL `3306`、Redis `6379`，容器密码可通过 `OPSAGENT_DB_USERNAME`、`OPSAGENT_DB_PASSWORD` 和 `OPSAGENT_DB_ROOT_PASSWORD` 覆盖。

## 构建和运行

后端：

```powershell
.\mvnw.cmd clean package
.\mvnw.cmd clean package
```

后端是六个独立进程，分别运行各模块 `target` 下的可执行 Jar。统一 API 入口为 `http://localhost:8080`。

只有修改前端源码并需要热更新时，才单独启动 Vite：

```powershell
cd ops-web
pnpm install
pnpm dev
```

Vite 开发模式访问 `http://localhost:5173`，并把 `/api` 和 `/actuator` 请求代理到 `http://localhost:8080`。直接访问 `http://localhost:8080/api/auth/login` 时，后端会跳转到同一服务的 `/login`；Axios 等 API 请求未认证时仍返回标准401 JSON。

生产构建：

```powershell
cd opsagent-web
pnpm build
```

构建结果位于 `ops-web/dist`，前端应由 Nginx 或静态站点独立发布；当前 Maven 不会把它打入某个业务服务 Jar。

项目默认启用 `local` Profile，也可以用 `SPRING_PROFILES_ACTIVE` 覆盖。接口访问日志只记录请求方法、路径、响应状态、耗时和 Trace ID，不记录请求体、密码或完整 JWT。

从数据库初始化到完整工单闭环的操作步骤见 [opsAgent使用文档.md](opsAgent使用文档.md)。
