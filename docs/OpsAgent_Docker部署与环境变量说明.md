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

### AI 生成的运行配置

2026-09-05 修复：Windows 启动脚本曾读取本机密钥，但 Docker 不会继承其他进程的环境变量。
AI 开关开启而密钥为空时，只能返回知识原文片段。当前本机已在 Git 和 Docker 构建上下文均忽略的
项目根目录 `.env` 中配置 DeepSeek，真实连通性探测通过。

新主机需要在 `.env` 设置 `OPS_AI_ENABLED=true`、`OPS_AI_PROVIDER=deepseek`、
`DEEPSEEK_API_KEY` 和 `DEEPSEEK_MODEL`。不要覆盖已有 `.env`；合并所需变量。
修改后执行 `docker compose --profile apps up -d --no-deps ops-rag-app`，单纯 `restart`
不会更新容器环境。Compose 已显式透传模型、地址、超时和输出预算。

管理员可调用 `POST /api/rag/admin/providers/deepseek/probe` 验证真实连接；
容器健康检查通过只表示应用存活，不表示外部 AI 可用。模型密钥没有写入镜像或提交到 Git。
生成模型与 OpenAI Embedding 配置独立，本次恢复生成不触发历史文档重建向量。

- `.env` 已被 `.gitignore` 排除；`.env.example` 只能保留占位值。
- API Key、生产数据库密码和 JWT Secret 不写 Dockerfile，不通过 `docker build --build-arg` 传入，也不保存在镜像层。
- 开发机可用 `.env` 或启动 Shell 环境变量；生产建议改用 Docker Secret、Vault 或 Kubernetes Secret。
- `docker save` 只能迁移镜像，不能迁移数据库；迁移 MySQL 前仍应执行 `mysqldump`。
- Qdrant 数据位于 named volume `qdrant-data`。离线镜像包位于 `D:\middleware\downloads\qdrant-v1.15.4.tar`，新主机可执行 `docker load -i` 导入。
- `OPS_PROMETHEUS_URL`、`OPS_GRAFANA_URL` 是容器内部探测地址；`OPS_PROMETHEUS_PUBLIC_URL`、`OPS_GRAFANA_PUBLIC_URL` 是浏览器点击后访问的公开地址。迁移到其他主机时，应在 `.env` 把两个 Public URL 改为新主机 IP 或域名，不能使用 Compose 服务名。

## 向量架构

### 本机 Rerank 配置（2026-09-05）

已启用本地 `BAAI/bge-reranker-v2-m3`。项目 `.env` 设置
`OPS_RERANK_ENABLED=true`、`COMPOSE_PROFILES=apps,rerank`，确保常规 Compose 启动包含重排服务。
本机 CPU 配置为：12 个检索候选、每个 query-passage 对最多 512 Token（超出截断）、
基础配置为 batch size 8、4 个 PyTorch 线程、Java 重排请求超时 20 秒。
低并发展示默认加载 `compose.demo.yaml`，覆盖为 batch size 2、2 个线程、推理并发 1、
重排超时 60 秒，并设置各服务的 JVM、连接池和容器资源预算。
对应变量为 `OPS_RAG_RETRIEVAL_CANDIDATES`、`OPS_RERANK_MAX_LENGTH`、
`OPS_RERANK_BATCH_SIZE`、`OPS_RERANK_CPU_THREADS`、`OPS_RERANK_TIMEOUT_SECONDS`。

调整原因：原 3 秒预算无法覆盖本机长文档 CPU 推理；实测 23 个候选约需 19.6 秒。
当前 12 候选配置下，工单问答与智能问答两次均返回 `rerankApplied=true` 和真实 `rerankScore`，
Micrometer 记录重排总耗时 20.77 秒 / 2 次，平均约 10.4 秒。此数字只对应两次本机测试，
不是并发性能保证；候选数和长度限制可能影响尾部证据召回，需要正式评测集再校准。
超时或不可用时仍保留原有排序降级机制。生成模型仍为 `deepseek-v4-flash`；
检索当前使用 BM25，Embedding 未启用与重排是否生效是两个独立状态。

重排运行参数可通过 `http://127.0.0.1:8010/health` 查看。密钥和本机配置仍由 Git 忽略。

### 演示资源模式（2026-09-05）

项目 `.env` / `.env.example` 使用 `COMPOSE_FILE=compose.yaml:compose.demo.yaml` 和
`COMPOSE_PATH_SEPARATOR=:`，普通 `docker compose` 命令默认保留演示预算，Windows / Linux 一致。
建议使用 `.\scripts\start-containers.ps1 -WithReranker` 启动；加 `-StandardResources`
可通过显式 `-f compose.yaml` 切回基础配置。旧安装首次添加 Nacos 数据卷前应使用启动脚本，
让它先停止、备份并迁移原容器内数据；不要直接重建仍未挂载持久化卷的旧 Nacos。

已有 RabbitMQ 安装需保留当前容器 hostname 到 `.env` 的 `OPS_RABBITMQ_HOSTNAME`，
以继续使用原 Mnesia 节点目录。本机已完成，新安装使用 `opsagent-rabbitmq` 固定主机名。

本轮预热后容器工作集约 4.75 GiB，原约 9.70 GiB。选型仍需计入模型冷缓存和系统开销，
不能按 Docker 工作集直接购买等量内存。完整参数、125 项后端测试与真实负载结果见
[演示环境资源优化与验收](OpsAgent_演示环境资源优化与验收.md)。

MySQL 是知识文档、Chunk、审核和任务状态的事实源。Elasticsearch 只负责 SmartCN/BM25；Qdrant 负责 cosine 向量、权限 Payload 与 kNN。Knowledge Service 同时写入两侧、同时清理两侧，并将两路检索结果以 RRF 融合。任一向量环节不可用时降级到 BM25，但监控会记录降级原因。
