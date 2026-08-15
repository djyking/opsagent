# opsagent

面向企业智能运维平台的 Java 后端面试练习项目。

项目采用模块化单体架构，业务代码统一放在 `com.example.opsagent` 包下。

## 运行环境

- Java 17
- Spring Boot 3.5.16
- Maven
- MySQL
- IntelliJ IDEA 2026.1
- Windows

项目已通过 `spring.docker.compose.enabled=false` 关闭 Docker Compose 自动启动。

## 包结构

```text
com.example.opsagent
├── common
│   ├── api
│   ├── exception
│   ├── config
│   └── util
├── auth
├── security
├── ticket
├── document
├── ai
├── audit
├── notification
└── task
```

每个业务模块分别维护自己的 `controller`、`service`、`entity`、`mapper`、`dto` 及其他模块专用包。

## 配置说明

- 通用配置：`src/main/resources/application.yml`
- 本地数据库配置：`src/main/resources/application-local.yml`
- MySQL 初始化脚本：`src/main/resources/db/schema.sql`

本地数据库配置可以通过以下环境变量覆盖：

- `OPSAGENT_DB_URL`
- `OPSAGENT_DB_USERNAME`
- `OPSAGENT_DB_PASSWORD`
- `OPS_AGENT_JWT_SECRET`（至少 32 个 UTF-8 字节，生产环境必须使用随机密钥）
- `OPS_AGENT_DOCUMENT_STORAGE`（可选，文档存储目录，默认 `./data/uploads`）

JWT 默认有效期为 120 分钟，可通过 `ops-agent.security.jwt.expire-minutes` 调整。

## 认证

注册用户：

```http
POST /api/auth/register
Content-Type: application/json

{"username":"alice","password":"123456","displayName":"Alice"}
```

登录由 Spring Security Filter 直接处理，不经过 Controller：

```http
POST /api/auth/login
Content-Type: application/json

{"username":"alice","password":"123456"}
```

携带登录响应中的 JWT 查询当前用户：

```http
GET /api/auth/me
Authorization: Bearer <token>
```

除注册、登录、健康检查和 Swagger 外，其余接口默认需要携带 JWT。

## 当前业务能力

- 工单创建、分页、详情、修改、逻辑删除和受控状态流转；
- 工单状态日志以及状态变化后的审计、通知、AI 总结任务；
- TXT、Markdown 文档上传、本地存储、解析和文本切片；
- 基于文档切片的简化问答和问答日志；
- 通知、审计日志和 AI 任务查询。

当前 `MockAiModelClient` 不会访问外部模型，只用于验证文档检索和问答编排。接入真实模型时应新增 `AiModelClient` 实现并替换模拟实现。

## 构建

```bash
mvn clean package
```

如果 Windows 没有全局安装 Maven，可以使用项目自带的 Maven Wrapper：

```bash
.\mvnw.cmd clean package
```

## 运行

```bash
mvn spring-boot:run
```

也可以直接在 IntelliJ IDEA 中运行 `OpsagentApplication`。

应用启动后，可以通过以下地址访问 Swagger UI：

```text
http://localhost:8080/swagger-ui.html
```
