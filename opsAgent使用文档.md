# OpsAgent 使用文档

## 1. 系统用途

OpsAgent 用于完成运维问题的最小业务闭环：

```text
普通用户创建工单
→ 运维人员接单
→ 上传并解析故障文档
→ 基于文档进行智能问答
→ 运维人员解决工单
→ 创建人确认关闭
→ 系统生成操作、通知和 AI 任务记录
```

系统采用前后端分离结构：

- 后端：当前仓库根目录，默认端口 `8080`。
- 前端：`opsagent-web`，开发端口 `5173`。
- 数据库：MySQL 8.4。
- Redis：开发环境已准备，但第一阶段业务尚未使用。

## 2. 运行前准备

需要安装：

- JDK 17；
- Docker Desktop，或可用的 MySQL 8；
- Node.js 20.19 或更高版本；
- pnpm 9 或更高版本。

首次运行应先确认端口 `3306`、`6379`、`8080` 和 `5173` 没有被其他程序占用。

## 3. 初始化基础设施和数据库

在项目根目录启动 MySQL 和 Redis：

```powershell
docker compose up -d
```

默认开发配置：

```text
数据库：opsagent
用户名：opsagent
密码：opsagent_local
MySQL 端口：3306
Redis 端口：6379
```

全新数据库执行：

```text
src/main/resources/db/schema.sql
```

该脚本会删除并重建 OpsAgent 相关表，只能用于允许重建的数据库。

`schema.sql` 已包含项目全部表结构和基础角色数据，可以重复执行。每次执行都会删除并重建项目表，不能用于需要保留数据的数据库。

## 4. 启动后端

PowerShell 中配置数据库和 JWT：

```powershell
$env:OPSAGENT_DB_URL = 'jdbc:mysql://localhost:3306/opsagent?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true'
$env:OPSAGENT_DB_USERNAME = 'opsagent'
$env:OPSAGENT_DB_PASSWORD = 'opsagent_local'
$env:OPS_AGENT_JWT_SECRET = '请替换成至少32字节的随机开发密钥'
.\mvnw.cmd spring-boot:run
```

启动成功后：

- 后端地址：`http://localhost:8080`
- 登录页面：`http://localhost:8080/login`
- Swagger：`http://localhost:8080/swagger-ui.html`
- 健康检查：`http://localhost:8080/actuator/health`

JWT 默认有效期为 120 分钟。每次业务请求仍会由后端验证 JWT 的签名、有效期和对应用户状态，并不是登录一次后永久放行。

## 5. 访问前端

默认情况下不需要单独启动前端。IDEA 运行 `OpsagentApplication` 后，Spring Boot 会直接提供 Vue 页面：

```text
http://localhost:8080/login
```

只有修改 Vue 源码并需要热更新时，才打开另一个终端启动 Vite：

打开另一个终端：

```powershell
cd opsagent-web
pnpm install
pnpm dev
```

访问：

```text
http://localhost:5173
```

开发环境已经配置 Vite 代理，前端 `/api` 请求会转发至 `http://localhost:8080`，本地开发不需要额外配置 CORS。
浏览器直接访问后端登录地址或未认证的受保护地址时，会自动跳转到同一后端服务的 `/login`。API 客户端不进行页面跳转，仍通过401 JSON通知前端处理登录状态。

如需让前端直接访问其他后端地址，可以复制 `.env.example` 为 `.env.local`：

```text
VITE_API_BASE_URL=http://目标后端地址:8080
```

此时属于跨域访问，后端或统一反向代理必须配置允许的来源；不要在生产环境使用无限制的 `*` CORS。

## 6. 账号和角色

在登录页选择“创建账号”进行注册。新用户默认获得 `USER` 角色。

角色能力：

| 角色 | 权限 |
|---|---|
| USER | 创建和查看自己的工单、上传文档、提问、关闭自己的已解决工单 |
| OPS | 查看待接工单和自己负责的工单、接单、上传文档、提问、解决工单 |
| ADMIN | 查看全部工单、执行全部状态动作、查看通知、审计和 AI 任务 |

