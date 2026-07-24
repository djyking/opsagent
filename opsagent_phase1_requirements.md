# OpsAgent 第一阶段需求文档：核心可运行版本

## 1. 项目定位

OpsAgent 是一个面向企业运维场景的 Java 后端 + AI 应用实践项目。

项目最终目标是建设一个“企业智能运维知识库与工单 Agent 平台”，用于模拟企业内部工单处理、文档知识库、AI 问答、RAG 检索、异步任务、监控治理等场景。

本需求文档只覆盖第一阶段。

---

## 2. 第一阶段目标

第一阶段目标是完成一个**核心可运行版本**，重点不是功能复杂，而是把主流程跑通。

本阶段需要完成：

- 用户登录；
- 工单管理；
- 文档上传；
- 文档解析；
- 文本切分；
- MySQL 存储；
- 调用大模型问答；
- README；
- Docker Compose 启动 MySQL / Redis；
- 使用设计模式，尤其是观察者模式。

第一阶段完成后，系统需要具备一个最小业务闭环：

```text
用户登录
  ↓
创建工单
  ↓
为工单上传运维文档
  ↓
保存文件并创建文档记录
  ↓
异步或同步解析文档
  ↓
文本清洗、切分并保存 Chunk
  ↓
用户针对文档提问
  ↓
查询相关 Chunk，调用大模型回答
  ↓
更新工单状态
  ↓
发布工单状态变更事件
  ↓
通知、业务日志、AI任务等监听器分别处理
```

---

## 3. 第一阶段不做的内容

第一阶段暂不实现以下内容：

- Elasticsearch；
- 向量库；
- Embedding；
- 正式 RAG 检索增强；
- MQ 异步任务；
- 线程池隔离；
- 分布式锁；
- 幂等复杂治理；
- 失败重试机制；
- XXL-JOB / Quartz；
- Nginx；
- Sentinel / Resilience4j；
- Prometheus；
- Grafana；
- 模型调用网关；
- token 成本统计；
- 降级策略；
- 压测和造数脚本。

这些内容放到第二、三、四阶段。

---

## 4. 业务背景

企业运维场景中，技术人员经常需要处理以下问题：

- 服务接口超时；
- 定时任务失败；
- Redis 连接异常；
- MQ 消息堆积；
- 数据同步异常；
- 服务器资源异常；
- 业务系统报错；
- 新人不知道如何查询历史处理方案。

传统方式下，运维人员需要手动查询文档、历史工单、处理记录和日志，处理效率较低。

第一阶段先实现一个简化版本：

- 用户可以登录系统；
- 用户可以创建和管理工单；
- 用户可以上传运维文档；
- 系统可以解析文档并切分文本；
- 用户可以基于上传的文档内容向大模型提问；
- 工单状态变化时，系统自动触发日志、通知、AI 总结任务等后续动作。

---

## 5. 核心业务流程

### 5.1 用户登录流程

```text
用户输入账号密码
  ↓
系统校验用户
  ↓
登录成功后返回 token
  ↓
后续接口携带 token 访问
```

第一阶段可以使用简化登录，不要求完整 RBAC 权限系统。

---

### 5.2 工单处理流程

```text
用户创建工单
  ↓
工单初始状态为 OPEN
  ↓
处理人将工单修改为 PROCESSING
  ↓
问题处理完成后修改为 RESOLVED
  ↓
确认无问题后修改为 CLOSED
```

工单状态变化时，需要发布状态变更事件。

---

### 5.3 文档处理流程

```text
用户上传文档
  ↓
系统保存文档元数据
  ↓
解析文档文本内容
  ↓
将文本切分成多个 chunk
  ↓
chunk 存入 MySQL
  ↓
用户可以基于文档内容进行问答
```

第一阶段文档 chunk 只存 MySQL，不接入向量库和 Elasticsearch。

---

### 5.4 大模型问答流程

```text
用户提出问题
  ↓
系统从 MySQL 查询相关文档 chunk
  ↓
拼接上下文和用户问题
  ↓
调用大模型接口
  ↓
返回答案
  ↓
记录问答日志
```

第一阶段不要求真正实现完整 RAG，只需要实现“基于已上传文档内容的简化问答”。

