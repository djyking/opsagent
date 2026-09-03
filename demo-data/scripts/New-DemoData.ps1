param([string]$OutputRoot = (Join-Path (Split-Path $PSScriptRoot -Parent) "generated"))

$ErrorActionPreference = "Stop"
$docs = Join-Path $OutputRoot "knowledge-docs"
$attachments = Join-Path $OutputRoot "attachments"
New-Item -ItemType Directory -Force -Path $docs, $attachments | Out-Null

$scenarios = @(
    @("201-01-order-timeout.md","订单创建接口超时处置手册","订单服务、库存服务及网关","下单成功率下降，P99 延迟超过 3 秒并伴随 429","http_server_requests、Hikari pending、Sentinel block QPS","冻结发布，按区域和渠道降级，检查连接池与库存依赖，再灰度恢复限流阈值"),
    @("201-02-payment-callback.md","支付回调重复消费处置手册","支付回调和订单状态同步链路","同一支付单多次回调，消费日志重复但业务状态不一致","队列 redelivered、幂等冲突数、回调成功率","隔离异常消息，核对业务唯一键和 ACK 时机，修复后按支付单分批回放"),
    @("201-03-gateway-429.md","网关 429 与热点限流排查手册","Spring Cloud Gateway 与 Sentinel","部分接口出现 429，普通流量也被误伤","block QPS、pass QPS、热点参数分布、网关 P99","确认规则来源和生效范围，先灰度调整阈值，再观察错误率和下游容量"),
    @("201-04-jvm-old-gc.md","JVM Old GC 频繁处置手册","Java 17 微服务进程","停顿时间增加，Old 区回收后占用仍高","jvm_gc_pause、old generation used、allocation rate","保留 GC 日志和堆转储，先限流或扩容止损，再分析对象增长路径"),
    @("201-05-thread-pool.md","线程池队列积压处置手册","异步任务、营销批处理和接口线程池","活跃线程达到上限，队列持续增长并触发拒绝","active count、queue size、reject count、下游 P99","暂停非核心任务，限制生产速率，识别慢下游后分批恢复"),
    @("202-01-mysql-pool.md","MySQL 连接池耗尽处置手册","MySQL 8 与 HikariCP","获取连接超时，应用 pending 增长","active、idle、pending、threads_connected、慢查询","阻断异常流量，终止确认无害的长事务，优化慢 SQL 后再评估池容量"),
    @("202-02-mysql-lock.md","MySQL 锁等待与死锁处置手册","订单和库存核心数据库","事务延迟突增，日志出现 deadlock 或 lock wait timeout","data_locks、data_lock_waits、事务持续时间","保存死锁证据，定位持锁事务和访问顺序，谨慎终止阻塞源并修复事务边界"),
    @("202-03-redis-hit-rate.md","Redis 命中率下降处置手册","商品、会员和营销缓存","命中率低于基线且数据库回源 QPS 上涨","keyspace_hits、keyspace_misses、evicted_keys、used_memory","检查集中失效和淘汰策略，对热点键增加随机过期并限制回源并发"),
    @("202-04-redis-hot-key.md","Redis 热点键与大 Key 处置手册","Redis 集群及客户端连接池","单节点带宽或 CPU 偏高，个别命令耗时异常","hotkeys、slowlog、网络带宽、命令 P99","只读扫描识别热点和大 Key，采用拆分、本地缓存或异步删除进行治理"),
    @("202-05-rabbitmq-backlog.md","RabbitMQ 消息积压处置手册","文档解析和工单审计队列","ready 数持续增长，消费速率低于生产速率","messages_ready、messages_unacked、publish rate、ack rate","检查消费者健康与下游容量，必要时水平扩容，修复后控制回放速率"),
    @("202-06-rabbitmq-dlq.md","RabbitMQ 重试与死信处理手册","解析 DLQ 和平台审计 DLQ","重试三次后消息进入死信队列","DLQ depth、retry count、消费失败计数","按 eventId 关联任务和日志，修复根因后复制到主交换机，依赖消费幂等避免重复副作用"),
    @("202-07-nacos-drift.md","Nacos 配置不一致处置手册","配置中心和服务发现客户端","同服务实例读取到不同配置或注册状态漂移","配置 MD5、长轮询日志、实例心跳时间","核对 namespace、group、dataId 与客户端缓存，灰度重新发布并逐实例确认"),
    @("202-08-sentinel-rule.md","Sentinel 规则变更处置手册","网关和各领域服务","规则未生效或阈值导致非预期阻断","pass QPS、block QPS、exception QPS、规则版本","对照容量基线审查规则，先单实例灰度，验证后再扩大范围"),
    @("203-01-gray-release.md","微服务灰度发布规范","所有星云商城 Java 微服务","计划发布或紧急修复需要降低变更风险","错误率、P95/P99、实例健康、核心业务成功率","完成变更审核和回滚预案，按 5%、20%、50%、100% 阶段放量"),
    @("203-02-rollback.md","生产回滚与配置恢复规范","应用版本、Nacos 配置和数据库兼容变更","发布后核心指标恶化或出现数据兼容风险","版本分布、错误率、配置 MD5、数据库异常","停止放量，按预案先回应用和配置；数据库只执行经过评审的前向修复"),
    @("203-03-capacity.md","大促容量评估与压测规范","订单、支付、库存、Redis 与 RabbitMQ","大促前需要验证峰值容量和降级能力","目标 TPS、资源水位、P99、队列积压、连接数","基于历史峰值乘安全系数，压测完整链路并验证限流、降级和扩容时间"),
    @("204-01-20260828-review.md","2026-08-28 订单超时事故复盘","订单创建链路","大促期间订单 P99 升至 8.6 秒且网关出现 429","下单成功率、Sentinel block QPS、连接池 pending","修正规则容量基线，补充大促前灰度校验和联合值守清单"),
    @("204-02-document-dlq-review.md","知识文档解析死信演练复盘","Knowledge 文档解析 RabbitMQ 链路","损坏 PDF 连续解析失败并进入 DLQ","parse failure、retry count、DLQ depth、任务状态","使用 eventId 定位失败任务，验证错误脱敏、三次重试、死信与人工回放流程")
)

