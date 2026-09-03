# OpsAgent AI / RAG 实施与验收报告

更新时间：2026-09-03

## 1. 结论

本轮已完成 OpenAI、DeepSeek、Kimi 三 Provider 抽象与真实调用，完成 OpenAI Embedding、Elasticsearch `dense_vector`、检索前数据权限、版本化 Prompt、Citation 校验、用量审计、限流、管理员诊断和前端接入。

三家生成模型均已通过真实 `OK` 请求；完整 RAG 使用无敏感合成 Runbook 验收成功。现有内部 Runbook 没有批量发送给 OpenAI 做 Embedding，因为项目目录写权限不等于内部数据可发送第三方的授权。历史文档继续通过权限过滤后的 MySQL 关键词检索提供本地降级能力。

## 2. 架构

```text
Browser
  → Gateway / JWT
  → RAG Service
      → 凭据索取拦截 / 每用户限流
      → Knowledge Service（Bearer Token 透传）
          → Query Embedding（text-embedding-3-small）
          → Elasticsearch KNN
          → PUBLIC / owner / ADMIN + documentId 过滤
          → 最低相似度 0.72
          → 无命中时使用权限 MySQL 关键词降级
      → PromptBuilder（版本化 YAML + 上下文预算）
      → LlmClientRouter
          ├─ OpenAI Responses API
          ├─ DeepSeek Chat Completions
          └─ Kimi Chat Completions（thinking disabled）
      → CitationValidator
      → Answer + 程序生成的 Sources
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
| Elasticsearch 向量索引 | 已实现并实测 | 索引 `opsagent-knowledge-v1`，确定性 `_id`，重复索引幂等 |
| RBAC / 数据权限 | 已实现并测试 | `PUBLIC`、创建者、ADMIN；过滤发生在 Prompt 之前 |
| 单文档范围 | 已实现 | `/api/rag/ask` 可选 `documentId`，Knowledge 与 ES 双层限定 |
| Prompt 管理 | 已实现 | `prompts/rag-answer.yml`，系统规则不硬编码到业务类 |
| Prompt Injection 基础防护 | 已实现并测试 | 文档内容标记为不可信数据；合成恶意语句未执行、未泄露 Key |
| Citation 校验 | 已实现并测试 | 只返回实际 RetrievedChunk；未知 `[chunk:id]` 被移除 |
| 无证据防编造 | 已实现并实测 | 凭据索取直接拒答；低相似结果不进入 Prompt |
| 超时与重试 | 已实现 | 401/403 不重试；429/5xx/网络错误最多 3 次 |
| Context / 成本控制 | 已实现 | 问题 2000 字、TopK 20、上下文 16000 字、输出 Token 限制 |
| 用户限流 | 已实现 | 单实例每用户每分钟 20 次 |
| AI Usage 审计 | 已实现并实测 | 记录模型、Token、耗时、结果、问题哈希；不保存正文和 Key |
| Prometheus 指标 | 已实现 | `opsagent_ai_requests_total`、`opsagent_ai_request_duration_seconds` |
| 管理员诊断 | 已实现并实测 | 配置状态和真实 probe；响应脱敏，不包含 Key/Authorization |
| 前端问答 | 已实现并构建 | 调用 `/api/rag/ask`，显示模型、耗时、答案和来源 |
| MySQL 关键词降级 | 已保留 | 未向量化历史文档仍可本地检索，并执行相同权限条件 |

## 4. 数据库与中间件变更

- 新增数据库 `ops_rag`。
- 新增 `ops_rag.ai_usage_log`。
- `ops_knowledge.knowledge_document` 新增 `visibility` 和权限索引。
- `ops_knowledge.knowledge_chunk` 新增 `embedding_model`、`indexed_at`。
- Elasticsearch 新增 `opsagent-knowledge-v1`，Embedding 字段为 1536 维 cosine `dense_vector`。
- 新增迁移脚本 `sql/08_ops_ai_rag.sql`，脚本使用 `information_schema` 判断，重复执行不会删除或覆盖已有数据。

迁移前后原有 22 篇文档和 24 个切片均保留。验收新增文档 ID `1023`、切片 ID `1025`，该切片状态为 `INDEXED`；Elasticsearch 验收时包含 1 条向量文档。

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
| Elasticsearch 索引 | 通过 | `opsagent-knowledge-v1/_count = 1` |
| 明确答案 RAG | 通过 | 命中 `chunk:1025`，回答“先确认 Sentinel 主从切换” |
| Prompt Injection | 通过 | 仅把恶意语句作为文档数据解释，`keyLeak=false` |
| 密码索取 | 通过（最终策略） | 直接证据不足，Provider `none`、Sources `0`、不产生 AI Usage |
| 历史关键词降级 | 通过 | 指定文档 `1013` 命中 `202-05-rabbitmq-backlog.md` |
| Maven `verify` | 通过 | Checkstyle 0 违规，现有与新增测试全部通过 |
| 前端 `pnpm build` | 通过 | Vue TypeScript 检查和 Vite 生产构建成功 |

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

## 7. 当前未实际使用或未完成

| 项目 | 当前状态 | 原因与后续工作 |
|---|---|---|
| 21 篇既有内部文档的全量向量化 | 未执行 | 未获得把具体内部文档发送给外部 Embedding Provider 的数据授权。完成数据分级/脱敏/第三方处理审批后，由 ADMIN 调用 `/api/knowledge/internal/reindex` |
| Kimi K3 | 未使用 | 当前 Key 的 `/models` 只返回 `kimi-k2.6`、`kimi-k2.7-code`；获得权限后改 `MOONSHOT_MODEL` 即可 |
| 自动跨供应商 Fallback | 未启用 | 涉及上下文跨第三方传输和成本策略，建议经审批后增加 allowlist |
| Redis 分布式 RAG 限流 | 未实现 | 当前单实例内存限流满足本地演示；多实例前改为 Redis Lua 或 Sentinel 持久化规则 |
| Redis 问答缓存 | 未实现 | 需要先定义用户权限、文档版本和缓存失效键，避免跨用户缓存泄漏 |
| SSE 流式输出 | 未实现 | 第一版使用普通 JSON；后续需同步实现取消、超时、前端流式渲染和网关配置 |
| 会话历史 | 未实现 | 当前为单轮问答，尚未定义会话权限、保留期和隐私清理策略 |
| 文档删除的 ES 补偿 | 未启用 | 现有文档删除 API 尚未开放；开放时需增加 Outbox/MQ 删除向量 |
| OCR | 未实现 | 扫描 PDF 仍需 Tesseract 或受控云 OCR |
| 生产 Secret Manager | 未接入 | 本地使用仓库外文件；生产应接 Vault/KMS/Kubernetes Secret |

## 8. 操作入口

- 用户操作：`http://127.0.0.1:5173/`
- RAG API：`POST http://127.0.0.1:8080/api/rag/ask`
- Provider 状态：`GET http://127.0.0.1:8080/api/rag/admin/providers`
- Elasticsearch：`http://127.0.0.1:9200/opsagent-knowledge-v1/_count`
- Prometheus：`http://127.0.0.1:9090/`
- Grafana：`http://127.0.0.1:3000/`

账号和本地密码只在仓库外 `D:\middleware\docs\OpsAgent本地地址与密码.md` 中维护。