---

## 6. 功能需求

### 6.1 用户模块

#### 6.1.1 用户登录

接口：

```text
POST /api/auth/login
```

请求字段：

| 字段 | 说明 |
|---|---|
| username | 用户名 |
| password | 密码 |

响应字段：

| 字段 | 说明 |
|---|---|
| token | 登录凭证 |
| username | 用户名 |
| displayName | 用户显示名 |

第一阶段可以使用简单 token，例如 UUID token 存 Redis。

---

#### 6.1.2 用户信息查询

接口：

```text
GET /api/auth/me
```

功能：

- 根据 token 查询当前用户信息；
- 校验 token 是否有效。

---

### 6.2 工单模块

#### 6.2.1 创建工单

接口：

```text
POST /api/tickets
```

请求字段：

| 字段 | 说明 |
|---|---|
| title | 工单标题 |
| description | 工单描述 |
| priority | 优先级 |
| creator | 创建人 |
| assignee | 处理人 |

默认状态：

```text
OPEN
```

---

#### 6.2.2 查询工单详情

接口：

```text
GET /api/tickets/{id}
```

返回内容：

- 工单 ID；
- 标题；
- 描述；
- 优先级；
- 当前状态；
- 创建人；
- 处理人；
- 创建时间；
- 更新时间。

---

#### 6.2.3 分页查询工单

接口：

```text
GET /api/tickets
```

查询条件：

| 参数 | 说明 |
|---|---|
| status | 工单状态 |
| priority | 优先级 |
| keyword | 标题或描述关键词 |
| pageNum | 页码 |
| pageSize | 每页大小 |

---

#### 6.2.4 修改工单状态

接口：

```text
PUT /api/tickets/{id}/status
```

请求字段：

| 字段 | 说明 |
|---|---|
| targetStatus | 目标状态 |
| operator | 操作人 |
| reason | 状态变更原因 |

状态枚举：

| 状态 | 说明 |
|---|---|
| OPEN | 待处理 |
| PROCESSING | 处理中 |
| RESOLVED | 已解决 |
| CLOSED | 已关闭 |

基础状态流转规则：

```text
OPEN → PROCESSING
PROCESSING → RESOLVED
RESOLVED → CLOSED
```

可选支持：

```text
PROCESSING → OPEN
RESOLVED → PROCESSING
```

状态修改成功后，必须发布工单状态变更事件。

---

#### 6.2.5 查询工单状态变更日志

接口：

```text
GET /api/tickets/{id}/status-logs
```

返回内容：

- 工单 ID；
- 原状态；
- 新状态；
- 操作人；
- 变更原因；
- 变更时间。

---

### 6.3 文档模块

#### 6.3.1 上传文档

接口：

```text
POST /api/documents/upload
```

支持类型：

- txt；
- md；
- docx 可选；
- pdf 可选。

第一阶段建议先支持 txt / md，降低实现复杂度。

上传后需要保存：

- 文件名；
- 文件类型；
- 文件大小；
- 上传人；
- 文档状态；
- 创建时间。

---

#### 6.3.2 文档解析

上传成功后，系统需要解析文本内容。

第一阶段可以同步解析，不需要 MQ。

文档状态：

| 状态 | 说明 |
|---|---|
| UPLOADED | 已上传 |
| PARSED | 已解析 |
| FAILED | 解析失败 |

---

#### 6.3.3 文本切分

文档解析完成后，需要将文本切分成 chunk。

chunk 字段：

| 字段 | 说明 |
|---|---|
| documentId | 文档 ID |
| chunkIndex | chunk 序号 |
| content | chunk 内容 |
| tokenEstimate | 预估 token 数，可选 |
| createdAt | 创建时间 |

第一阶段切分规则可以简单实现：

- 按固定字符长度切分；
- 每段 500—1000 字；
- 暂不做复杂语义切分。

---

#### 6.3.4 查询文档列表

接口：

```text
GET /api/documents
```

查询条件：

- fileName；
- status；
- uploader；
- pageNum；
- pageSize。

---

#### 6.3.5 查询文档 chunk

接口：

```text
GET /api/documents/{id}/chunks
```

返回指定文档的文本切片。

---

