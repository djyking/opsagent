# OpsAgent 公网部署验收记录

验收日期：2026-09-06（北京时间）。正式入口：**https://opsagent.cloud**。

## 交付结果

完整 Vue 前端、六个 Spring Cloud 服务及现有中间件部署到用户购买的 VPS。保留微服务、Nacos、Sentinel、Elasticsearch、Qdrant、RabbitMQ 和监控架构；前端使用生产构建，不依赖本地 5173、Windows 或电脑持续开机。

| 项目 | 实际情况 |
| --- | --- |
| VPS | 194.163.168.181；x86_64；6 vCPU；11960 MiB RAM；约 193 GiB 根磁盘 |
| 操作系统 | Ubuntu 24.04.4 LTS |
| Docker / Compose | Docker 29.8.0；Compose v5.5.1 |
| 公共 Nginx | 1.27.5；复用 ops-web-app 内置 Nginx，仅此一个公共反向代理 |
| 服务运行目录 | `/opt/opsagent/deploy/public` |
| 实际源码副本 | `/opt/opsagent/source` |
| 运行和构建记录 | `/opt/opsagent/runtime` |
| 备份 | `/opt/opsagent/backups`；首次完整迁移包保留在 incoming 及本机 |
| 本地源码 | `D:\myselfProject\opsagent` |
| Nginx 生效配置 | `/opt/opsagent/deploy/public/config/nginx/active.conf` |
| 云端私有环境 | `/opt/opsagent/deploy/public/secret.env`；0600权限，不入Git |

## URL、HTTPS 和端口

| 验收项 | 结果 |
| --- | --- |
| 初始 HTTP | IP、根域及 www 页面200；features和验证码接口200 |
| `https://opsagent.cloud/` | 200；正式入口 |
| `http://opsagent.cloud/` | 301到HTTPS根域 |
| `http://www.opsagent.cloud/` | 301到HTTPS根域，保留路径和查询参数 |
| `https://www.opsagent.cloud/` | 301到HTTPS根域，保留路径和查询参数 |
| SPA深链接 `/tickets` | 200，交由Vue路由处理 |
| 无效静态资源 | 404，不回退为HTML页面 |
| HTTPS信任验证 | 通过；Let's Encrypt YE2，覆盖根域和www |
| 当前证书有效期 | 2026-09-05 14:46:18 UTC 至 2026-12-04 14:46:17 UTC |
| 自动续期 | systemd每天检查两次；实际staging dry-run成功 |
| 公网TCP端口 | 仅22、80、443可连接；IPv4、IPv6防火墙一致 |
| 管理与业务端口 | MySQL、Redis、RabbitMQ、Nacos、Sentinel、ES、Qdrant、Grafana、Prometheus、reranker、Java和Gateway均不发布主机端口 |

API与Vue同源，通过 `/api/` 访问Gateway。Nginx关闭API缓存、SSE响应缓冲与请求缓冲，读写超时900秒。带hash静态资源缓存一年，HTML及API不缓存。外部 `/actuator`、Swagger、公开注册、知识内部Feign接口、Alertmanager内部回调均返回404；容器内部调用继续可用。

## 数据迁移

在本地短暂停写窗口内导出 MySQL 五个业务库和八个持久卷，再恢复本地18服务。MySQL采用单事务逻辑导出；ES、Qdrant、Nacos、RabbitMQ等使用停止后的卷归档。Redis会话未导入，公网JWT独立生成。

迁移包约50.7 MB，上传前后SHA256一致：

`e4f0761156f11fb464511dd41f28c0afafdf5f171df9205add2fed8fabe1b351`

已迁移工单62条、服务配置项13个、关系11条、知识库11个。检索索引保留49个切片与49个Qdrant点及读写别名。Qdrant抽样直接向量自相似查询得到约1.0分，确认向量、发布状态过滤及别名可工作。

