# OpsAgent Phase 1 接口文档

本文档根据 `opsagent_phase1_requirements.md` 整理第一阶段接口清单。这里只描述接口用途、统一响应和错误约定，具体请求参数和响应字段以后根据表结构与 DTO 设计补充。

## 1. 通用约定

### 1.1 基础路径

```text
/api
```

### 1.2 数据格式

- 普通接口使用 `application/json`。
- 文件上传接口使用 `multipart/form-data`。
- 时间字段建议使用 `yyyy-MM-dd HH:mm:ss`。

### 1.3 登录凭证

登录成功后返回 token。后续需要登录态的接口通过请求头传递：

```text
Authorization: Bearer <token>
```

第一阶段可以使用简化 token，例如 UUID token 存 Redis。

## 2. 统一响应结构

成功响应：

```json
{
  "code": 0,
  "message": "success",
  "data": {}
}
```

失败响应：

```json
{
  "code": 400,
  "message": "错误信息",
  "data": null
}
```

分页响应的 `data` 建议统一为：

```json
{
  "records": [],
  "total": 0,
  "pageNum": 1,
  "pageSize": 10
}
```

## 3. 错误码约定

| code | 说明 |
|---|---|
| `0` | 成功 |
| `400` | 请求参数错误 |
| `401` | 未登录或 token 无效 |
| `403` | 无权限，第一阶段一般不使用 |
| `404` | 资源不存在 |
| `409` | 业务状态冲突，例如非法状态流转 |
| `500` | 系统未知异常 |

业务错误建议通过 `BusinessException` 抛出，由 `GlobalExceptionHandler` 统一转换响应。

## 4. Auth 用户模块

| 方法 | 路径 | 功能 |
|---|---|---|
| `POST` | `/api/auth/login` | 用户登录，校验用户名和密码，返回 token 与用户基础信息 |
| `GET` | `/api/auth/me` | 查询当前登录用户信息，并校验 token 是否有效 |
| `POST` | `/api/auth/logout` | 用户退出登录，删除或失效当前 token，可选实现 |

## 5. Ticket 工单模块

| 方法 | 路径 | 功能 |
|---|---|---|
| `POST` | `/api/tickets` | 创建工单，初始状态为 `OPEN` |
| `GET` | `/api/tickets/{id}` | 查询工单详情 |
| `GET` | `/api/tickets` | 分页查询工单列表，支持按状态、优先级、关键词等条件筛选 |
| `PUT` | `/api/tickets/{id}` | 修改工单基础信息，可选实现 |
| `PUT` | `/api/tickets/{id}/status` | 修改工单状态，成功后发布 `TicketStatusChangedEvent` |
| `GET` | `/api/tickets/{id}/status-logs` | 查询指定工单的状态变更日志 |
| `DELETE` | `/api/tickets/{id}` | 删除工单，建议逻辑删除，可选实现 |

工单状态流转：

```text
OPEN -> PROCESSING -> RESOLVED -> CLOSED
```

可选回退：

```text
PROCESSING -> OPEN
RESOLVED -> PROCESSING
```

## 6. Document 文档模块

| 方法 | 路径 | 功能 |
|---|---|---|
| `POST` | `/api/documents/upload` | 上传文档，保存文档元数据，并触发同步解析和文本切分 |
| `GET` | `/api/documents` | 分页查询文档列表，支持按文件名、状态、上传人等条件筛选 |
| `GET` | `/api/documents/{id}` | 查询文档详情 |
| `GET` | `/api/documents/{id}/chunks` | 查询指定文档的文本切片 |
| `POST` | `/api/documents/{id}/parse` | 手动重新解析文档，可选实现 |
| `DELETE` | `/api/documents/{id}` | 删除文档，建议同步逻辑删除文档切片，可选实现 |

第一阶段建议优先支持：

- `txt`
- `md`

文档状态：

```text
UPLOADED
PARSED
FAILED
```

## 7. AI 问答模块

| 方法 | 路径 | 功能 |
|---|---|---|
| `POST` | `/api/ai/chat` | 基于已上传文档内容进行简化问答，调用大模型并记录问答日志 |
| `GET` | `/api/ai/chat-logs` | 分页查询 AI 问答日志 |
| `GET` | `/api/ai/chat-logs/{id}` | 查询单条 AI 问答日志详情，可选实现 |

第一阶段只实现简化问答：

- 从 MySQL 查询文档 chunk。
- 可使用关键词匹配或指定文档前 N 个 chunk。
- 不接入 Elasticsearch、向量库和正式 RAG。

## 8. Audit 审计模块

| 方法 | 路径 | 功能 |
|---|---|---|
| `GET` | `/api/audit/operation-logs` | 分页查询操作审计日志 |
| `GET` | `/api/audit/operation-logs/{id}` | 查询单条操作审计日志详情，可选实现 |

审计日志主要由监听器自动生成：

- 监听 `TicketStatusChangedEvent`。
- 写入 `operation_log`。

## 9. Notification 通知模块

| 方法 | 路径 | 功能 |
|---|---|---|
| `GET` | `/api/notifications` | 分页查询通知记录 |
| `GET` | `/api/notifications/{id}` | 查询单条通知记录详情 |
| `PUT` | `/api/notifications/{id}/status` | 修改通知状态，可选实现 |

第一阶段通知只写数据库记录，不发送真实邮件、短信或 WebSocket。

## 10. Task AI 任务模块

| 方法 | 路径 | 功能 |
|---|---|---|
| `GET` | `/api/tasks/ai` | 分页查询 AI 任务列表 |
| `GET` | `/api/tasks/ai/{id}` | 查询 AI 任务详情 |
| `PUT` | `/api/tasks/ai/{id}/status` | 修改 AI 任务状态，可选实现 |

AI 任务主要由监听器自动生成：

- 监听 `TicketStatusChangedEvent`。
- 当工单状态变为 `RESOLVED` 时创建 AI 总结任务。
- 第一阶段只创建任务记录，不要求真正执行总结。

## 11. 事件相关接口说明

工单状态变更事件不是 HTTP 接口，而是后端内部事件：

```text
TicketStatusChangedEvent
```

发布时机：

```text
工单状态更新成功后
```

第一阶段至少包含三个监听器：

| 监听器 | 功能 |
|---|---|
| `AuditLogListener` | 写入操作审计日志 |
| `NotificationListener` | 写入通知记录 |
| `AiSummaryTaskListener` | 工单变为 `RESOLVED` 时创建 AI 总结任务 |

## 12. Swagger 约定

项目集成 springdoc-openapi 后，接口文档地址：

```text
http://localhost:8080/swagger-ui.html
```

OpenAPI JSON：

```text
http://localhost:8080/v3/api-docs
```
