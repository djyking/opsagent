# AGENTS.md

## Project Goal

This is a Java backend interview practice project.

Project name: opsagent.

The project simulates an enterprise intelligent operations platform. The first phase only implements basic CRUD modules. Later phases may add Redis cache, MQ, Elasticsearch, vector database, RAG, Agent, scheduled jobs, rate limiting, monitoring, and model gateway.

## Current Environment

- Java 17
- Spring Boot 3.5.16
- Maven
- IntelliJ IDEA 2026.1
- Windows
- Docker is not required in the first phase.
- Docker Compose auto-start is currently disabled by `spring.docker.compose.enabled=false`.

## Tech Stack For First Phase

- Spring Boot Web
- Spring Validation
- Lombok
- MyBatis-Plus
- MySQL Driver
- Spring Data Redis dependency may exist, but Redis usage can be minimal in the first phase.
- springdoc-openapi for Swagger UI

## First Phase Scope

Only implement:

1. Common response wrapper
2. Global exception handling
3. Business exception
4. Error code enum
5. Page response object
6. MyBatis-Plus integration
7. Ticket CRUD
8. Knowledge base CRUD
9. Document metadata CRUD
10. MySQL initialization SQL
11. Swagger/OpenAPI
12. README startup instructions

## Do Not Implement Yet

Do not implement:

- Login or permission system
- Frontend pages
- AI model calls
- RAG
- Agent
- Elasticsearch
- MQ
- XXL-JOB
- Sentinel
- Prometheus
- Grafana
- Spring Cloud microservices
- Docker-dependent startup logic

## Coding Rules

- Use modular monolith architecture.
- Keep code simple and interview-friendly.
- Use RESTful APIs.
- Do not return Entity directly from Controller.
- Use Request DTO and Response DTO.
- Use validation annotations on request DTOs.
- Use logical delete.
- Use unified API response format.
- Use global exception handling.
- Do not hardcode database credentials in Java code.
- Put local database configuration in application-local.yml if needed.
- Do not remove existing project files unless clearly necessary.
- Do not introduce unnecessary dependencies.

## Check Commands

After changes, run:

```bash
mvn clean package -DskipTests