首次导入后的上述数量是迁移快照。最终验收已发布切片为56个；逐ID核对发现一条源切片已不存在的历史向量，先保存完整向量备份，再仅删除该孤立索引点，数据库未变更。末次Qdrant与已发布切片均为56，无孤立索引点。历史无效消息由修复后的消费者正常ACK，全部业务队列已无积压，未执行队列清空。

RabbitMQ保留原节点hostname和持久消息。Nacos六个服务配置及Sentinel规则迁移，副本未发现本地地址、Windows路径或固定明文凭据覆盖公网环境的情况。

## 账号及公网防护

- 保留原admin的ID与业务归属，使用独立随机强密码。
- 新建demo，只有OPS角色，可查看待接单工单、CMDB、公开知识与AI问答，并可执行相应业务操作；并非只读账号。
- 禁用另外10个种子/测试账号，保留原记录和归属；撤销导入的refresh token。
- 关闭公开注册；验证码及后端验证继续启用。禁用账号不能刷新登录。
- MySQL使用独立应用账号，仅授权五个业务数据库。MySQL、Redis、RabbitMQ、Grafana、JWT及告警令牌独立轮换。
- AI全局并发2、每分钟10次、UTC每天100次；每日逻辑调用量持久化。它是调用次数预算，不是供应商金额硬上限；embedding仍使用用户原先启用的配置。
- 文档解析检查所有权及管理权限，事务锁防止并发重复提交同一解析任务。

明文账号在本机忽略文件 `data/public-deploy/OpsAgent_公网访问与账号.txt`，不写入本报告或公开网页。

迁移验收另发现两个已删除文档的历史FAILED索引任务被旧消息不断重试，重试次数达到30/36，下一次时间溢出到2094/6381年。修复为终态与旧版本消息幂等ACK、索引任务原子领取、最多300秒退避、到达次数上限后停止自动重试；保留失败历史，并清除这两个终态任务的无效下一次时间。未清空RabbitMQ队列或删除文档数据。

## 功能和流式验收

管理员通过真实随机验证码与HTTPS登录。工单列表/活动告警、SLA、班次分页、CMDB及关系、知识库、知识审核、索引一致性、通知、审计、问答会话和模型配置接口均返回成功。demo可看到14条待接单工单；越权访问管理员索引、审计、通知及模型管理接口返回403。

系统监控读取到六个业务Prometheus目标正常、Grafana健康；隐藏不对外公开的Grafana/Prometheus跳转按钮，继续显示监控数据。

真实公网SSE请求已验证 `text/event-stream`、`Cache-Control: no-store`、`X-Accel-Buffering: no`。模型使用 **DeepSeek deepseek-v4-flash**。复测收到1348个token事件、sources事件及done事件，完整回答2292字符、5条引用，`generationComplete=true`、`finishReason=stop`。分批内容在约40.6秒开始、54秒结束，证实没有等到全部生成后一次性返回。

云端12条较长候选的CPU重排实测约27秒。因此公网重排超时由继承的20秒调整为60秒；复测 `rerankApplied=true`。前端修正为检索等待90秒，首次生成状态后另计90秒，避免原先30秒总等待提前取消正常请求。结束、取消及异常均清理计时器。

向量查询保持原0.72相似度阈值，部分问法正常无向量命中，使用BM25加reranker。一次外部embedding调用出现临时不可用并自动回退BM25，后续检索复测恢复；降级在metadata中明确标识。没有为改变展示效果降低阈值或重嵌入全部文档。

浏览器自动化已读取公网登录页；部分表单操作因工具超时未完成全面页面点击和浏览器内逐字渲染验收。以上业务与SSE结果基于真实公网HTTPS接口，不将未完成的浏览器自动化标为通过。用户可直接登录同一站点验收。

## 资源及性能

保留已验证的demo内存和线程参数，使用独立公网Compose。18服务均运行，配置健康检查的14服务均healthy，运行验收期间无OOM和自动重启。约12 GiB主机在问答后仍有约6.2 GiB可用内存。

