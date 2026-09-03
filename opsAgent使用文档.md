# OpsAgent 操作手册

## 1. 当前系统说明

OpsAgent 当前是一个 Java 17 / Spring Boot 3.5 微服务项目，用于演示企业内部运维工单、知识库、异步消息、审计和检索问答场景。

```text
浏览器（5173）
  → Gateway（8080）
    → Auth（8101）
    → Ticket（8102）
    → Knowledge（8103）
    → RAG（8104）
    → Platform（8105）
```

Java 服务和 Vue 前端运行在 Windows 宿主机。MySQL、Redis、Nacos、Sentinel、RabbitMQ、Elasticsearch、Prometheus、Grafana 由 Docker Compose 运行。

当前两条主要闭环是：

```text
工单操作 → 事务内 Outbox → RabbitMQ → Platform 消费幂等 → 操作审计
文档上传 → RabbitMQ 异步解析 → 重试/DLQ → 文本切片 → RAG 检索引用
```

本地地址、账号和密码统一保存在仓库外的 `D:\middleware\docs\OpsAgent本地地址与密码.md`。不要把该文件复制到项目或提交到 GitHub。

## 2. 运行前准备

- Windows 已启用 WSL2；
- Docker Desktop 已安装并启动；
- JDK 17 位于 `D:\jdk17.0.19\jdk-17.0.19`；
- 项目位于 `D:\myselfProject\opsagent`；
- 中间件目录位于 `D:\middleware`。

启动前确保端口 `3000`、`3306`、`5173`、`5672`、`6379`、`8080`、`8101`—`8105`、`8848`、`8849`、`8858`、`9090`、`9200`、`9848`、`15672` 未被其他程序占用。

## 3. 启动、查看状态和停止

首次启动或 Java 代码修改后，先停止旧进程，再构建并启动：

```powershell
powershell -ExecutionPolicy Bypass -File D:\middleware\scripts\stop-opsagent.ps1
powershell -ExecutionPolicy Bypass -File D:\middleware\scripts\start-opsagent.ps1 -Build
```

必须先停止旧 Java 进程，因为 Windows 会锁定正在运行的 JAR，直接重新打包可能失败。

代码没有变化时直接启动：

```powershell
powershell -ExecutionPolicy Bypass -File D:\middleware\scripts\start-opsagent.ps1
```

只启动中间件：

```powershell
powershell -ExecutionPolicy Bypass -File D:\middleware\scripts\start-opsagent.ps1 -MiddlewareOnly
```

查看状态或停止全部本地进程和容器：

```powershell
powershell -ExecutionPolicy Bypass -File D:\middleware\scripts\status-opsagent.ps1
powershell -ExecutionPolicy Bypass -File D:\middleware\scripts\stop-opsagent.ps1
```

停止脚本不会执行 `docker compose down -v`，因此不会删除数据库和其他 named volume。

## 4. 初始化数据库和企业演示数据

Compose 第一次创建 MySQL volume 时，会按文件名执行 `sql` 目录中的初始化脚本。已有 volume 再次启动时不会重新执行，也不会清空数据库。

需要重新生成企业演示文件、导入约定 ID 区间的数据并通过 HTTP 上传 18 篇 Runbook 时，在项目根目录执行：

```powershell
cd D:\myselfProject\opsagent
powershell -ExecutionPolicy Bypass -File .\demo-data\scripts\Initialize-EnterpriseDemo.ps1
```

该脚本还会上传一个故意损坏的 `broken-demo.pdf`，用于验证三次消费尝试和知识解析 DLQ。看到 1 条对应死信属于预期结果。

不要为了重复初始化而删除 Docker volume。需要迁移或重建前，先使用 `mysqldump` 备份四个业务库。

## 5. 登录、注册和角色

打开 `http://127.0.0.1:5173/`。演示账号的密码见仓库外密码文档。

| 角色 | 当前权限 |
|---|---|
| USER | 注册、登录、创建和查看自己的工单、关闭自己创建的已解决工单 |
| OPS | 查看待接工单和自己负责的工单、接单、处理和解决工单 |
| ADMIN | 查看全部工单、执行状态操作、访问管理员接口 |

登录页可以进入注册页。新注册用户默认是 `USER`，角色变更需要修改 `ops_auth.sys_user_role` 后重新登录。

页面左侧菜单包括：运行总览、工单中心、知识库、智能问答和系统监控；ADMIN 还会看到通知中心和系统管理。

## 6. 工单操作

### 6.1 页面快速演示