$template = @'
# {0}

## 文档信息

- 所属公司：星云商城（Nebula Mall，虚构演示环境）
- 适用范围：{1}
- 维护角色：SRE / 相关服务研发 / 值班负责人
- 变更原则：先止损、保留证据、单点灰度、持续观测、可随时回退

## 典型现象与影响判断

典型现象为：{2}。收到告警后先确认告警是否仍在持续，记录开始时间、受影响区域、调用方、接口和最近一次发布或配置变更。业务影响必须使用成功率、失败订单数或受影响用户数描述，不用“问题很严重”之类的模糊结论。若影响支付、订单创建或库存扣减，立即升级为 P1，并冻结同链路非必要变更。

## 关键指标

重点观察：{3}。至少比较故障前十五分钟、故障窗口和处置后三十分钟，不凭单个瞬时值下结论。Grafana 截图只作为辅助证据，必须同时保存查询条件、时间范围和原始日志片段。Prometheus 抓取失败时先确认目标是否 UP，不能把“没有数据”解释为指标已经恢复。

## 标准排查顺序

1. 在 OpsAgent 建立或关联工单，填写影响范围、发生时间、请求样本和负责人。
2. 检查最近三十分钟的应用发布、Nacos 配置、Sentinel 规则、数据库 DDL 和定时任务。
3. 从入口错误率和延迟开始，依次检查应用线程、缓存、消息队列、数据库及外部依赖。
4. 使用 traceId 或业务唯一键串联日志；无法串联时保留各系统的精确时间戳和实例名。
5. 形成一个可证伪的根因假设，只做最小变更验证；验证失败立即回退，不连续叠加多个动作。

## 处置动作

推荐动作：{4}。所有生产动作由执行人和复核人双人确认，在工单评论中记录动作前指标、命令意图、结果和下一观察点。扩容、限流、重启只能作为止损措施，根因修复必须落到代码、配置、容量模型或操作流程。需要回放消息时按小批量执行，并以 eventId 和业务唯一键确认幂等结果。

## 风险与禁止事项

- 禁止未留存线程转储、慢查询或消息样本就直接重启全部实例。
- 禁止为了消除告警无限扩大线程池、连接池、队列或超时时间。
- 禁止在生产日志和工单附件中记录密码、令牌、完整手机号及真实支付信息。
- 禁止直接清空 RabbitMQ 主队列或死信队列；任何丢弃都必须有审批和可追溯清单。
- 禁止绕过灰度一次性修改全部实例。数据库结构变更优先采用向前兼容设计。

## 恢复判定与复盘

核心成功率、错误率和 P99 连续三十分钟回到基线，积压已清零或稳定下降，且业务方完成真实场景回归后，方可标记 RESOLVED。由创建人确认后关闭工单。P1/P2 事故在两个工作日内完成复盘，内容包含时间线、根因、触发条件、止损动作、为何未提前发现以及负责人明确的改进项；新知识经评审后上传本知识库并触发异步解析。
'@

foreach ($scenario in $scenarios) {
    Set-Content -LiteralPath (Join-Path $docs $scenario[0]) `
        -Value ($template -f $scenario[1],$scenario[2],$scenario[3],$scenario[4],$scenario[5]) -Encoding utf8
}

$samples = @{
    "gateway-timeout-sample.log" = "2026-08-28T20:03:12+08:00 ERROR traceId=demo-a81f route=order-create status=504 elapsedMs=8021`n2026-08-28T20:03:13+08:00 WARN traceId=demo-b91e sentinelBlock=true resource=POST:/api/orders"
    "redis-metrics-20260828.txt" = "time=2026-08-28T20:15:00+08:00 keyspace_hits=184020 keyspace_misses=120381 hit_rate=60.45%`nused_memory_human=3.82G evicted_keys=18293 environment=demo"
    "nacos-config-before-after.txt" = "before: dataId=ops-gateway.yaml group=DEFAULT_GROUP order.rule.qps=800 md5=demo-old`nafter: dataId=ops-gateway.yaml group=DEFAULT_GROUP order.rule.qps=1600 md5=demo-new"
    "rabbitmq-queue-metrics.txt" = "queue=ops.knowledge.parse.queue messages_ready=126 messages_unacked=8 publish_rate=12.4 ack_rate=7.1`nqueue=ops.knowledge.parse.dlq messages_ready=1"
    "order-service-error-20260828.log" = "2026-08-28 20:03:12 ERROR traceId=demo-a81f SQLTransientConnectionException: Connection is not available`n2026-08-28 20:03:12 WARN traceId=demo-a81f inventory-call elapsedMs=4812 result=timeout"
}
foreach ($sample in $samples.GetEnumerator()) {
    Set-Content -LiteralPath (Join-Path $attachments $sample.Key) -Value $sample.Value -Encoding utf8
}
[IO.File]::WriteAllBytes((Join-Path $attachments "broken-demo.pdf"),[Text.Encoding]::UTF8.GetBytes("%PDF-1.7`nintentionally corrupted for DLQ demo"))
Write-Host "Generated $($scenarios.Count) documents and 6 attachments under $OutputRoot"