第一阶段没有角色管理页面。需要由数据库管理员分配 `OPS` 或 `ADMIN`，示例：

```sql
INSERT IGNORE INTO sys_user_role (user_id, role_id)
SELECT u.id, r.id
FROM sys_user u
JOIN sys_role r ON r.code = 'OPS'
WHERE u.username = 'ops_user';
```

将 `OPS` 改为 `ADMIN` 即可分配管理员角色。角色变更后，用户应重新登录，以确保前端重新读取当前权限。

建议准备三个测试账号：

```text
requester   USER
operator    OPS
admin       ADMIN
```

## 7. 工单完整操作流程

### 7.1 创建工单

1. 使用 USER 账号登录。
2. 进入“工单中心”。
3. 点击“新建工单”。
4. 填写标题、问题描述和优先级。
5. 创建后状态为“待接单（CREATED）”。

USER 只能查看自己创建的工单；OPS 可以看到所有待接工单和自己负责的工单；ADMIN 可以查看全部工单。

### 7.2 接收工单

1. 退出 USER 账号，使用 OPS 账号登录。
2. 打开待接单工单。
3. 点击“接收工单”。
4. 可填写处理备注并确认。

工单进入 `PROCESSING`，当前 OPS 成为处理人。其他 OPS 不再拥有该工单的数据访问权，ADMIN 除外。

### 7.3 解决工单

当前处理人或 ADMIN 在处理完成后点击“标记已解决”，工单进入 `RESOLVED`。

状态提交后系统会：

- 在主事务中写入工单状态操作记录；
- 事务提交后生成通知记录；
- 生成待处理的工单总结 AI 任务；
- 写入附加审计日志。

### 7.4 关闭工单

使用最初创建工单的 USER 账号登录，打开 `RESOLVED` 工单并点击“确认关闭”。工单进入 `CLOSED`，业务闭环完成。

工单状态是单向流转，不支持从已关闭、已解决状态退回。

## 8. 文档上传与解析

在工单详情的“关联文档”区域：

1. 选择 PDF、DOCX、TXT、MD 或 Markdown 文件。
2. 点击“上传”。
3. 上传成功后状态为 `PENDING`。
4. 点击文档右侧的播放按钮开始解析。
5. 成功后状态变为 `SUCCESS`，可以点击切片按钮查看 Chunk。

限制：

- 单文件默认不超过 50 MB；
- PDF 必须是文本型 PDF；
- 扫描图片 PDF 第一阶段不支持 OCR；
- 不支持 DOC、压缩包和可执行文件；
- 系统会组合校验扩展名、声明 Content-Type 和 Tika 检测结果；
- 本地文件使用 UUID 保存，数据库只记录相对路径和 SHA-256。

解析失败时状态为 `FAILED`，页面会显示简要原因。修正文档后可以重新上传；对于可重试错误，也可以再次点击解析。

## 9. 文档智能问答

问答前至少需要一个状态为 `SUCCESS` 的文档。

1. 在工单详情的“文档智能问答”区域选择检索范围。
2. 可以限定单个文档，也可以检索当前工单的全部已解析文档。
3. 输入问题并点击“提交问题”。
4. 后端读取有限候选 Chunk，执行关键词评分并选择 Top K。
5. 返回答案和引用的文档、Chunk、页码及相关性分数。

默认 `OPS_AGENT_AI_ENABLED=false`，此时不会访问外部模型，只会返回明确的本地占位结果。启用 OpenAI-compatible 服务：

```powershell
$env:OPS_AGENT_AI_ENABLED = 'true'
$env:OPS_AGENT_AI_BASE_URL = 'https://api.openai.com/v1'
$env:OPS_AGENT_AI_API_KEY = '你的API Key'
$env:OPS_AGENT_AI_MODEL = 'gpt-4.1-mini'
.\mvnw.cmd spring-boot:run
```