1. 使用 USER 登录，进入“工单中心”，点击“新建工单”。
2. 填写标题、描述和优先级，创建后状态为 `CREATED`。
3. 退出后使用 OPS 登录，打开该工单并点击“接收工单”，状态变为 `ASSIGNED`。
4. 将工单推进到 `PROCESSING` 后，处理人可点击“标记已解决”，状态变为 `RESOLVED`。
5. 使用原创建人登录，点击“确认关闭”，状态变为 `CLOSED`。
6. 工单详情右侧“状态时间线”可查看操作人、前后状态和备注。

状态机允许的完整流转为：

```text
CREATED → ASSIGNED → PROCESSING → RESOLVED → CLOSED
                     ├→ SUSPENDED → PROCESSING
                     ├→ WAITING_CONFIRM → PROCESSING / RESOLVED
                     └→ REJECTED
```

当前前端已实现创建、接单、解决和关闭按钮，但尚未提供 `ASSIGNED → PROCESSING` 的按钮。该一步暂时使用 API：

```powershell
$gateway = 'http://127.0.0.1:8080'
$username = Read-Host 'OPS 用户名'
$password = Read-Host 'OPS 密码'
$loginBody = @{ username = $username; password = $password } | ConvertTo-Json
$login = Invoke-RestMethod -Method Post -Uri "$gateway/api/auth/login" -ContentType 'application/json' -Body $loginBody
$headers = @{ Authorization = "Bearer $($login.data.accessToken)" }
$ticketId = Read-Host '工单 ID'
$ticket = Invoke-RestMethod -Uri "$gateway/api/tickets/$ticketId" -Headers $headers
$body = @{ target = 'PROCESSING'; version = $ticket.data.version; remark = '开始处理' } | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri "$gateway/api/tickets/$ticketId/transition" -Headers $headers -ContentType 'application/json' -Body $body
```

接单和状态更新都带版本条件。多人或多实例同时接单时，只有一个请求能成功，其他请求返回冲突。

### 6.2 工单事件和审计

创建、接单和状态流转会在同一数据库事务内写入 `ops_ticket.event_outbox`。Publisher 在后台发送 `ticket.#` 事件，使用 Publisher Confirm、指数退避和超时 `PUBLISHING` 回收；Platform 消费端按 `eventId` 幂等写审计。

管理员可通过以下接口查看跨服务审计：

```text
GET /api/platform/admin/audits?bizId={工单ID}&limit=50
```

当前“系统管理”页面仍使用旧版通知、审计和 AI 任务接口，尚未改为展示 `/api/platform/admin/audits`；需要查看新的 RabbitMQ 审计时，应使用上述接口或数据库。

## 7. 文档上传与异步解析

工单详情中的“关联文档”区域目前实际读写的是知识库 `1`，并未在数据库中建立真正的“工单—文档”关联。这是当前前端适配层的已知限制。

页面操作：

1. 在工单详情选择 PDF、DOCX、TXT、MD 或 Markdown 文件；
2. 点击“上传”；
3. 点击播放图标提交异步解析任务；
4. 等待数秒后刷新页面；
5. 状态为 `SUCCESS` 时点击切片图标查看 Chunk。

后端真实状态使用 `UPLOADED`、`PARSING`、`PARSED`、`FAILED`；前端会把 `PARSED` 映射为 `SUCCESS`。单文件默认上限 50 MB，扫描型 PDF 尚不支持 OCR。

解析消息进入 `ops.knowledge.parse.queue`。失败最多消费三次，最终进入 `ops.knowledge.parse.dlq`。任务状态可通过以下接口查询：

```text
GET /api/knowledge/parse-tasks/{taskId}
```

“知识库”页面目前支持创建和查看知识库；任意知识库的文档上传、列表和解析可以通过 `/api/knowledge/bases/{id}/documents` 等接口完成。

## 8. 智能问答

进入“智能问答”，输入问题并提交。也可以在工单详情的“文档智能问答”区域提问。

当前默认返回 `retrieval-fallback`：RAG 服务调用 Knowledge 服务的 MySQL 文本检索，返回命中的文档和 Chunk 引用，但不调用外部 LLM。Elasticsearch 容器已运行，Java 索引和检索尚未接入。

建议演示问题：

```text
大促期间订单接口出现 429 和超时应该如何排查？
RabbitMQ 消息积压时应检查哪些指标？
Redis 命中率下降时如何确认是否发生缓存雪崩？
```

## 9. Nacos 操作与实际接入程度

控制台：`http://127.0.0.1:8849/`

Nacos 已实际使用，不只是启动了容器：

- 六个 Java 服务注册到 Nacos；
- Gateway 使用 `lb://服务名` 和 Nacos 服务发现转发请求；
- 六个服务订阅各自的 `{spring.application.name}.yaml`；
- 启动脚本自动运行 `publish-nacos-config.ps1` 发布六个 Data ID。

