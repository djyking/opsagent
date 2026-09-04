# OpsAgent AI / RAG 实施与验收报告

更新时间：2026-09-03

## 1. 结论

本轮已完成 OpenAI、DeepSeek、Kimi 三 Provider 抽象与真实调用，完成 OpenAI Embedding、Elasticsearch SmartCN/BM25、Qdrant 向量检索、检索前数据权限、版本化 Prompt、Citation 校验、用量审计、真实 SSE、Sentinel 限流、文档删除补偿、管理员诊断和前端接入。

三家生成模型均曾通过真实 `OK` 请求；完整 RAG 使用无敏感合成 Runbook 验收成功。用户已明确授权把最初 21 篇内部文档发送给 OpenAI `text-embedding-3-small`。截至 2026-09-04，本机数据库有 32 篇未删除文档，其中 26 篇已发布、49 个有效切片；已有 49 个向量已在不再次调用外部 Embedding 的前提下从旧 ES 向量索引迁入 Qdrant。当前 Compose 容器未注入三家 API Key，因此运行态会降级为 retrieval-only；把 Key 通过不提交 Git 的 `.env` 注入并重建应用容器后才会恢复生成式回答。

## 2. 架构

```text
Browser
  → Gateway / JWT
  → RAG Service
      → 凭据索取拦截 / Sentinel FlowRule
      → Knowledge Service（Bearer Token 透传）
          → Query Embedding（text-embedding-3-small）
          → Elasticsearch SmartCN/BM25
          → Qdrant kNN + PUBLIC / owner / ADMIN + documentId 过滤
          → 应用层 RRF 融合与最低相似度 0.72
          → 向量侧不可用时降级到 BM25，检索侧不可用时再降级到权限 MySQL 关键词检索
      → PromptBuilder（版本化 YAML + 上下文预算）
      → LlmClientRouter
          ├─ OpenAI Responses API
          ├─ DeepSeek Chat Completions
          └─ Kimi Chat Completions（thinking disabled）
      → Provider 原生 Token SSE
      → CitationValidator
      → token + Sources + 校验后的 done.answer
      → ops_rag.ai_usage_log + Micrometer
```

自动跨 Provider Fallback 默认不实现。原因是供应商切换可能把同一内部上下文发送到另一第三方，必须由数据策略和业务策略明确授权；当前通过 `OPS_AI_PROVIDER` 显式切换。

## 3. 功能实施表

| 功能 | 状态 | 实现说明 |
|---|---|---|
| OpenAI Provider | 已实现并实测 | `gpt-5.6-luna`，Responses API，低推理强度 |
| DeepSeek Provider | 已实现并实测 | `deepseek-v4-flash`，兼容 Chat Completions |
| Kimi Provider | 已实现并实测 | `kimi-k2.6`，显式关闭 thinking，避免输出预算被推理耗尽 |
| Provider 配置切换 | 已实现 | `OPS_AI_PROVIDER=openai/deepseek/kimi`，业务代码不变 |
| Embedding | 已实现并实测 | OpenAI `text-embedding-3-small`，固定 1536 维，文档与查询同模型 |
| Elasticsearch 文本索引 | 已实现并实测 | SmartCN/BM25，版本化索引与读写 Alias，确定性 `_id`，重复索引幂等 |
| Qdrant 向量索引 | 已实现并实测 | 1536 维 cosine、权限 Payload、稳定 Chunk ID、版本化 Collection 与读 Alias |
| RBAC / 数据权限 | 已实现并测试 | `PUBLIC`、创建者、ADMIN；过滤发生在 Prompt 之前 |
| 单文档范围 | 已实现 | `/api/rag/ask` 可选 `documentId`，Knowledge 与 ES 双层限定 |
| Prompt 管理 | 已实现 | `prompts/rag-answer.yml`，系统规则不硬编码到业务类 |
| Prompt Injection 基础防护 | 已实现并测试 | 文档内容标记为不可信数据；合成恶意语句未执行、未泄露 Key |
| Citation 校验 | 已实现并测试 | 只返回实际 RetrievedChunk；未知 `[chunk:id]` 被移除 |
| 无证据防编造 | 已实现并实测 | 凭据索取直接拒答；低相似结果不进入 Prompt |
| 超时与重试 | 已实现 | 401/403 不重试；429/5xx/网络错误最多 3 次；SSE 仅在首 Token 前重试 |
| Context / 成本控制 | 已实现 | 问题 2000 字、TopK 20、上下文 16000 字、输出 Token 限制 |
| Sentinel 限流 | 已实现并实测 | Nacos 持久化 `ops-rag-ask` FlowRule，每实例 5 QPS；12 并发时 6 次被拦截 |
| AI Usage 审计 | 已实现并实测 | 记录模型、Token、耗时、结果、问题哈希；不保存正文和 Key |
| Prometheus 指标 | 已实现 | `opsagent_ai_requests_total`、`opsagent_ai_request_duration_seconds` |
| 管理员诊断 | 已实现并实测 | 配置状态和真实 probe；响应脱敏，不包含 Key/Authorization |
| SSE 流式问答 | 已实现并实测 | `/api/rag/stream` 返回 status/token/sources/done/error，真实 Token 非事后切串 |
| 前端问答 | 已实现并构建 | Fetch 读取 POST SSE，逐 Token 显示并以 `done.answer` 覆盖最终校验文本 |
| 文档删除补偿 | 已实现并故障测试 | 创建人/ADMIN 软删除；ES 文本和 Qdrant 向量任一侧失败均持久化重试，最多 10 次 |
| MySQL 关键词降级 | 已保留 | ES 无高质量命中或不可用时仍执行相同权限条件 |

