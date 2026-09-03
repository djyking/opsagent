# OpsAgent 企业级改造与验收报告

## 1. 改造结论

项目已按“星云商城内部生产运维服务台 + 智能知识助手”完成可运行改造。Java 服务没有制作 Docker 镜像；MySQL、Redis、Nacos、Sentinel、RabbitMQ、Elasticsearch、Prometheus、Grafana 全部由 `compose.yaml` 管理。

数据库迁移前备份位于 `D:\middleware\backups\opsagent-before-docker-20260903-150024.sql`。Docker MySQL 使用独立 named volume，停止脚本不会删除卷。

## 2. 已实现业务链路

| 链路 | 实现 | 实际验收 |
|---|---|---|
| Ticket Outbox | 事务内写 Outbox、定时扫描、Publisher Confirm、失败指数退避、超时 PUBLISHING 回收 | RabbitMQ 停止期间工单 2041 创建成功，事件为 FAILED；恢复后自动变为 SENT；进程崩溃窗口中的陈旧 PUBLISHING 也会重新发布 |
| Platform 审计 | `ticket.#` 消费、独立数据库落库、eventId 幂等、审计查询 API、DLQ | 当前消费幂等表 86 条、审计表 88 条；同 eventId 重投后审计数量不增加 |
| 文档异步解析 | 快速创建任务、JSON 消息、Tika 解析、短事务切片 | 18 篇 Markdown 全部 SUCCESS/PARSED |
| 文档重试与死信 | 监听器最多三次、错误摘要、任务失败、DLQ | 损坏 PDF 的 retry_count=3，DLQ 恰有 1 条 |
| 企业演示数据 | 多角色、40 张企业工单、完整大促事故、评论、附件、4 个知识库 | Docker MySQL 中共有 10 个账号、50 张工单、8 个知识库 |
| RAG | 22 篇可用文档全量向量化、权限 KNN、Provider 原生 SSE、Citation 校验 | 25 个切片均由 `text-embedding-3-small` 索引；DeepSeek SSE 返回真实分段 Token |
| 文档删除补偿 | 创建人/ADMIN 软删除、ES 即时删除、持久化指数退避 | 停止 ES 后任务进入 RETRYING；恢复后自动 SUCCESS，向量 1→0 |
| RAG 限流 | Sentinel 资源保护、Nacos FlowRule 持久化 | 12 个并发请求中 6 个被 5 QPS 规则拦截 |
| 监控 | Prometheus 容器抓宿主 Java，Grafana 自动预置 | Prometheus 6/6 targets UP，Grafana 数据源返回 OK |

最终知识状态：22 篇可用文档均为 `INDEXED`，25 个有效切片均使用 `text-embedding-3-small`，1 篇故意损坏 PDF 为 `FAILED`；另保留 1 条本轮删除补偿测试的软删除记录。主解析队列和平台审计队列均无积压，解析 DLQ 保留 1 条预期测试死信。

## 3. 企业账号与数据

管理员和企业演示账号的密码统一记录在仓库外的 `D:\middleware\docs\OpsAgent本地地址与密码.md`，本报告不保存明文密码：

| 账号 | 姓名/身份 | 角色 |
|---|---|---|
| zhangqi | 张琪（订单研发） | USER |
| liyan | 李妍（客服主管） | USER |
| chenyu | 陈宇（支付研发） | USER |
| zhaolei | 赵蕾（质量保障） | USER |
| wangwei | 王伟（SRE） | OPS |
| liuming | 刘明（数据库运维） | OPS |
| sunhao | 孙浩（中间件运维） | OPS |

企业脚本只把 BCrypt 摘要写入 `sys_user.password`，没有存储明文密码。

## 4. 五分钟演示路线

1. 打开 `http://127.0.0.1:5173/`，用张琪账号查看业务方工单。
2. 展示工单 2000“大促期间订单创建接口大量超时并出现429”的完整历史、评论与附件。
3. 用王伟账号演示接单和状态流转，观察 `event_outbox` 从 PENDING 到 SENT。
4. 用管理员访问 `/api/platform/admin/audits?bizId=2000`，说明平台服务不跨库查询 Ticket，而是异步消费事件。
5. 在 RAG 页面提问，展示逐 Token 输出、最终 Citation 校验和真实 Runbook 来源；生成 Provider 接收内部上下文前应确认相应数据处理授权。
6. 打开 RabbitMQ 的四个主/死信队列，再展示损坏 PDF 对应的三次重试和单条 DLQ。
7. 打开 Prometheus Targets（6/6 UP）和 Grafana OpsAgent Overview 的 MQ 指标。

## 5. 启停与初始化

```powershell
# 全部中间件 + 本机 Java + 前端
D:\middleware\scripts\start-opsagent.ps1

# 仅中间件
D:\middleware\scripts\start-opsagent.ps1 -MiddlewareOnly

# 重新生成企业文件、重建约定演示区间并真实上传解析
.\demo-data\scripts\Initialize-EnterpriseDemo.ps1

# 查看状态或停止（保留数据库卷）
D:\middleware\scripts\status-opsagent.ps1
D:\middleware\scripts\stop-opsagent.ps1
```

## 6. 尚未实际实现

| 项目 | 当前状态 | 未实现原因 | 后续工作 |
|---|---|---|---|
| 多 Provider 自动 Fallback | 未启用 | OpenAI、DeepSeek、Kimi 均已接入并实测；自动切换会改变上下文接收方 | 建立 Provider allowlist、数据策略和成本策略后再启用 |
| Sentinel 全局共享限流 | 部分实现 | Nacos 已持久化 5 QPS FlowRule，但普通客户端仍每实例计数 | 严格多实例总额度需部署 Cluster Token Server |
| Redis 问答缓存 | 未实现 | 权限和文档版本会影响答案，直接共享缓存有越权风险 | 缓存键加入用户权限、文档版本和 Prompt 版本，并实现删除失效 |
| Java 虚拟线程 | 未实现 | Java 17 没有正式虚拟线程 API，Java 21 才正式提供 | 升级 Java 21 后用于文档 I/O；数据库连接等稀缺资源仍需限流 |
| OCR | 未实现 | Tika 只能处理文本型 PDF，扫描件需要 OCR 运行时 | 评估 Tesseract 或云 OCR，并增加文件隔离和资源限制 |
| 工单附件上传 API | 仅有真实文件和数据库元数据 | 当前 Ticket 模块没有附件存储接口 | 复用 Knowledge 的文件校验/存储抽象，补上传、下载和权限校验 |
| 生产告警规则 | 未配置 | 本地演示缺少接收人、SLO 和通知渠道 | 添加 Prometheus rule、Alertmanager 及企业通知渠道 |

AI/RAG 的详细实现、真实 Provider 测试和限制见 `docs/OpsAgent_AI_RAG_实施与验收报告.md`。以上未实现项不会在界面或文档中标记为“已接入”。