也可以配置：

```text
OPS_AGENT_AI_TOP_K
OPS_AGENT_AI_CANDIDATE_LIMIT
OPS_AGENT_AI_CONNECT_TIMEOUT
OPS_AGENT_AI_READ_TIMEOUT
```

API Key 只能通过环境变量或安全配置中心提供，不要提交到 Git。

## 10. 管理员功能

ADMIN 登录后左侧会增加：

- 通知中心：查看状态事件生成的通知，并将待处理通知标记为已发送或失败；
- 系统管理 / 操作审计：查看工单状态变化的审计记录；
- 系统管理 / AI 任务：将任务从 `PENDING` 更新到 `PROCESSING`，再更新为 `SUCCESS` 或 `FAILED`。

这些接口同时由 Spring Security 在服务端限制为 ADMIN。隐藏前端菜单不是安全边界，最终权限始终以后端校验为准。

## 11. 构建和生产部署

后端构建：

```powershell
.\mvnw.cmd clean package
```

产物位于：

```text
target/opsagent-0.0.1-SNAPSHOT.jar
```

前端构建：

```powershell
cd opsagent-web
pnpm build
```

产物位于：

```text
opsagent-web/dist
```

生产环境推荐：

```text
浏览器
→ Nginx 提供前端 dist
→ /api 反向代理至 Spring Boot:8080
→ Spring Boot 访问 MySQL
```

前端使用 History 路由，Nginx 对非静态文件路径应回退到 `index.html`。

## 12. 常见问题

### JWT Secret 报错

`OPS_AGENT_JWT_SECRET` 必须至少包含 32 个 UTF-8 字节。生产环境应使用随机生成的高强度密钥。

### 前端提示网络错误

依次检查：

1. 后端是否运行在 `8080`；
2. `http://localhost:8080/actuator/health` 是否可访问；
3. 前端是否通过 `pnpm dev` 启动；
4. 自定义 `VITE_API_BASE_URL` 时是否存在跨域问题。

### 登录后返回 401

可能原因包括 JWT 已过期、签名密钥变化、用户已被禁用或旧 Token 对应用户不存在。退出后重新登录；如果仍失败，检查后端日志。

### 文档没有检索结果

确认文档状态为 `SUCCESS`，并且问题中的关键词确实出现在文档切片中。第一阶段使用关键词评分，不是向量检索。

### 数据库表字段不匹配

说明数据库仍是旧结构。不要直接运行全量脚本覆盖已有数据，应先备份并审核 `phase1_v2_migration.sql`。

## 13. 当前微服务本地环境

当前根 POM 是 `pom` 聚合工程，实际运行的是 Gateway、Auth、Ticket、Knowledge、RAG 和
Platform 六个进程。推荐使用统一脚本：

```powershell
powershell -ExecutionPolicy Bypass -File D:\middleware\scripts\start-opsagent.ps1 -Build
powershell -ExecutionPolicy Bypass -File D:\middleware\scripts\status-opsagent.ps1
powershell -ExecutionPolicy Bypass -File D:\middleware\scripts\stop-opsagent.ps1
```

本地演示账号为 `admin / Admin@123`、`ops / Ops@123`、`user / User@123`。这些密码只允许
在本地环境使用。

## 14. 业务演示场景和数据

`sql/06_scenario_data.sql` 是可重复执行的演示数据脚本，固定使用 `1000` 以上的主键，避免与
接口实时创建的数据冲突。脚本提供以下场景：