### 6.4 大模型问答模块

#### 6.4.1 基于文档问答

接口：

```text
POST /api/ai/chat
```

请求字段：

| 字段 | 说明 |
|---|---|
| question | 用户问题 |
| documentId | 指定文档 ID，可选 |
| topN | 取前 N 个 chunk，可选 |

处理逻辑：

1. 接收用户问题；
2. 从 MySQL 查询相关文档 chunk；
3. 第一阶段可以用简单 keyword 匹配或取指定文档前几个 chunk；
4. 拼接上下文；
5. 调用大模型；
6. 返回模型答案；
7. 记录问答日志。

---

#### 6.4.2 查询问答日志

接口：

```text
GET /api/ai/chat-logs
```

返回内容：

- 问题；
- 模型答案；
- 使用的文档 ID；
- 调用耗时；
- 创建时间。

---

## 7. 设计模式要求

第一阶段硬性要求使用设计模式，尤其是观察者模式。

---

### 7.1 观察者模式：工单状态变更事件

#### 7.1.1 使用场景

当工单状态发生变化时，系统需要自动触发多个后续动作：

1. 记录状态变更日志；
2. 记录操作审计日志；
3. 生成通知记录；
4. 如果工单状态变为 RESOLVED，创建 AI 总结任务。

这些动作不应该全部写死在 TicketService 中。

因此必须使用观察者模式，将工单状态变更抽象为事件，由多个监听器分别处理。

---

#### 7.1.2 事件定义

事件名称：

```text
TicketStatusChangedEvent
```

事件字段：

| 字段 | 说明 |
|---|---|
| ticketId | 工单 ID |
| title | 工单标题 |
| fromStatus | 原状态 |
| toStatus | 新状态 |
| operator | 操作人 |
| assignee | 处理人 |
| reason | 变更原因 |
| changedAt | 变更时间 |

---

#### 7.1.3 事件发布者

发布者：

```text
TicketService
```

发布时机：

```text
工单状态更新成功后发布事件
```

要求：

- TicketService 可以依赖 ApplicationEventPublisher；
- TicketService 不允许直接调用具体监听器；
- TicketService 不应该直接调用 AuditLogService、NotificationService、AiTaskService 去完成事件后续动作；
- 后续新增监听器时，不应该修改 TicketService 主逻辑。

---

#### 7.1.4 事件监听器

第一阶段至少实现 3 个监听器。

##### A. AuditLogListener

职责：

- 监听 TicketStatusChangedEvent；
- 写入 operation_log 表；
- 记录工单状态变更操作。

记录示例：

```text
用户 admin 将工单 #1001 从 OPEN 修改为 PROCESSING，原因：开始处理故障。
```

---

##### B. NotificationListener

职责：

- 监听 TicketStatusChangedEvent；
- 写入 notification_record 表；
- 模拟通知工单处理人或创建人。

通知内容示例：

```text
工单【Redis 连接超时】状态已从 OPEN 变更为 PROCESSING，请及时关注。
```

第一阶段只写数据库记录，不需要真实邮件、短信、WebSocket。

---

##### C. AiSummaryTaskListener

职责：

- 监听 TicketStatusChangedEvent；
- 当新状态为 RESOLVED 时，创建 AI 总结任务；
- 写入 ai_task 表。

业务含义：

工单解决后，后续可以调用大模型生成故障总结、原因分析和处理建议。第一阶段只创建任务记录，不真正执行总结。

---

#### 7.1.5 观察者模式验收要求

必须满足：

1. 工单状态变更后能发布 TicketStatusChangedEvent；
2. 至少 3 个 Listener 能监听并处理该事件；
3. TicketService 不直接调用具体监听器；
4. 新增监听器时不需要修改 TicketService；
5. Listener 职责单一；
6. Listener 执行失败时不能影响工单状态变更主流程；
7. 代码中能清晰说明这是观察者模式的应用。

---

### 7.2 策略模式：文档解析策略

除观察者模式外，建议使用策略模式处理不同文档类型。

#### 7.2.1 使用场景

不同文件类型的解析方式不同：

- txt 直接读取文本；
- md 读取 Markdown 文本；
- docx 需要专门解析；
- pdf 需要 PDF 解析器。