## 4. 数据库与中间件变更

- 新增数据库 `ops_rag`。
- 新增 `ops_rag.ai_usage_log`。
- `ops_knowledge.knowledge_document` 新增 `visibility` 和权限索引。
- `ops_knowledge.knowledge_chunk` 新增 `embedding_model`、`indexed_at`。
- `ops_knowledge.knowledge_index_task` 保存 ES/Qdrant DELETE 补偿状态、重试次数和下次执行时间。
- Elasticsearch 当前正式索引只保存 SmartCN/BM25 文本字段；Qdrant Collection 保存 1536 维 cosine 向量和权限 Payload。
- 新增迁移脚本 `sql/08_ops_ai_rag.sql` 和 `sql/09_knowledge_index_compensation.sql`；脚本重复执行不会删除已有数据。

当前未删除文档为 32 篇，其中 26 篇已发布；49 个有效切片的模型均标记为 `text-embedding-3-small`。Qdrant `ops_knowledge_vector_read` Alias 已指向 `ops_knowledge_vector_v1`，精确计数为 49；旧 `opsagent-knowledge-v1` 仅作为本次离线迁移来源保留，运行期不再承担向量检索。

## 5. 真实测试结果

| 测试 | 结果 | 关键证据 |
|---|---|---|
| OpenAI 模型列表 | 通过 | HTTP 200，账号可见 `gpt-5.6-luna` |
| DeepSeek 模型列表 | 通过 | HTTP 200，账号可见 `deepseek-v4-flash` |
| Kimi 模型列表 | 通过 | HTTP 200，账号可见 `kimi-k2.6`；不可见 K3 |
| OpenAI Java probe | 通过 | Responses API 返回 `OK` |
| DeepSeek Java probe | 通过 | 返回 `OK` |
| Kimi Java probe | 通过 | 关闭 thinking 后返回 `OK` |
| 文档 Embedding | 通过 | `text-embedding-3-small`，数据库状态 `INDEXED` |
| Qdrant 向量迁移 | 通过 | 49 个已有向量迁入 `ops_knowledge_vector_v1`，Alias 查询精确计数 49 |
| Qdrant kNN 与权限 Payload | 通过 | 自相似查询命中同一 Chunk，score=1.0，`reviewStatus=PUBLISHED` 过滤生效 |
| 明确答案 RAG | 通过 | 命中 `chunk:1025`，回答“先确认 Sentinel 主从切换” |
| Prompt Injection | 通过 | 仅把恶意语句作为文档数据解释，`keyLeak=false` |
| 密码索取 | 通过（最终策略） | 直接证据不足，Provider `none`、Sources `0`、不产生 AI Usage |
| 历史关键词降级 | 通过 | 指定文档 `1013` 命中 `202-05-rabbitmq-backlog.md` |
| Maven `verify` | 通过 | Checkstyle 0 违规，现有与新增测试全部通过 |
| 前端 `pnpm build` | 通过 | Vue TypeScript 检查和 Vite 生产构建成功 |
| DeepSeek SSE | 通过 | `ST`、`REAM`、`_OK` 三个真实 delta；首字节 0.72s，总耗时 1.63s |
| SSE 正常结束 | 通过 | HTTP 200、完整 `done`，无 chunked transfer 错误 |
| Sentinel FlowRule | 通过 | 12 并发中 6 个放行、6 个拦截；规则已从 Nacos 推送 |
| 双索引删除补偿 | 通过（自动化测试） | ES/Qdrant 任一侧失败进入 RETRYING；恢复后继续清理，数据库保持软删除事实源 |

OpenAI 首次 Java probe 曾连接超时。根因是 Windows 启用了 `127.0.0.1:7890` HTTPS 代理，而 Java 默认直连到了不可用的 DNS 地址。启动脚本现会自动读取 Windows 当前代理并给 Java HTTPS 请求使用，localhost 配置为不走代理；修复后 OpenAI probe 通过。

