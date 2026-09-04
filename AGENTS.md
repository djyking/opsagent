# AGENTS.md

## Project Goal

This is a Java 17 enterprise operations and RAG practice project.

Project name: opsagent.

The project simulates an enterprise intelligent operations platform. It currently includes ITSM workflows, Redis, RabbitMQ, Elasticsearch hybrid retrieval, RAG, Sentinel rate limiting, Nacos discovery/configuration and Prometheus/Grafana observability.

## Current Environment

- Java 17
- Spring Boot 3.5.16
- Maven
- IntelliJ IDEA 2026.1
- Windows
- Docker Compose runs middleware only; Java services run on Windows.
- Docker Compose auto-start is currently disabled by `spring.docker.compose.enabled=false`.

## Current Tech Stack

- Spring Boot Web
- Spring Validation
- Lombok
- MyBatis-Plus
- MySQL Driver
- Spring Data Redis dependency may exist, but Redis usage can be minimal in the first phase.
- springdoc-openapi for Swagger UI

## Current Scope

Maintain and extend:

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

## Out Of Scope Unless Explicitly Requested

Do not introduce Milvus/Qdrant, GraphRAG, Agentic RAG or Java service containers unless explicitly requested. Do not send new internal documents to an external AI provider without a matching data-processing authorization.

## Coding Rules

- Preserve the existing Spring Cloud microservice architecture.
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
- Keep API keys and local passwords outside Git.
- Do not clear databases or delete Docker named volumes during routine development and testing.
- Java class headers use `@author heyu` and a project date no later than `2026/9/3`.

## Check Commands

After changes, run:

```bash
mvn clean verify
