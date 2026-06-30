# opsagent

opsagent is a Java backend interview practice project. It simulates an enterprise intelligent operations platform and currently focuses on a simple modular monolith CRUD foundation.

## Current Scope

- Unified API response wrapper
- Unified page response object
- Error code enum and business exception
- Global exception handling
- MyBatis-Plus integration with logical delete and pagination
- Ticket CRUD
- Knowledge base CRUD
- Document metadata CRUD
- MySQL initialization SQL
- Swagger UI through springdoc-openapi

The first phase intentionally does not include login, permission control, frontend pages, AI calls, RAG, Agent, Elasticsearch, MQ, XXL-JOB, Sentinel, Prometheus, Grafana, Spring Cloud, or Docker-dependent startup logic.

## Environment

- Java 17
- Spring Boot 3.5.16
- Maven
- MySQL
- IntelliJ IDEA
- Windows

Docker Compose auto-start is disabled by `spring.docker.compose.enabled=false`.

## Database Setup

Create and initialize the database with:

```bash
mysql -u root -p < src/main/resources/db/schema.sql
```

The default application configuration reads database settings from environment variables and provides local examples:

- `OPSAGENT_DB_URL`, default `jdbc:mysql://localhost:3306/opsagent?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai`
- `OPSAGENT_DB_USERNAME`, default `root`
- `OPSAGENT_DB_PASSWORD`, default `root`

For your own machine, either set environment variables or adjust `src/main/resources/application.yaml` locally.

## Start

Build the project:

```bash
mvn clean package -DskipTests
```

If Maven is not installed globally on Windows:

```bash
.\mvnw.cmd clean package -DskipTests
```

Run the application:

```bash
mvn spring-boot:run
```

Or run `OpsagentApplication` directly from IntelliJ IDEA.

## Swagger UI

After startup, open:

```text
http://localhost:8080/swagger-ui.html
```

OpenAPI JSON:

```text
http://localhost:8080/v3/api-docs
```

## Main APIs

Ticket:

- `POST /api/tickets`
- `PUT /api/tickets/{id}`
- `DELETE /api/tickets/{id}`
- `GET /api/tickets/{id}`
- `GET /api/tickets`

Knowledge base:

- `POST /api/knowledge-bases`
- `PUT /api/knowledge-bases/{id}`
- `DELETE /api/knowledge-bases/{id}`
- `GET /api/knowledge-bases/{id}`
- `GET /api/knowledge-bases`

Document metadata:

- `POST /api/documents`
- `PUT /api/documents/{id}`
- `DELETE /api/documents/{id}`
- `GET /api/documents/{id}`
- `GET /api/documents`
- `GET /api/documents/by-knowledge-base/{knowledgeBaseId}`

## Response Format

All controllers return:

```json
{
  "code": 0,
  "message": "success",
  "data": {}
}
```

Paged APIs return `data.records`, `data.total`, `data.pageNo`, and `data.pageSize`.

## Later TODO

- Redis cache for hot data
- MQ event flow
- Elasticsearch search
- Vector database and RAG
- Agent workflow
- Scheduled jobs
- Rate limiting
- Monitoring
- Model gateway
