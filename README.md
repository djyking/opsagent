# opsagent

Java backend interview practice project for an enterprise intelligent operations platform.

The project has been reset to a modular monolith package skeleton. Business classes will be rebuilt under `com.example.opsagent`.

## Environment

- Java 17
- Spring Boot 3.5.16
- Maven
- MySQL
- IntelliJ IDEA 2026.1
- Windows

Docker Compose auto-start is disabled by `spring.docker.compose.enabled=false`.

## Package Layout

```text
com.example.opsagent
├── common
│   ├── api
│   ├── exception
│   ├── config
│   └── util
├── auth
├── ticket
├── document
├── ai
├── audit
├── notification
└── task
```

Each business module keeps its own `controller`, `service`, `entity`, `mapper`, `dto`, and other module-specific packages.

## Configuration

- Common configuration: `src/main/resources/application.yml`
- Local database configuration: `src/main/resources/application-local.yml`
- MySQL initialization entry: `src/main/resources/db/schema.sql`

Local database settings can be overridden with environment variables:

- `OPSAGENT_DB_URL`
- `OPSAGENT_DB_USERNAME`
- `OPSAGENT_DB_PASSWORD`

## Build

```bash
mvn clean package -DskipTests
```

If Maven is not installed globally on Windows:

```bash
.\mvnw.cmd clean package -DskipTests
```

## Run

```bash
mvn spring-boot:run
```

Or run `OpsagentApplication` directly from IntelliJ IDEA.

Swagger UI will be available after controllers are added:

```text
http://localhost:8080/swagger-ui.html
```
