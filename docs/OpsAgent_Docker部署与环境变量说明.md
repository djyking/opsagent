# OpsAgent Docker 部署与环境变量说明

## 结论

Spring 配置 `${OPS_RAG_NEIGHBOR_WINDOW:1}` 在 Docker 中仍然有效，但它读取容器环境，而不是无条件读取宿主机环境。`compose.yaml` 使用下面的映射把宿主机 Shell 或 `.env` 中的值传入容器：

```yaml
environment:
  OPS_RAG_NEIGHBOR_WINDOW: ${OPS_RAG_NEIGHBOR_WINDOW:-1}
```

左侧是容器内变量名，右侧是 Compose 插值；新主机未配置时使用 `1`。因此迁移时复制项目和 Compose，并在新主机重新创建 `.env`，参数就会继续生效。

## 启停

```powershell
cd D:\myselfProject\opsagent
Copy-Item .env.example .env
# 编辑 .env，不能把它提交到 Git
.\scripts\start-containers.ps1 -Build -WithReranker
docker compose --profile apps --profile rerank ps
.\scripts\stop-containers.ps1
```

只启动中间件：

```powershell
docker compose up -d
```

## 容器分层

| 层 | 容器 | 说明 |
|---|---|---|
| 入口 | `ops-web-app`、`ops-gateway-app` | 5173 页面、18080 API；Nginx 对 SSE 关闭缓冲 |
| 业务 | auth、ticket、knowledge、rag、platform | 六个 Java 17 Spring Boot 镜像 |
| 检索 | Elasticsearch、Qdrant、reranker | BM25、向量检索、可选重排 |
| 基础设施 | MySQL、Redis、RabbitMQ、Nacos、Sentinel | 事实数据、缓存/锁、消息和治理 |
| 可观测 | Prometheus、Alertmanager、Grafana | 指标、告警和仪表盘 |

## 密钥和迁移

- `.env` 已被 `.gitignore` 排除；`.env.example` 只能保留占位值。
- API Key、生产数据库密码和 JWT Secret 不写 Dockerfile，不通过 `docker build --build-arg` 传入，也不保存在镜像层。
- 开发机可用 `.env` 或启动 Shell 环境变量；生产建议改用 Docker Secret、Vault 或 Kubernetes Secret。
- `docker save` 只能迁移镜像，不能迁移数据库；迁移 MySQL 前仍应执行 `mysqldump`。
- Qdrant 数据位于 named volume `qdrant-data`。离线镜像包位于 `D:\middleware\downloads\qdrant-v1.15.4.tar`，新主机可执行 `docker load -i` 导入。
- `OPS_PROMETHEUS_URL`、`OPS_GRAFANA_URL` 是容器内部探测地址；`OPS_PROMETHEUS_PUBLIC_URL`、`OPS_GRAFANA_PUBLIC_URL` 是浏览器点击后访问的公开地址。迁移到其他主机时，应在 `.env` 把两个 Public URL 改为新主机 IP 或域名，不能使用 Compose 服务名。

## 向量架构

MySQL 是知识文档、Chunk、审核和任务状态的事实源。Elasticsearch 只负责 SmartCN/BM25；Qdrant 负责 cosine 向量、权限 Payload 与 kNN。Knowledge Service 同时写入两侧、同时清理两侧，并将两路检索结果以 RRF 融合。任一向量环节不可用时降级到 BM25，但监控会记录降级原因。