| 配置 | 公网参数 |
| --- | --- |
| Nacos | Xms128m/Xmx384m，容器768m，按2核限定线程派生 |
| 普通Java服务 | Xms64m，按模块Xmx256～448m，容器768～1024m |
| Hikari / Tomcat | 最小空闲1、最大连接5；Tomcat最小2、最大32 |
| Elasticsearch | 堆512m，容器1536m |
| reranker | 原F32模型，batch2，最长512，推理并发1，4 CPU，容器3 GiB |
| 模型版本 | BAAI/bge-reranker-v2-m3，revision `953dc6f6f85a1b2dbfca4c34a2796e7dde08d41e`，离线加载 |
| MySQL / Redis | 768m / 128m容器上限 |

容器上限并不代表预留内存；不建议为个位数用户无差别扩大线程池和所有JVM堆。此次优先增加重排CPU，并保留系统、文件缓存和请求峰值余量。云端vCPU的速度与本机不同，不能只凭核数推定响应时间。

## 运维、构建与后续操作

全后端 `clean verify` 通过138项测试；随后知识库重试修复的33项针对性测试及Checkstyle通过，重排异常日志的5项相关测试通过。前端生产构建与SSE现有测试通过，新增超时行为做了模拟计时器验证；生产镜像在云端顺序构建，避免12 GB主机并发六次Maven构建。

已启用 `opsagent-backup.timer` 和 `opsagent-cert-renew.timer`。每日在线备份五个MySQL库及知识附件，校验成功后保留7天。首次日常备份已实际成功，续期dry-run成功。备份位于同一服务器；初次完整迁移包同时保留在本机，日常备份尚未发送到异地存储。

操作方法、定时器、恢复步骤见同目录README。密钥、env、数据、证书、模型和迁移包全部排除Git；可提交部署Compose、Nginx配置、监控配置、运维脚本及源代码。

Cloudflare仍为DNS only，未登录或改动用户Cloudflare/域名注册商账号。现在可以由用户手动将 **A @** 和 **CNAME www** 改为 **Proxied**，SSL/TLS设为 **Full (strict)**。不要缓存 `/api/*` 或SSE。开启代理后还需针对Cloudflare链路再次验证问答；当前证书、重定向及SSE结论针对DNS only直连链路。

## Docker 工作集快照

此快照采集于问答后，数值会随查询和文件缓存变化；不能把工作集当作模型冷启动的峰值。

| 容器 | 工作集 / 上限 |
| --- | --- |
| opsagent-ops-rag-app-1 | 288.6MiB / 896MiB |
| opsagent-ops-web-app-1 | 4.469MiB / 96MiB |
| opsagent-grafana-1 | 234MiB / 512MiB |
| opsagent-ops-knowledge-app-1 | 333.8MiB / 1GiB |
| opsagent-ops-gateway-app-1 | 266.5MiB / 768MiB |
| opsagent-ops-ticket-app-1 | 307.4MiB / 768MiB |
| opsagent-ops-platform-app-1 | 279.2MiB / 768MiB |
| opsagent-prometheus-1 | 88.78MiB / 256MiB |
| opsagent-alertmanager-1 | 13.67MiB / 128MiB |
| opsagent-ops-auth-app-1 | 317.7MiB / 768MiB |
| opsagent-rabbitmq-1 | 146.6MiB / 384MiB |
| opsagent-qdrant-1 | 27.47MiB / 512MiB |
| opsagent-sentinel-1 | 166.6MiB / 512MiB |
| opsagent-elasticsearch-1 | 909.1MiB / 1.5GiB |
| opsagent-reranker-1 | 1.786GiB / 3GiB |
| opsagent-nacos-1 | 586.5MiB / 768MiB |
| opsagent-redis-1 | 6.145MiB / 128MiB |
| opsagent-mysql-1 | 355.6MiB / 768MiB |