验证方式：

1. 打开控制台“服务管理”，应看到六个 `ops-*` 服务；
2. 打开“配置管理”，选择 `DEFAULT_GROUP`，应看到六个 `*.yaml`；
3. 执行状态脚本后，再访问任意业务页面验证 Gateway 路由。

当前发布到 Nacos 的内容主要是连通性标识和管理信息，数据库、MQ 等核心参数仍主要通过环境变量和本地 `application.yml` 提供。Nacos 已接入，但配置中心的业务化程度仍有限。

## 10. Sentinel 操作与实际接入程度

控制台：`http://127.0.0.1:8858/`，登录凭据见仓库外密码文档。

Sentinel 客户端已接入六个 Java 服务。启动脚本启用 eager 模式并设置 Dashboard 地址；实测 Dashboard 能看到六个健康应用实例。

使用方法：

1. 先通过页面或 API 访问各服务，产生资源调用；
2. 登录 Sentinel；
3. 左侧选择 `ops-gateway`、`ops-auth-service`、`ops-ticket-service`、`ops-knowledge-service`、`ops-rag-service` 或 `ops-platform-service`；
4. 查看实时监控、簇点链路和机器列表。

目前没有配置正式的流控、熔断、热点或授权规则，也没有将规则持久化到 Nacos。也就是说，Sentinel 已完成客户端和 Dashboard 接入，可观测，但尚未形成生产级治理策略。直接在 Dashboard 创建的规则可能在应用或 Dashboard 重启后丢失。

## 11. RabbitMQ、Prometheus、Grafana 和 Elasticsearch

- RabbitMQ 管理页可查看 `ops.platform.audit.queue`、`ops.platform.audit.dlq`、`ops.knowledge.parse.queue` 和 `ops.knowledge.parse.dlq`。
- Prometheus 的 `/targets` 页面应显示六个 Java job 全部 `UP`。
- Grafana 已预置 `OpsAgent Overview`，包含 Outbox、MQ 消费和文档解析指标。
- Elasticsearch `_cluster/health` 当前应为 green，但业务检索尚未使用 Elasticsearch。

所有地址和登录凭据见仓库外密码文档。

## 12. 构建和代码检查

后端完整检查：

```powershell
cd D:\myselfProject\opsagent
.\mvnw.cmd verify
```

前端检查：

```powershell
cd D:\myselfProject\opsagent\ops-web
& 'C:\Users\77190\.cache\codex-runtimes\codex-primary-runtime\dependencies\bin\fallback\pnpm.cmd' build
```

后端构建包含 Checkstyle。当前并发接单测试使用 8 个 Java 17 平台线程验证数据库条件更新；Java 17 没有正式虚拟线程 API，虚拟线程需要升级到 Java 21。

## 13. 日志、数据和排障

- Java/前端日志：`D:\middleware\logs`；
- PID 文件：`D:\middleware\data`；
- 上传文件：`D:\myselfProject\opsagent\data\uploads`；
- Docker 数据：Compose named volumes；
- 数据库备份：`D:\middleware\backups`。

常见检查顺序：

1. 执行 `status-opsagent.ps1`；
2. 执行 `docker compose ps`；
3. 查看对应 Java 服务的 `*.stdout.log` 和 `*.stderr.log`；
4. 在 Prometheus `/targets` 检查抓取状态；
5. 在 Nacos 检查服务实例；
6. 在 RabbitMQ 检查主队列、消费者和 DLQ；
7. 最后检查 MySQL 中的 Outbox、解析任务和消费幂等表。

RabbitMQ 临时不可用时，工单主事务仍可成功，Outbox 会记录失败并自动重试。历史日志中的连接失败需要结合时间判断；只要 RabbitMQ 已恢复、主队列无积压且 Outbox 全部为 `SENT`，就不属于当前故障。

## 14. 当前未完成项

| 项目 | 当前情况 |
|---|---|
| 外部 LLM | 未配置供应商、模型地址和 API Key |
| Elasticsearch Java 检索 | 容器已运行，应用仍使用 MySQL fallback |
| Embedding/向量检索 | 尚未选择模型和向量维度 |
| Sentinel 持久化规则 | 客户端已接入，尚无生产流控/熔断规则和 Nacos 持久化 |
| OCR | 扫描 PDF 需要 Tesseract 或云 OCR |
| 真正的工单附件关联 | 当前工单页固定使用知识库 1 |
| 管理页面适配 | 新 Platform MQ 审计已提供 API，前端系统管理页尚未切换 |
| Java 虚拟线程 | Java 17 不支持正式 API，需升级 Java 21 |
| Alertmanager | 缺少 SLO、联系人和企业通知渠道 |