第一阶段可以先实现 txt / md，但代码结构要预留扩展能力。

---

#### 7.2.2 策略接口

建议定义：

```text
DocumentParser
```

方法：

```text
boolean supports(String fileType)

String parse(MultipartFile file)
```

实现类：

```text
TxtDocumentParser
MarkdownDocumentParser
```

后续可以扩展：

```text
PdfDocumentParser
DocxDocumentParser
```

---

#### 7.2.3 策略模式验收要求

必须满足：

1. 文档解析不能全部写死在 Controller；
2. 不同文档类型由不同 Parser 处理；
3. 新增文件类型时，尽量不修改原有解析主流程；
4. 第一阶段至少实现 txt 解析；
5. md 解析可选，但建议实现。

---

### 7.3 模板方法模式：大模型调用流程，可选

第一阶段可选使用模板方法模式封装大模型调用流程。

通用流程：

```text
构建 prompt
  ↓
调用模型
  ↓
解析结果
  ↓
记录日志
  ↓
返回响应
```

如果第一阶段时间紧，可以先不强制实现模板方法模式，但需要保证模型调用代码不要散落在 Controller 中。

---

## 8. 数据库设计

### 8.1 user 用户表

| 字段 | 类型 | 说明 |
|---|---|---|
| id | bigint | 主键 |
| username | varchar | 用户名 |
| password | varchar | 密码 |
| display_name | varchar | 显示名 |
| status | varchar | 状态 |
| created_at | datetime | 创建时间 |
| updated_at | datetime | 更新时间 |

---

### 8.2 ticket 工单表

| 字段 | 类型 | 说明 |
|---|---|---|
| id | bigint | 主键 |
| title | varchar | 工单标题 |
| description | text | 工单描述 |
| priority | varchar | 优先级 |
| status | varchar | 当前状态 |
| creator | varchar | 创建人 |
| assignee | varchar | 处理人 |
| created_at | datetime | 创建时间 |
| updated_at | datetime | 更新时间 |

---

### 8.3 ticket_status_log 工单状态日志表

| 字段 | 类型 | 说明 |
|---|---|---|
| id | bigint | 主键 |
| ticket_id | bigint | 工单 ID |
| from_status | varchar | 原状态 |
| to_status | varchar | 新状态 |
| operator | varchar | 操作人 |
| reason | varchar | 变更原因 |
| created_at | datetime | 创建时间 |

---

### 8.4 document 文档表

| 字段 | 类型 | 说明 |
|---|---|---|
| id | bigint | 主键 |
| file_name | varchar | 文件名 |
| file_type | varchar | 文件类型 |
| file_size | bigint | 文件大小 |
| storage_path | varchar | 文件存储路径 |
| status | varchar | 状态 |
| uploader | varchar | 上传人 |
| created_at | datetime | 创建时间 |
| updated_at | datetime | 更新时间 |

---

### 8.5 document_chunk 文档切片表

| 字段 | 类型 | 说明 |
|---|---|---|
| id | bigint | 主键 |
| document_id | bigint | 文档 ID |
| chunk_index | int | 切片序号 |
| content | text | 切片内容 |
| token_estimate | int | 预估 token 数 |
| created_at | datetime | 创建时间 |

---

### 8.6 ai_chat_log AI 问答日志表

| 字段 | 类型 | 说明 |
|---|---|---|
| id | bigint | 主键 |
| question | text | 用户问题 |
| answer | text | 模型答案 |
| document_id | bigint | 关联文档 ID |
| used_chunks | text | 使用的 chunk ID |
| cost_time_ms | bigint | 调用耗时 |
| created_at | datetime | 创建时间 |

---

### 8.7 operation_log 操作审计日志表

| 字段 | 类型 | 说明 |
|---|---|---|
| id | bigint | 主键 |
| biz_type | varchar | 业务类型 |
| biz_id | bigint | 业务 ID |
| operation_type | varchar | 操作类型 |
| operator | varchar | 操作人 |
| content | varchar | 操作内容 |
| created_at | datetime | 创建时间 |

---

### 8.8 notification_record 通知记录表

