# OpsAgent RAG 现状审计

审计日期：2026-09-04

## 结论

当前实现已从“SQL 关键字检索 Demo”升级为可运行的企业级基础 RAG 流水线：结构化切片、OpenAI 批量 Embedding、Elasticsearch BM25、Qdrant kNN、RRF 融合、可选 BGE Cross-Encoder 重排、Context 预算、可信 Citation、SSE 流式回答、Outbox/MQ 索引一致性、Reindex 和离线评测均已落地。

系统仍有两个明确边界：当前 Java 17 不支持正式虚拟线程；数据库现有 26 篇已发布文档，而仅有最初 21 篇获得过发送至 OpenAI Embedding 的明确授权，因此本次没有对新增文档再次执行外部向量化，也没有伪造新的全量质量评测数字。已有 49 个本机向量已离线迁入 Qdrant，不涉及新的外发。

## 已实现能力

| 能力 | 实现状态 | 关键行为 |
|---|---|---|
| 结构化解析 | 已实现 | Markdown、DOCX、文本型 PDF、TXT；标题路径、列表、表格、代码、引用和 PDF 页码 |
| Token-aware 切片 | 已实现 | target 500、max 800、min 100、overlap 80；策略版本 `structure-v1` |
| Embedding | 已实现 | `text-embedding-3-small` 批量请求、维度检查、Token/耗时/成功失败指标 |
| ES 文本索引 | 已实现 | 版本化物理索引、读写 Alias、SmartCN/BM25、Bulk 部分失败处理 |
| Qdrant 向量索引 | 已实现 | 1536 维 cosine、权限 Payload、稳定 Chunk ID、版本化 Collection 与读 Alias |
| Hybrid Search | 已实现 | ES BM25 + Qdrant kNN、权限/发布状态前置过滤、标识符加权、application-side RRF |
| Rerank | 已实现 | BGE 远程 Provider、3 秒超时、不可用时按 RRF 安全降级 |
| Context | 已实现 | 去重、邻居扩展、每文档配额、6000 Token 预算、来源编号 `[S1]` |
| Citation | 已实现 | 只接受程序生成的来源编号，剔除无效引用并计数 |
| SSE | 已实现 | `status`、`token`、`sources`、`done`、`error`；前端逐增量渲染和 30 秒首段超时 |
| 一致性 | 已实现 | 本地 Outbox、Publisher Confirm、手工 ACK、幂等、重试/DLQ、删除和归档补偿 |
| Reindex | 已实现 | Redisson 锁、新物理索引、校验、Alias 原子切换、进度任务和单文档修复 |
| 可观测 | 已实现 | Query/Embedding/检索/RRF/Rerank/Context/Citation/Reindex 指标及 Grafana 面板 |
| 离线评测 | 已实现 | 50 条检索问题、10 条回答问题；Recall/MRR/nDCG/延迟/Citation 指标 |

## 代码审查结果

- 已修复 MySQL 8.4 不支持 `ADD COLUMN IF NOT EXISTS` 导致迁移无法执行的问题，脚本改为 `information_schema + PREPARE` 幂等 DDL。
- 已修复索引一致性 Gauge 每次查询动态注册的问题，改为单个 `AtomicLong` Gauge 更新。
- 已补齐 Embedding 失败计数和失败请求耗时。
- 已修复 SSE immediate 分支总耗时几乎恒为 0 的问题，计时起点前移至检索准备阶段。
- 前端引用不再展示原始 Chunk 正文；显示 Source ID、文档、章节、页码和检索通道，RRF/Rerank 分数只对管理员可见。
- 管理员新增索引一致性、全量 Reindex、失败任务和单文档修复页面。

## 明确不在本轮范围

- Milvus、Weaviate 等其他向量数据库；当前已选择并接入 Qdrant。
- GraphRAG 和 Agentic RAG。
- 扫描 PDF OCR。
- 未经数据授权自动切换到另一家生成 Provider。
- Java 17 下的虚拟线程。
