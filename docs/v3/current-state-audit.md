# OpsAgent V3 当前状态审计

审计日期：2026-09-06。正式工程为 `D:/myselfProject/opsagent`；当前分支 `dev`，HEAD `54449a86ed540ca4298d87933e7efbf77c7ad657`。前一轮上线代码尚未提交，不能把 HEAD 当成运行版本。继续在这些改动上增量实现，不执行 reset、不清库。

## G0 发布与资源基线

- 已发布版本：`observability-workspace-20260906-r1`。Web 镜像 `0d3c87833dd97a29613cffa7a6796a9a1aac910595f49806cc4330bcdac4ae4b`；静态入口 `/assets/index-KKWrRC8o.js`。
- 前一轮最终源包 SHA-256：`3825eb5f5d7b5513fc83f2eb6c5a0ec1cd484aeacdff0272fad62f77c3417e38`。已另存不可变基线 `data/public-deploy/v3/baseline-r1-source.tgz`。
- 云端为 Docker Compose，运行目录 `/opt/opsagent/deploy/public`，源码 `/opt/opsagent/source`。中间件没有公网管理端口，现有秘密配置与生产卷保持原位。
- 本轮开始时 23 个容器运行；12 GB 主机 available 5162 MiB，Swap 0，磁盘余量 141 GB。各镜像/容器/内存详情见 `data/public-deploy/v3/cloud-baseline.log`，不包含环境变量秘密。
- 已执行上一轮 `20260906_observability_workspace.sql`、`20260906_observability_catalog.sql`、`20260906_traffic_governance.sql`，受影响库 `ops_platform`。新增迁移采用兼容表/字段，不重建库。
- 已有 Prometheus 3.14.0、Nacos 3.0.3、RabbitMQ 3.13、Grafana 13.2.1。未发现实际 Trace 存储与跨进程追踪，仅有本地日志 request/trace ID。
- 用户已授权系统、账号、云部署、中间件和隔离演练；保持原有精确审批、目标白名单、TTL、预算和业务恢复验证，不扩展到生产故障注入。

## 八项问题分层结论

| ID | 已核实当前实现与缺口 | 本轮处理 |
| --- | --- | --- |
| U-01 | UI 已共用同一 AI store/SSE，但浮球只有图标，无账号级首次引导 | 明确标签、一次提示、上下文可移除、减少动态效果 |
| U-02 | AppLayout 导航即时改变，正文仍 out-in 退场，存在不一致时序；不是已完成逐帧复现 | 统一外壳提交，移除冲突离场过渡，补导航时序验证 |
| U-03 | 已使用 G6 5.0.49，23 节点33登记关系；上一轮五列网格已上线，非公开旧自研组件 | 类型化精简、分组、隐藏治理边、邻域聚焦 |
| U-04 | 指标刷新仍 setData/render 并重跑网格；resize 当前只 setSize，不应误报为每次 fit | 解耦结构/指标/视口，明确手势和持久化，十轮刷新验证 |
| U-05 | API/适配器已有；仅Java job/探针充分接入。up=0直接CRITICAL、Sentinel有Block即降级的旧规则需纠正；巡检缺持久调度执行证据 | 独立观测状态与业务健康，RabbitMQ/Nacos真实接入，复用调度器与运行租约 |
| U-06 | Nacos真实源读取存在。六个应用YAML实际源内容相同，均为接入标记片段，不是完整运行配置；现有业务配置可CAS发布但没有审批 | 完整来源身份/能力、共享解释、受控提案接现有Agent审批、源与目标验证分离 |
| U-07 | 首页仍以最多七条事件主区为主，没有拓扑摘要 | 拓扑与覆盖态势为主体，保留少量关键行动 |
| U-08 | 前轮有事件/工具/审批/确定性恢复与知识审核；真实Trace、实例历史、历史拓扑、差异和后端统一证据包尚缺 | 在已有事实源上补齐五项P3并逐项运行验收 |

上述分层代码和源核对证据分别保存在 `data/public-deploy/v3/frontend-audit.md`、`configuration-audit.md`、`configuration-read-audit.json`、`monitoring-audit.md`。运行态验证随阶段追加，未运行项不写 PASS。

## 关键技术决定

1. 保留 G6 5.0.49 和原主题，不引入第二个图引擎。
2. 没有现成Trace后端，采用 OpenTelemetry Java Agent → 单实例Collector → Tempo，Collector单处生成服务图指标供现有Prometheus抓取。不引入Kubernetes、Loki、图数据库或第二套Trace存储。
3. 先接既有隔离订单/通知与关键调用者，服务身份显式包含PROD/DEMO与CI。客户端推断的中间件peer不承担服务器健康结论。
4. 配置写入只开放既有隔离业务非敏感白名单；持久化提案是不可变意图，审批和执行继续使用AgentRun与原审批表，不新增审批事实源。
5. 普通拓扑摘要初始每分钟采集、72小时保留；事件固定证据独立保留，不以短期清理删除。以上为待实际容量验证的初始预算。
6. Compose环境提供实际实例/进程信息，不伪造Pod。历史自本轮启用起累计，不补造过去。

## G1 接入与配置清单

逐节点真实端点、targets/样本、身份和原因由监控审计补齐；配置的完整身份、只读/可写范围、源与应用证据由配置审计补齐。保持读取失败、未接入和不支持的区别，不能为了覆盖率把未知改成绿色。
