# OpsAgent RAG 架构与运行机制

## 在线问答

```mermaid
flowchart TD
    UI[Vue 问答工作台]
    GW[Gateway 18080]
    RL[Sentinel 限流]
    RAG[RAG Service]
    KS[Knowledge Service]
    B[Elasticsearch BM25 和 SmartCN]
    E[OpenAI Query Embedding]
    V[Qdrant 向量检索]
    F[RRF 融合 Top30]
    RR[BGE Rerank 可选]
    CA[去重、邻居、配额和 Token Budget]
    LLM[OpenAI、DeepSeek 或 Kimi]
    CV[Citation 校验]

    UI -->|SSE /api/rag/stream| GW
    GW --> RL
    RL --> RAG
    RAG --> KS
    KS --> B
    KS --> E
    E --> V
    B --> F
    V --> F
    F --> RR
    RR --> CA
    CA --> LLM
    LLM --> CV
    CV -->|token、sources、done| UI
```

Embedding 或向量检索失败时 Knowledge Service 降级为 BM25；BGE 不可用或超过配置超时时保持 RRF 顺序；LLM 不可用时返回检索证据。降级会记录指标和结构化元数据。代码默认 Top30/3 秒，本机 CPU 启动脚本采用 5 候选/20 秒；有 GPU 或独立推理服务时应压测后恢复更大的候选池。

## 文档入库与索引一致性

```mermaid
flowchart TD
    U[上传文档] --> P[解析队列]
    P --> C[结构化解析与切片]
    C --> D[(MySQL 文档/Chunk)]
    D --> R[提交审核]
    R --> A{OPS/ADMIN 审核}
    A -->|驳回| X[REJECTED]
    A -->|通过| PUB[PUBLISHED]
    PUB --> O[(Knowledge Outbox)]
    O --> MQ[RabbitMQ 索引队列]
    MQ --> I[Embedding + ES BM25 + Qdrant Vector]
    I --> S[index_status=SUCCESS]
    I -->|可重试错误| RETRY[重试/补偿]
    I -->|不可重试或耗尽| DLQ[索引 DLQ / FAILED]
```

发布状态 `review_status` 与技术索引状态 `index_status` 分离。RAG 只能检索已发布并且当前用户有权访问的文档。删除和归档写入 DELETE 补偿任务，避免 ES 残留可检索内容。

## Reindex

```mermaid
sequenceDiagram
    participant Admin as 管理员
    participant API as Knowledge Admin API
    participant Lock as Redisson Lock
    participant DB as MySQL
    participant ES as Elasticsearch BM25
    participant QD as Qdrant Vector
    Admin->>API: POST /admin/reindex
    API->>DB: 创建 PENDING 任务
    API-->>Admin: taskId
    API->>Lock: 获取分布式锁
    API->>ES: 创建新版本物理索引
    API->>QD: 创建新版本 Collection
    loop 每篇已发布文档
        API->>DB: 读取结构化 Chunk
        API->>ES: Bulk 写入文本索引
        API->>QD: Bulk 写入向量与 Payload
        API->>DB: 更新进度
    end
    API->>ES: 校验文本索引数量
    API->>QD: 校验向量点数量
    API->>QD: 切换 Collection Alias
    API->>ES: 原子切换读写 Alias；失败则回滚 Qdrant
    API->>DB: SUCCESS / FAILED
    API->>Lock: 释放锁
```

管理入口为前端“知识索引管理”，后端接口统一位于 `/api/knowledge/admin`。旧 `/internal/reindex` 仅保留兼容，不应作为新运维流程入口。

## 数据与安全

- 问题日志只记录不可逆 query hash、模式、数量、耗时和降级原因，不记录问题正文、Prompt、Context 或 API Key。
- API Key 通过不提交 Git 的 Compose `.env`、Docker Secret 或宿主机 Shell 环境注入容器，不进入镜像层、Nacos、数据库和前端。
- Embedding 授权与生成模型授权是两个不同的数据处理目的；新增知识应在数据分级确认后再外发。
- Citation 来源由后端检索结果构造，模型不能自行扩充来源编号。