| 字段 | 类型 | 说明 |
|---|---|---|
| id | bigint | 主键 |
| ticket_id | bigint | 工单 ID |
| receiver | varchar | 接收人 |
| title | varchar | 通知标题 |
| content | varchar | 通知内容 |
| status | varchar | 通知状态 |
| created_at | datetime | 创建时间 |

---

### 8.9 ai_task AI 任务表

| 字段 | 类型 | 说明 |
|---|---|---|
| id | bigint | 主键 |
| biz_type | varchar | 业务类型 |
| biz_id | bigint | 业务 ID |
| task_type | varchar | 任务类型 |
| status | varchar | 任务状态 |
| request_payload | text | 请求上下文 |
| result | text | AI 处理结果 |
| created_at | datetime | 创建时间 |
| updated_at | datetime | 更新时间 |

---

## 9. 推荐工程结构

```text
com.example.opsagent
├── common
│   ├── api
│   ├── exception
│   ├── config
│   └── util
├── auth
│   ├── controller
│   ├── service
│   ├── entity
│   ├── mapper
│   └── dto
├── ticket
│   ├── controller
│   ├── service
│   ├── entity
│   ├── mapper
│   ├── dto
│   ├── vo
│   └── event
├── document
│   ├── controller
│   ├── service
│   ├── parser
│   ├── entity
│   ├── mapper
│   ├── dto
│   └── vo
├── ai
│   ├── controller
│   ├── service
│   ├── client
│   ├── entity
│   ├── mapper
│   └── dto
├── audit
│   ├── listener
│   ├── service
│   ├── entity
│   └── mapper
├── notification
│   ├── listener
│   ├── service
│   ├── entity
│   └── mapper
└── task
    ├── listener
    ├── service
    ├── entity
    └── mapper
```

---

## 10. 技术要求

第一阶段建议使用：

| 技术 | 用途 |
|---|---|
| Java 17 | 开发语言 |
| Spring Boot | 后端框架 |
| MyBatis-Plus / MyBatis | 数据访问 |
| MySQL | 业务数据存储 |
| Redis | 登录 token 存储、简单缓存 |
| Docker Compose | 启动 MySQL / Redis |
| OpenAPI / Swagger | 接口文档 |
| DeepSeek / OpenAI-compatible API | 大模型问答 |
| Maven | 项目构建 |

---

## 11. Redis 使用范围

第一阶段 Redis 只做简单使用，不做复杂高并发治理。

使用场景：

1. 登录 token 存储；
2. 文档问答结果简单缓存，可选；
3. 工单详情缓存，可选。

不做：

- 分布式锁；
- 热点缓存治理；
- 缓存穿透/击穿/雪崩完整方案；
- 限流计数。

这些放到第三阶段。

---

## 12. Docker Compose 要求

第一阶段必须提供 Docker Compose 文件，至少包含：

```text
MySQL
Redis
```

要求：

1. 能一键启动 MySQL / Redis；
2. README 里说明启动命令；
3. 提供数据库初始化 SQL；
4. 本地启动项目后可以连上 MySQL / Redis。

---

## 13. README 要求

README 至少包含：

1. 项目介绍；
2. 第一阶段功能范围；
3. 技术栈；
4. 本地启动步骤；
5. Docker Compose 启动方式；
6. 数据库初始化方式；
7. 大模型 API Key 配置方式；
8. 核心接口列表；
9. 观察者模式说明；
10. 后续阶段规划。

---

## 14. 统一返回结构

建议统一返回：

```json
{
  "code": 0,
  "message": "success",
  "data": {}
}
```

异常返回：

```json
{
  "code": 400,
  "message": "工单不存在",
  "data": null
}
```

---

## 15. 异常处理要求

必须包含：

- 参数校验异常；
- 业务异常；
- 工单不存在；
- 文档不存在；
- 状态流转非法；
- 文档解析失败；
- 大模型调用失败；
- 系统未知异常。

建议定义：

```text
BusinessException
ErrorCode
GlobalExceptionHandler
```

---

## 16. 参数校验要求

创建工单：

- title 不能为空；
- description 不能为空；
- priority 不能为空；
- creator 不能为空。

修改状态：

- targetStatus 不能为空；
- operator 不能为空。

上传文档：