## 6. 代码评审发现及处理

| 发现 | 风险 | 处理 |
|---|---|---|
| 原 RAG 只有占位回答 | 无真实 AI 能力 | 重构为 Provider + Retriever + Prompt + Citation 完整链路 |
| 原检索没有文档级权限 | 私有文档可能进入 Prompt | 增加 visibility、owner、ADMIN 过滤及权限测试 |
| KNN 无最低分 | 唯一向量可能被任何问题召回 | 增加默认 0.72 阈值 |
| 向量开启后历史文档不可用 | 功能回退 | 高质量向量无命中时回退权限关键词检索 |
| Kimi 小输出预算正文为空 | thinking 消耗预算 | Kimi Adapter 显式 `thinking.type=disabled` |
| 模型可能伪造引用 | 来源不可信 | Sources 由程序构造，并清理未知 Citation |
| 密码问题可能命中安全规范文档 | 不必要外部调用 | 检索前凭据索取硬性拦截 |
| Key 容易进入配置 | Secret 泄露 | 仓库外文件解析、环境变量注入、全程不打印 |
| 重试可能扩大成本 | 重复计费 | 最大 3 次，仅 429/5xx/网络错误重试 |
| DeepSeek 思考阶段 `content:null` | 前端出现字符串 `null` | 仅处理 JSON 文本节点，复测只输出真实 Token |
| SSE 完成时 ASYNC 二次分派丢失鉴权 | 响应已提交后 403、chunk 未正常结束 | 仅放行容器内部 `DispatcherType.ASYNC`，初始请求仍强制 JWT |
| 补偿任务可能被多实例重复领取 | 重复 ES 删除与状态竞争 | 使用带状态和到期条件的原子 claim 更新 |

## 7. 当前未实际使用或未完成

| 项目 | 当前状态 | 原因与后续工作 |
|---|---|---|
| Kimi K3 | 未使用 | 当前 Key 的 `/models` 只返回 `kimi-k2.6`、`kimi-k2.7-code`；获得权限后改 `MOONSHOT_MODEL` 即可 |
| 自动跨供应商 Fallback | 未启用 | 涉及上下文跨第三方传输和成本策略，建议经审批后增加 allowlist |
| Sentinel 全局共享额度 | 未实现 | 当前规则由 Nacos 集中下发但每实例计数；严格多实例额度需 Cluster Token Server |
| Redis 问答缓存 | 未实现 | 需要先定义用户权限、文档版本和缓存失效键，避免跨用户缓存泄漏 |
| 会话历史 | 未实现 | 当前为单轮问答，尚未定义会话权限、保留期和隐私清理策略 |
| 上传原文件物理清理 | 未实现 | 删除接口有意保留原文件便于恢复；后续按审计保留期增加定时清理 |
| OCR | 未实现 | 扫描 PDF 仍需 Tesseract 或受控云 OCR |
| 生产 Secret Manager | 未接入 | 本地使用仓库外文件；生产应接 Vault/KMS/Kubernetes Secret |

## 8. 操作入口

- 用户操作：`http://127.0.0.1:5173/`
- RAG API：`POST http://127.0.0.1:18080/api/rag/ask`
- RAG SSE：`POST http://127.0.0.1:18080/api/rag/stream`
- Provider 状态：`GET http://127.0.0.1:18080/api/rag/admin/providers`
- Elasticsearch：`http://127.0.0.1:9200/opsagent-knowledge-v1/_count`
- Prometheus：`http://127.0.0.1:9090/`
- Grafana：`http://127.0.0.1:3000/`

账号和本地密码只在仓库外 `D:\middleware\docs\OpsAgent本地地址与密码.md` 中维护。

## 9. 2026-09-04 RAG 技术增强补充

本节覆盖前文基于旧 `opsagent-knowledge-v1` 单体向量索引的历史结论。当前已升级为结构化切片、Elasticsearch SmartCN/BM25、Qdrant kNN、应用层 RRF、可选 BGE Cross-Encoder、Context Token Budget、`[Sx]` Citation、独立索引 Outbox/DLQ，以及 ES 版本化索引和 Qdrant 版本化 Collection/Alias。管理员页面新增一致性检查、全量 Reindex、失败任务和单文档修复。

本机 BGE `BAAI/bge-reranker-v2-m3` 已完成真实 CPU 推理；Java → BGE 隔离测试中 5/5 来源具有 Rerank 分数。实时库现有 26 篇已发布文档，而明确的 OpenAI Embedding 外发授权范围为最初 21 篇，因此本次没有把新增文档再次发送给 OpenAI；改为复用本机旧 ES 中已有的 49 个向量完成 Qdrant 数据迁移。完整现状、架构和不编造数字的评测报告见 `docs/rag/`。
