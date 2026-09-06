# OpsAgent V3 部署与恢复

运行目录 `/opt/opsagent/deploy/public`；正式源码 `D:/myselfProject/opsagent`；V3 计划独立发布目录 `/opt/opsagent/releases/v3-observation-20260906-r1`，与 R1 不共用 before 备份。最终执行结果见验收报告。

## 执行顺序

1. 全量Java验证、前端验证，打包受版本管理及新增源码；排除 data、target、node_modules、dist、秘密与会话。
2. 上传带SHA-256清单的源码包，逐个构建独立V3镜像标签；先不切换运行容器。
3. 确认无冲突演练/审批，执行原 `backup-daily.sh`，单独保存旧配置、秘密和镜像标签。备份权限0700，不进入公开产物。
4. 增量执行两份兼容SQL，应用真实监控配置与Rabbit插件持久设置，先切换兼容后端。
5. 核对G1实际采样、巡检和配置来源后，启用 `compose.observability-v3.yaml`。新Tempo专属卷使用10001身份，保留其他卷。
6. Trace组件仅接内部网络，五个Java服务通过只读Agent jar与入口脚本启用采集；保留原 `JAVA_TOOL_OPTIONS` 堆限制。
7. 切换Web，按三条真实场景验收；记录每个镜像ID、Web入口、资源与时间。新历史从本次启用开始积累。

## 日常操作

启用后运行Trace相关服务须同时指定两个Compose文件：

```bash
cd /opt/opsagent/deploy/public
docker compose --env-file secret.env -f compose.yaml -f compose.observability-v3.yaml ps
```

避免仅使用基础文件重建已埋点Java服务，否则会失去Agent环境与挂载。自动重启策略仍为 `unless-stopped`。普通数据库备份仍使用原脚本；短期Trace存储有独立保留期，不将其当成固定事件证据的唯一副本。

## 分层回滚

- 程序：将指定应用的 `before-v3-observation-20260906-r1` 标签恢复到 `1.0.0`，按本轮保存的基线Compose重新创建对应应用；保留新增表、事件、审批与审计，不执行降级删表。
- Trace：移除Java埋点需按基础Compose重建这五个服务，停止Collector/Tempo即可，**不删除**Tempo卷或数据库固定证据。监控配置移除servicegraph目标，避免无意的采集告警。
- 配置：业务配置通过系统创建版本化回滚提案，并走原Agent精确审批、CAS与实例验证；不能复制旧内容覆盖可能存在的后续变更。
- 演练：先用原安全恢复/TTL结束隔离故障，再考虑程序回滚。暂停配置应用演练仅在隔离目标内部开放，时限到期必须真实重新读取源并应用。

## 初始资源策略与边界

Collector 256MB、Tempo 512MB上限；有界队列、2秒批量、72小时Trace与普通历史摘要保留；不扩大原Java堆、不新增Swap。Trace查询最大6小时窗口，历史最大72小时；模型、工具、事件运行预算和原审批策略继续有效。最终CPU/内存/磁盘增长及拒收结果将在验收后记录。