- 文件不能为空；
- 文件类型必须受支持；
- 文件大小需要限制。

AI 问答：

- question 不能为空。

---

## 17. 第一阶段验收标准

### 17.1 功能验收

必须满足：

1. 用户可以登录；
2. 登录后可以访问业务接口；
3. 可以创建工单；
4. 可以分页查询工单；
5. 可以查询工单详情；
6. 可以修改工单状态；
7. 工单状态修改后可以查询状态变更日志；
8. 工单状态修改后自动生成审计日志；
9. 工单状态修改后自动生成通知记录；
10. 工单进入 RESOLVED 状态后自动创建 AI 总结任务；
11. 可以上传文档；
12. 可以解析文档；
13. 可以将文档切分为 chunk；
14. 文档和 chunk 可以存入 MySQL；
15. 可以基于文档内容调用大模型问答；
16. 可以查询 AI 问答日志；
17. Docker Compose 可以启动 MySQL 和 Redis；
18. README 可以指导本地启动。

---

### 17.2 设计模式验收

必须满足：

1. 工单状态变更使用观察者模式；
2. 存在明确的事件对象 TicketStatusChangedEvent；
3. 存在明确的事件发布者；
4. 至少存在三个事件监听器；
5. TicketService 不直接调用具体监听器；
6. 新增监听器时不需要修改 TicketService；
7. 文档解析建议使用策略模式；
8. Controller 不直接写文档解析细节；
9. 大模型调用逻辑不能散落在 Controller 中。

---

### 17.3 工程质量验收

必须满足：

1. 分包清晰；
2. Controller 只处理请求入口；
3. Service 负责业务逻辑；
4. Mapper 负责数据库访问；
5. DTO、VO、Entity 分层清楚；
6. 有统一返回结构；
7. 有全局异常处理；
8. 有参数校验；
9. 有 SQL 初始化脚本；
10. 有 README；
11. 项目可以正常启动；
12. 核心接口可以通过 Swagger / Apifox / Postman 调通。

---

## 18. 面试可讲点

第一阶段完成后，需要能回答以下问题：

1. 这个项目解决什么业务问题？
2. 为什么第一阶段只做核心可运行版本？
3. 工单状态是怎么流转的？
4. 为什么工单状态变化要用观察者模式？
5. 如果不用观察者模式，会有什么问题？
6. Spring ApplicationEvent 和观察者模式是什么关系？
7. TicketService 为什么不能直接调用通知服务和审计服务？
8. 如果新增短信通知，需要改哪些代码？
9. Listener 执行失败是否会影响主流程？
10. 文档解析为什么适合用策略模式？
11. 文本切分为什么要单独存 chunk？
12. 第一阶段的问答和正式 RAG 有什么区别？
13. 为什么第一阶段不用 Elasticsearch 和向量库？
14. Redis 在第一阶段起什么作用？
15. 后续如何扩展到 RAG、MQ、限流、监控？

---

## 19. 第一阶段完成后的后续规划

第一阶段完成后，后续阶段如下：

### 第二阶段：RAG + 检索增强

加入：

- Elasticsearch；
- 向量库；
- Embedding；
- RAG 问答；
- 答案引用来源；
- 文档 chunk 管理增强。

### 第三阶段：中间件与高频场景

加入：

- Redis 热点缓存；
- MQ 异步任务；
- 线程池隔离；
- 分布式锁；
- 幂等；
- 失败重试；
- XXL-JOB / Quartz。

### 第四阶段：工程化与稳定性

加入：

- Nginx；
- Sentinel / Resilience4j；
- Prometheus；
- Grafana；
- 模型调用网关；
- token 成本统计；
- 降级策略；
- 压测和造数脚本。

---

## 20. 第一阶段最终交付物

第一阶段最终需要交付：

1. 可运行 Spring Boot 项目；
2. MySQL 初始化 SQL；
3. Docker Compose 文件；
4. 工单管理接口；
5. 用户登录接口；
6. 文档上传和解析接口；
7. 简化大模型问答接口；
8. 工单状态变更事件机制；
9. 至少三个事件监听器；
10. README 文档；
11. 接口测试说明；
12. 项目结构说明；
13. 后续阶段规划说明。
