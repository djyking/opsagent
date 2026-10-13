# OpsAgent 演示环境资源优化与验收

日期：2026-09-05。目标：个位数用户访问的完整系统演示。修改、构建和实际部署均位于 `D:\myselfProject\opsagent`。

## 结果与容量建议

18 个服务继续运行。按相同 Docker 工作集口径，优化前 3 次采样末值为 **9.70 GiB**；页面、验证码、检索和真实问答预热后，优化配置的空闲末值为 **4.75 GiB**，下降约 **51%**。持续访问时采样峰值为 **4.78 GiB**。这是本轮工作负载的观测结果，不是所有未来负载的内存上界或长期泄漏测试。

| 指标 | 优化前 | 优化后 |
|---|---:|---:|
| 全部容器 Docker 工作集合计 | 9.70 GiB | 4.75 GiB |
| 六个业务 Java 服务合计 | 4.34 GiB | 1.69 GiB |
| Nacos Docker 工作集 | 1,431 MiB | 591 MiB |
| Nacos 进程线程 | 1,037 | 约 197–199 |
| Nacos gRPC 工作线程 | 512 | 8 |
| 每个数据库连接池 | 常驻 10 / 最大 10 | 常驻 1 / 最大 5 |
| MySQL 工作集 | 632 MiB | 350 MiB |
| Elasticsearch 工作集 | 1,306 MiB | 932 MiB |
| Sentinel 工作集 | 404 MiB | 139 MiB |
| Qdrant 工作集 | 255 MiB | 28 MiB |

Docker 工作集通常扣除 inactive file cache。模型文件映射页还可能在共享文件的其他 cgroup 中记账，不能把屏幕显示的 4.75 GiB 等同于新云主机整机只需要 4.75 GiB。本机优化后 cgroup.current 合计约 4.91 GiB，也受现有模型缓存记账影响。

因此另做了真正冷缓存的重排验证：复制模型到隔离容器的新 inode，逐文件 fsync/fadvise，仅驱逐新副本缓存；mincore 确认 **554,461 个模型页中 0 页驻留**。在 **2 CPU、3 GiB 硬上限、无 swap、无外网** 下，模型加载约 9.02 秒，30 候选 × 512 token 推理约 49.76 秒，cgroup 峰值 **2.145 GiB**，无 OOM、无 swap、未触碰内存硬限。12 候选 × 512 token 约 18.67 秒。临时容器已删除，原模型卷未修改。

购买建议：**12 GB 内存适合作为本项目低并发展示的目标配置**，留出模型冷加载、操作系统和请求峰值的空间；16 GB 更宽裕。8 GB 需要更严格的整机验证，当前不建议为了省钱直接按 8 GB 购买。2 核可以展示，但重排会占用明显 CPU 时间；4 核更有利于边问答边浏览。以上不是对尚未部署的 ARM 或云端共享 CPU 的性能保证。

各容器硬上限合计约 13.31 GiB，它们是独立服务的峰值保护，不是预分配或保证可同时用满的总预算。12 GB 主机不能承受所有容器同时触顶，上云后还需按实际主机检查合计占用。

## 实施内容

### 1. 可切换的演示资源配置

新增 `compose.demo.yaml`，与原 `compose.yaml` 合并使用。启动脚本默认加载演示配置；`.env` / `.env.example` 的 `COMPOSE_FILE` 也使普通 Compose 命令默认合并该文件，显式设置路径分隔符保持 Windows / Linux 一致；`-StandardResources` 可使用基础配置。所有业务与中间件功能继续运行。

通过 `SPRING_PROFILES_INCLUDE=demo` 加载六个 `application-demo.yml`，不会替换原有业务配置和身份信息。普通模式不加载这些线程池参数。JVM 使用 2 个逻辑处理器的预算、Serial GC、小初始堆，收敛代码缓存、Direct 内存及 Netty 分配器 arena 数；glibc 进程限制 arena 数量，降低线程较多时的 native 分配开销。

| 服务 | 堆设置 | 容器上限 |
|---|---|---:|
| Auth / Ticket / Platform | Xms 64 MiB / Xmx 320 MiB | 每个 768 MiB |
| RAG | Xms 64 MiB / Xmx 320 MiB | 896 MiB |
| Knowledge | Xms 64 MiB / Xmx 448 MiB | 1,024 MiB |
| Gateway | Xms 64 MiB / Xmx 256 MiB | 768 MiB |
| Nacos | Xms 128 MiB / Xmx 384 MiB | 768 MiB |
| Sentinel | Xms 32 MiB / Xmx 192 MiB | 512 MiB |
| Elasticsearch | Xms / Xmx 512 MiB | 1,536 MiB |
| 本地重排 | 原 Float32 模型 | 3,072 MiB |

容器限制覆盖堆、native 分配和文件缓存等，不能只按 JVM Xmx 分配同等大小的容器。

### 2. Nacos 线程与持久化

针对运行中的 Nacos 3.0.3 镜像启动脚本及 `RemoteUtils` / `EnvUtil` 实现核实参数：

- `-XX:ActiveProcessorCount=2`、`-Dnacos.core.sys.basic.processors=2`。
- `-Dremote.executor.times.of.processors=4`，得到 2 × 4 = 8 个 SDK RPC 工作线程；队列上限设为 256。
- HTTP 线程常驻 2、最多 32；减少按宿主机 CPU 数推导的线程池。
- JVM 参数使用 `JAVA_TOOL_OPTIONS`；该镜像的 `JAVA_OPT_EXT` 放在 `-jar` 之后，不用它传 JVM 参数。
- 清空默认 `JVM_XMN`，避免旧的 512 MiB 新生代参数与缩小后的堆冲突。

