# OpsAgent 隔离订单服务故障处置手册

适用对象：ops-demo-order-service。该目标提供真实订单预览请求并读取独立 Redis，仅用于受控演示，不承载 OpsAgent 的登录、工单或正式业务数据。

## Nacos Redis 配置漂移

场景代码为 NACOS_REDIS_CONFIG_DRIFT。正常 Redis 连接端口为 6379；演练将专用配置改为固定无监听端口 6380，真实订单预览返回 HTTP 503，原因 REDIS_CONNECT_FAILED。

诊断应核对同一 incident 的目标快照：configurationSource 为 NACOS、configurationStatus 为 APPLIED、redisPort 为 6380，并结合工单告警时间和业务探针。该场景的故障点是连接配置，无需重启 OpsAgent 核心 Redis 或删除任何数据。

匹配修复工具是 demo_config_restore。工具使用当前观测的 expectedRevision，申请对本次隔离事件恢复固定配置基线。审批只针对该次工具、参数和目标；如果版本变化，应重新观测，不沿用旧审批。

## Sentinel 限流规则异常

场景代码为 SENTINEL_RULE_REGRESSION。专用资源为 ops-demo-order-query，正常 QPS 阈值为 5；故障阈值为 0。实际请求会被 Sentinel 拦截，返回 HTTP 429，blockedTotal 增加。

诊断应比较 sentinel.resource、qps、blockedTotal 与 business.httpStatus，区分限流和 Redis 连接失败。累计通过、拦截数是进程启动以来的计数，不是瞬时 QPS。

匹配修复工具是 demo_flow_restore。它在精确动作审批通过后恢复该资源的固定基线规则，不关闭整套系统的限流保护。

## 恢复证据与工单收口

配置发布回执只证明写入被接受。Nacos 配置可能短暂存在读延迟，必须观察目标实际加载的 revision 与业务结果。

恢复需同时满足：本次 incident 仍匹配；新鲜业务探针连续成功至少三次、HTTP 200；同一告警 episode 已收到 resolved；工单经过合法状态流转进入 RESOLVED 或 CLOSED。

恢复来源 AGENT_TOOL 表示受控 Agent 工具执行，MANUAL 表示人工恢复，TTL_GUARD 表示到期保护。三种来源分别记录，不能互相冒充。默认故障最长保留 15 分钟，到期由目标自己的保护机制处理。

工作流等待告警恢复有上限，不能用“模型说已恢复”替代探针，也不能在等待耗尽后强制标记工单解决。运行证据和工单历史用于复核每一步实际动作。
