# opsagent

`opsagent` 是一个前后端分离的运维工单与文档智能问答系统。后端采用 Java 17、Spring Boot、Spring Security、MyBatis-Plus 和 MySQL；前端位于 `opsagent-web`，采用 Vue 3、Vite、TypeScript、Pinia 和 Vue Router。

第一阶段已形成“登录 → 工单 → 文档 → 解析 → 切片 → 检索 → 模型问答 → 引用 → 状态事件”的最小业务闭环。

## 项目结构

```text
opsagent
├─ src                         Spring Boot 后端代码
├─ opsagent-web                Vue 3 前端项目
├─ compose.yaml                MySQL 与 Redis 开发环境
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
POST /api/auth/register
POST /api/auth/login
GET  /api/auth/me

POST /api/tickets
GET  /api/tickets
GET  /api/tickets/{id}
PUT  /api/tickets/{id}
POST /api/tickets/{id}/accept
POST /api/tickets/{id}/resolve
POST /api/tickets/{id}/close

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

完整请求和响应结构可在应用启动后通过 `http://localhost:8080/swagger-ui.html` 查看。

## 文档和问答

支持文本型 PDF、DOCX、TXT、MD/Markdown，默认单文件上限 50 MB。文件使用 UUID 服务端名称和相对路径保存，并校验扩展名、声明类型及 Tika 检测类型，同时记录 SHA-256。扫描版 PDF 不支持 OCR。

解析状态为 `PENDING → PARSING → SUCCESS`，失败时为 `FAILED` 并保存简要原因。Tika 解析和模型调用均在数据库事务外完成，结果使用短事务保存。

问答先在当前工单（可选限定单个文档）内读取有限候选切片，再进行中英文关键词评分并选取 Top K。默认不调用外部模型，会明确返回本地占位行为；配置 OpenAI 兼容服务后才会访问 `/chat/completions`。

## 配置

通用配置位于 `src/main/resources/application.yml`，本地数据库配置位于 `src/main/resources/application-local.yml`。常用环境变量：

```text
OPSAGENT_DB_URL
OPSAGENT_DB_USERNAME
OPSAGENT_DB_PASSWORD
OPS_AGENT_JWT_SECRET          至少 32 个 UTF-8 字节
OPS_AGENT_DOCUMENT_STORAGE    默认 ./data/uploads
OPS_AGENT_AI_ENABLED           默认 false
OPS_AGENT_AI_BASE_URL
OPS_AGENT_AI_API_KEY
OPS_AGENT_AI_MODEL
OPS_AGENT_AI_TOP_K             默认 5
OPS_AGENT_AI_CANDIDATE_LIMIT   默认 200
```

密钥和生产数据库密码不要写入仓库。PowerShell 本地示例：

```powershell
$env:OPS_AGENT_JWT_SECRET = '请替换为至少32字节的随机开发密钥'
```

## 数据库与容器

- 全新开发库：执行 `src/main/resources/db/schema.sql`。该脚本会删除并重建相关表，只能用于允许重建的数据库。
- `schema.sql` 已合并表结构、权限表和基础角色数据，可重复执行；每次执行都会清空并重建项目表。
- 本次代码不会自动执行任何 DDL。

`compose.yaml` 提供 MySQL 8.4 和 Redis 7。Redis 第一阶段只作为后续基础设施，业务代码没有强行使用缓存。

```powershell
docker compose up -d
```

默认端口为 MySQL `3306`、Redis `6379`，容器密码可通过 `OPSAGENT_DB_USERNAME`、`OPSAGENT_DB_PASSWORD` 和 `OPSAGENT_DB_ROOT_PASSWORD` 覆盖。

## 构建和运行

后端：

```powershell
.\mvnw.cmd clean package
.\mvnw.cmd spring-boot:run
```

默认运行方式：只启动 `OpsagentApplication`，然后访问 `http://localhost:8080/login`。Spring Boot 会直接提供已构建的 Vue 页面，不需要再启动5173端口。

只有修改前端源码并需要热更新时，才单独启动 Vite：

```powershell
cd opsagent-web
pnpm install
pnpm dev
```

Vite 开发模式访问 `http://localhost:5173`，并把 `/api` 和 `/actuator` 请求代理到 `http://localhost:8080`。直接访问 `http://localhost:8080/api/auth/login` 时，后端会跳转到同一服务的 `/login`；Axios 等 API 请求未认证时仍返回标准401 JSON。

生产构建：

```powershell
cd opsagent-web
pnpm build
```

构建结果位于 `opsagent-web/dist`，Maven 会将其打入 Spring Boot 可执行 Jar。也可以改用 Nginx 单独发布前端；如果前后端使用不同域名，需要额外配置受控 CORS，并覆盖 `OPS_AGENT_LOGIN_PAGE_URL`。

项目默认启用 `local` Profile，也可以用 `SPRING_PROFILES_ACTIVE` 覆盖。接口访问日志只记录请求方法、路径、响应状态、耗时和 Trace ID，不记录请求体、密码或完整 JWT。

从数据库初始化到完整工单闭环的操作步骤见 [opsAgent使用文档.md](opsAgent使用文档.md)。