增加 `nacos-data` 持久化卷和 readiness 检查。Java 服务等待 Nacos 就绪后启动。首次迁移脚本停止原 Nacos，备份并复制整个 `/home/nacos/data`，不覆盖已有数据卷。

本次迁移前后 **7 份 Nacos 配置内容 SHA-256 完全一致**，包含 6 个服务配置及 Sentinel 流控规则。备份在 `data/nacos-migration/20260905-200949/`，已被 Git 忽略。

### 3. 应用线程与连接

- 5 个 MVC 服务：Tomcat 常驻线程 2、最大 32，连接上限 256、等待连接队列 64。
- Hikari：常驻 1、最大 5，空闲 60 秒回收。已通过真实运行指标验证 1/5 生效。
- Knowledge Redisson：工作 / 网络线程 2/2；普通连接常驻 1、最多 8；订阅连接常驻 1、最多 2，沿用原认证和分布式锁。
- RabbitMQ 消费：每个队列 1 个消费者、prefetch 1，减少解析和索引任务预取积压。
- Gateway：每个下游固定连接池最多 32 条，空闲连接回收，保留 SSE 长响应。
- RAG SSE：最多 8 条并发、无长等待队列，空闲线程 30 秒回收。满载时返回明确错误，并将对应会话请求标记失败，避免一直显示处理中。

### 4. 本地重排峰值控制

原模型、Float32 权重、最大 512 token 输入和响应结构保留。演示模式 batch=2、CPU 线程=2、interop=1、单 Uvicorn worker、推理并发=1。

繁忙时立即返回 503 与 Retry-After，Java 沿用检索排序回退；请求取消不会在后台推理仍运行时提前释放推理槽。健康接口增加 active/busy/rejected 状态。

12 个长候选在 2 CPU 下实测接近原 20 秒超时，因此演示模式单独设置 60 秒重排超时，给共享 CPU 留余量；不是人为把所有问答等待时间延长到 60 秒。

### 5. 其他中间件

- MySQL：128 MiB buffer pool、最多 50 个连接，收敛表缓存、线程缓存和内存临时表预算。
- RabbitMQ：Erlang scheduler 2、异步线程 4，保留当前主机名作为节点身份，避免重建容器后指向新的 Mnesia 数据目录。现有身份写在忽略的 `.env`，新安装使用稳定默认主机名。
- Elasticsearch：保留 512 MiB heap 与既有索引，按 2 CPU 配置工作线程。
- Qdrant：2 个服务 / 搜索线程，优化 CPU 预算 1。`MaxOptimizationThreads` 在该版本需要 YAML 整数，使用 `config/demo/qdrant.yaml`；直接传环境字符串会启动失败，已在应用阶段修正。
- Grafana / Prometheus / Alertmanager：设置 Go CPU 和软内存预算；其他容器设置明确上限。
- Nginx：根据 CPU quota 自动调整为 1 个 worker。

## 验收

- Maven 全量 `clean verify`：**125 个测试通过，0 失败、0 错误、0 跳过**；Checkstyle 通过。本机 Mockito 使用显式 javaagent 参数完成验证，无需修改项目 POM。
- reranker 5 个针对性测试通过，覆盖过载、取消、失败释放、关闭和健康 / 排序。
- 最终 18 个容器运行正常；具备 healthcheck 的容器全部 healthy，最终容器 OOM / 重启次数均为 0，采样时 swap 为 0。
- 优化配置下真实随机验证码及已有管理员账号登录成功。
- 工单、SLA 分页、服务目录、班次分页、知识库、已有文档切片 / 指定文档检索可用。
- 5 个并发访问者持续约 75 秒：**1,605 次成功请求，0 失败，接口 p95 约 31 ms**；同期本地 12 个长候选重排约 19.7 秒成功。此延迟来自本机网络，不代表公网或 2 核云主机的最终延迟。
- 真实知识问答使用 **DeepSeek v4 Flash**，`rerankApplied=true`、`generationComplete=true`、`finishReason=stop`，返回 6 个引用，无降级；服务清单与依赖关系的普通 / SSE 问答通过。
- Nacos 6 个服务注册恢复；7 份配置哈希保持一致；ES / Qdrant 的既有 49 条索引数据保留。
- RabbitMQ 原有队列、消费者及既有 4 条索引队列消息保留。没有通过重建空队列来制造资源下降。
- 默认启动脚本再次运行后，18 个容器 ID 均未变化，验证了重复启动的幂等性。

## 启动、测量与恢复

从项目根目录执行：

```powershell
# 默认演示资源配置，包含已经启用的本地重排
.\scripts\start-containers.ps1 -WithReranker

# 切回基础资源配置
.\scripts\start-containers.ps1 -WithReranker -StandardResources

# 只读测量，需要 PowerShell 7.3+
.\scripts\measure-resources.ps1 -Samples 6 -IntervalSeconds 10
```

测量脚本记录 Docker 工作集、cgroup current / peak / 文件缓存、OOM、重启次数、JVM 堆、线程与 Hikari 连接预算；不输出环境变量或凭据，不强制 GC。

验收原始输出在 `data/resource-audit/`：`baseline/`、`demo-load/`、`demo-steady/`、`rerank-cold/`、`api-verification.json`、`sustained-load.json` 和构建 / 测试日志，均已被 Git 忽略。数据库一致性备份和环境备份也在该目录，不随代码或镜像发布。临时验收 token 文件已删除。

原业务 / 重排镜像另保留 `before-demo-20260905` 标签用于本机回退；未提交 Git，也未购买或创建云资源。
