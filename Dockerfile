FROM maven:3.9.11-eclipse-temurin-17 AS builder

WORKDIR /workspace
COPY . .
ARG APP_MODULE
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -DskipTests -pl "${APP_MODULE}" -am package
RUN cp "$(find "${APP_MODULE}/target" -maxdepth 1 -type f -name '*.jar' \
        ! -name '*.original' | head -n 1)" /workspace/app.jar

FROM eclipse-temurin:17-jre-jammy

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --create-home --uid 10001 opsagent
WORKDIR /app
COPY --from=builder /workspace/app.jar /app/app.jar
RUN mkdir -p /app/data/uploads && chown -R opsagent:opsagent /app
USER opsagent
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
