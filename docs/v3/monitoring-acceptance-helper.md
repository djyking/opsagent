# V3 云端监控验收辅助脚本

作者：heyu

脚本：`scripts/observability/v3-monitoring-acceptance.py`。只使用 Python 标准库，默认仅发送 GET；不会部署、修改 Docker 网络、直接调用调度器、修改数据库、登录或刷新令牌。`--run-manual` 是唯一 POST 开关，仅创建一次真实的只读巡检执行记录；响应不确定时不重试。

## 准备与默认只读检查

在项目根目录运行。默认站点 `https://opsagent.cloud`，默认凭证读取现有 `data/public-deploy/admin-session.json` 顶层 `accessToken`；也可以预先通过环境安全设置 `OPSAGENT_ACCESS_TOKEN`。不要将令牌放进命令参数或报告。过期凭证返回 BLOCKED，由操作者另行登录更新。

```powershell
Set-Location -LiteralPath D:\myselfProject\opsagent
$v3Python = 'C:\Users\77190\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe'
& $v3Python scripts/observability/v3-monitoring-acceptance.py --output data/public-deploy/v3/g1-baseline.json
```

默认核对实际 23 个 CI、唯一环境身份、生命周期、观测状态/诊断、采样新鲜度、实例与指标证据，以及所选 CI 的已有巡检历史。默认选择 `ops-demo-notification-service / DEMO`，可用 `--ci`、`--environment` 指定其他实际 CI。清单确有增减时显式调整 `--expected-ci-count`，不要为掩盖漏节点修改期望值。

NOT_CONFIGURED、UNSUPPORTED 等状态是合法的采集覆盖缺口，并不要求全部 23 个 CI 都 READY。缺少指标的 READY、过期值仍显示有效、跨身份实例、缺少执行证据会失败。脚本记录实际映射实例数，不硬编码 Prometheus target 总数；共享 JVM 的多个 CI 可合法指向同一运行实例。RabbitMQ、Nacos、Qdrant 原生指标的缺失项会单独列出。

## G1：一次手动检查和两个真实自动周期

```powershell
& $v3Python scripts/observability/v3-monitoring-acceptance.py --run-manual --await-scheduled --max-wait-seconds 3600 --output data/public-deploy/v3/g1-manual-and-scheduled.json
```

此命令仅显式请求一次手动检查，然后根据 API 的真实 `intervalMs`、`nextRunAt` 等待两个新出现的 SCHEDULED 记录。接受条件包括：两个不同 runId、相邻 scheduledFor 时间槽、前一条 nextRunAt 等于后一条 scheduledFor、均为 COMPLETED、证据与状态一致、关联真实 HEALTH_CHECK 工作流且执行者是 system-health。间隔变化、计划关闭、超时或工作流关联缺失不能通过。

默认等待上限 3600 秒；真实间隔较长时，操作者可增加 `--max-wait-seconds`。每次睡眠不超过 30 秒，期间输出安全进度并保存 WAITING 报告。脚本不会加速调度、改计划时间、伪造记录或补跑时间槽。没有 `--await-scheduled` 的检查只核对已有历史，报告标注 EXISTING_HISTORY_ONLY，不能充当本次新自动周期的证明。

COMPLETED + UNKNOWN 是“执行完成、结论未知”，不会转换成健康 PASS。手动检查忙碌或失败、自动检查未凑齐时返回 BLOCKED/NOT_RUN。调用手动接口后网络异常也不会重试，先用只读历史确认是否已落库。

## E2E-B：由操作者注入采集网络故障

故障注入和恢复均由 root 单独实施：只断开 `demo-rabbitmq` 与 `demo-metrics`，保留 `demo-data`；恢复时重新连接 `demo-metrics` 并保留 `demo-rabbitmq` 网络别名。脚本不包含 Docker 操作。

完成故障注入并等待真实 Prometheus 抓取周期后运行：

```powershell
& $v3Python scripts/observability/v3-monitoring-acceptance.py --phase check-fault --output data/public-deploy/v3/e2e-b-fault.json
```

检查 DEMO RabbitMQ observation=FAILED、真实新鲜 up=0 和实例抓取错误；支持 SCRAPE_FAILED、TARGET_CONNECTION_REFUSED、TARGET_TIMEOUT 等服务端实际诊断。并读取真实 notification 业务预览，核对 HTTP 200、业务探针为 1 且新鲜、业务健康不是红色，正式 PROD RabbitMQ 仍为 READY/up=1，采集故障不能将 Demo RabbitMQ 标红为业务宕机。读取业务目标接口只做真实预览，并会由服务端刷新只读探针证据。

完成外部网络恢复、等待新鲜 up=1 后运行：

```powershell
& $v3Python scripts/observability/v3-monitoring-acceptance.py --phase check-recovery --prior-report data/public-deploy/v3/e2e-b-fault.json --output data/public-deploy/v3/e2e-b-recovery.json
```

恢复要求同一站点先前 PASS 的故障报告，并且恢复样本时间晚于该故障报告完成时间；没有先前故障证据不能把一个普通健康快照称为“恢复”。每个阶段若采样尚未更新，应等待真实抓取后重新运行该只读阶段，不修改指标或状态。报告只证明 API 证据，Docker 网络拓扑隔离本身由操作者另行记录。

## 报告与退出码

报告只能写到本项目已被 Git 忽略的 `data/` 下。输出包含安全身份、实例指纹、状态、指标值/时间、runId/checkId/workflowRunId 和证据引用；不保存原始 URL、凭证、原始响应、抓取错误正文或业务配置。退出码：0=PASS，1=FAIL，2=BLOCKED，3=NOT_RUN。状态优先级 FAIL > BLOCKED > NOT_RUN > PASS；WAITING 是未完成的中间报告。

本次交付仅做脚本语法验证。云端 baseline、手动巡检、两个自动周期、故障和恢复阶段均为 **NOT_RUN**，需要部署完成后按上述命令取得真实证据。

## 新增巡检表自动初始化

已核对 `ObservabilitySchemaInitializer`：它是 ApplicationRunner，默认 `ops.operations.initialize-schema=true`，启动时运行 UTF-8 的 `observability-schema.sql`。该脚本包含 `observability_inspection_plan`、`observability_inspection_execution` 的 `CREATE TABLE IF NOT EXISTS`，因此默认配置下由服务启动幂等创建，无须手工执行 SQL，旧历史表保留。若发布环境明确关闭该 initializer，应通过正式发布迁移建立这两张表，不能假定已经存在。
