# OpsAgent RAG 验收与评测报告

验收日期：2026-09-04

## 本轮真实结果

| 检查项 | 结果 |
|---|---|
| 前端 `vue-tsc -b && vite build` | 通过，2500 个模块完成转换 |
| Knowledge 单元/组件测试 | 19/19 通过（含 Qdrant Collection、Alias、权限过滤与查询体） |
| RAG 单元/组件测试 | 12/12 通过 |
| 全工程 `mvn clean verify` | 15 个 Reactor 模块全部 SUCCESS |
| Checkstyle | 0 违规 |
| Gateway 健康 | `UP` |
| 前端访问 | `http://127.0.0.1:5173/` 返回 HTTP 200 |
| SmartCN | `analysis-smartcn` 已加载 |
| BGE Reranker | 容器健康；`BAAI/bge-reranker-v2-m3` 真实推理返回分数 0.71239 / 0.00056 |
| Java → BGE 集成 | LLM 禁用的隔离端口实测成功；5/5 来源包含 Rerank 分数，最高 0.991705 |
| Prometheus / Grafana | 6/6 Targets UP；Grafana 13.2.1 数据库 `ok` |
| Qdrant 数据迁移 | 49 个已有向量离线迁移成功；Alias 精确计数 49，自相似 kNN score=1.0 |
| SSE 真实调用 | HTTP 200，917 个 token 事件、1 个 sources、1 个 done |
| SSE 首 Token / 总耗时 | 4023 ms / 9525 ms |
| MySQL 数据保留 | 32 篇未删除知识文档，未清库、未删除 named volume |

SSE 验收问题为“生产服务器磁盘使用率超过 90% 时应该如何处理？”。报告只记录事件数量和耗时，不保存问题对应的生成正文或内部 Context。

## 评测集

`rag-eval/cases/retrieval_cases.jsonl` 包含 50 条检索问题，其中 42 条有答案、8 条无答案，并包含 Redis、MySQL、RabbitMQ、Nacos、Sentinel、JVM、Gateway、发布、事故和磁盘场景及 Hard Negative。`answer_cases.jsonl` 包含 10 条回答/Citation 用例。

运行命令：

```powershell
python .\rag-eval\scripts\evaluate.py --password 'Admin@123'
python .\rag-eval\scripts\evaluate.py --password 'Admin@123' --include-answers
```

## 当前不能给出的质量数字

实时库有 26 篇已发布文档，当前明确的 OpenAI Embedding 外发授权范围只有最初 21 篇。本轮没有对新增文档执行外部 Embedding；Qdrant 已通过复用本机旧索引向量获得 49 个点，但这不等于重新完成了全量质量评测，因此不能据此编造 Hybrid/Rerank 的 Recall、MRR、nDCG 和 Citation 命中率。

获得新增文档的明确外发授权并向 Compose 注入 OpenAI Key 后，应依次执行：全量 Reindex、等待任务 SUCCESS、通过 Java 链路验证 BGE Rerank、四种检索模式离线评测，再把 `rag-eval/result` 的原始 JSON 和报告数字补入本文。BGE 边车本身已经用非敏感合成文本完成真实推理；尚缺的是基于重新生成向量的端到端质量验收。禁止用随机向量或历史结果替代真实数字。