| 场景 | 演示目标 | 相关数据或接口 |
|---|---|---|
| 多人并发接单 | 多个运维人员同时提交相同版本，条件更新只允许一个请求成功 | `OPS-SCENE-1001`、`POST /api/tickets/{id}/claim` |
| Redis 命中率下降 | 从缓存命中率、过期集中、淘汰和数据库回源分析故障 | `OPS-SCENE-1002`、知识切片 `1003` |
| Nacos 配置漂移 | 对比 dataId、group、namespace 和实例配置状态 | `OPS-SCENE-1003`、知识切片 `1004` |
| 磁盘容量告警 | 处理上传目录增长，并规划对象存储迁移 | `OPS-SCENE-1004` |
| Sentinel 限流异常 | 分析 429、调用峰值和热点规则 | `OPS-SCENE-1005` |
| 线程池队列积压 | 通过有界线程池、错峰和下游限流恢复 CPU | `OPS-SCENE-1006`、知识切片 `1005` |
| Refresh Token 失效 | 区分令牌过期、撤销和客户端重试问题 | `OPS-SCENE-1007` |
| RAG 降级检索 | 未接 Elasticsearch/LLM 时仍能用 MySQL 文本检索返回引用 | 知识库 `101`—`103`、切片 `1001`—`1006` |

执行方式：

```powershell
D:\middleware\runtime\mysql-8.0.46\bin\mysql.exe -uroot -p2491125 `
  --default-character-set=utf8mb4 < D:\myselfProject\opsagent\sql\06_scenario_data.sql
```

`TicketClaimConcurrencyTest` 使用 Java 17 的固定大小线程池，让 8 个工作线程同时更新同一条
工单，并断言只有一个线程成功。这个测试覆盖数据库原子条件更新和乐观锁，而不是仅演示线程 API。

Java 17 没有正式版虚拟线程。虚拟线程在 Java 21 正式发布，因此当前代码不能使用
`Executors.newVirtualThreadPerTaskExecutor()`。升级到 Java 21 后，可将文档解析等 I/O 密集任务
迁移到虚拟线程；数据库连接池、外部模型并发和上传带宽仍必须使用信号量或限流器设置上限。

## 15. SQL 组织方式

Auth 和 Ticket 服务当前只有少量固定 CRUD 与原子更新，MyBatis 注解 SQL 短且没有动态条件，
继续保留注解比增加 XML 更容易阅读。Knowledge 服务当前使用参数化 `JdbcTemplate`，不是 MyBatis。

出现以下任一情况时应迁移到 `src/main/resources/mapper/*.xml`：

- 多表查询持续增长、单条 SQL 超过约 10—15 行；
- 使用多个动态条件、`foreach`、`choose` 或可复用 SQL 片段；
- 需要复杂 `resultMap`、嵌套映射或由 DBA 独立审查 SQL；
- Java 字符串拼接已经妨碍格式化和执行计划分析。

迁移时设置 `mybatis-plus.mapper-locations=classpath*:/mapper/**/*.xml`。当前 SQL 规模尚未达到
迁移阈值，因此没有为了形式统一而创建空的 XML 映射文件。

## 16. 中间件实际状态

| 组件 | 当前状态 | 说明 |
|---|---|---|
| MySQL | 已运行 | 四个业务库和演示数据均已初始化 |
| Redis | 已运行并接入 | Auth 使用配置化连接，带密码探针 |
| Nacos | 已运行并接入 | 服务注册和六份配置均可验证 |
| Sentinel | 已运行并接入 | 六个应用已注册到 Dashboard |
| Prometheus Server | 已下载并接入 | 抓取 Gateway 与五个业务服务的 `/actuator/prometheus` |
| Grafana | 配置已准备，程序包待补齐 | 官方 CDN 下载速度异常；放入指定目录后启动脚本会自动启用 |
| RabbitMQ | 未接入 | 只有 Outbox 表和事件模型，没有 Publisher、Consumer、重试和死信链路 |
| Elasticsearch | 未接入 | 没有索引映射、写入或查询适配器；Java 客户端应作为 Maven 依赖引入 |
| 外部 LLM | 未接入 | 缺少供应商、模型地址和 API Key，当前使用检索降级回答 |

Prometheus 页面为 `http://127.0.0.1:9090/`，Grafana 默认页面为
`http://127.0.0.1:3000/`。本地 Grafana 初始账号由启动脚本设置为 `admin / opsagent_local`，
实际部署必须修改。
