# OpsAgent V3 验收报告

本文件随运行验证更新；当前为部署前记录，不能据此宣称 V3 已发布。执行入口与总体规格来自本轮 V3 文档。基线见 `current-state-audit.md`。

## 已执行

| 项目 | 环境 / 命令 | 结果 | 证据 |
| --- | --- | --- | --- |
| 前端全部现有脚本 | 本地 Node，逐一运行 `ops-web/tests/*.test.mjs`，17组 | PASS | `data/public-deploy/v3/frontend-all-summary.log`、`frontend-all-tests.log` |
| Agent 回归 | Java17 wrapper / Central settings；Agent模块及依赖 test | PASS，146项中1项既有外网测试跳过 | `data/public-deploy/v3/root-agent-regression.log` |
| 配置→审批桥接 | 6项真实H2持久运行测试，包含待应用、恢复对账、审批漂移 | PASS | `root-configuration-agent-tests.log` |
| 监控/巡检/冻结证据定向 | 51项平台测试 | PASS（最终全量结果待汇总） | 分任务日志 `data/public-deploy/v3` |
| RAG 证据相关 | 59项定向测试 | PASS（最终全量结果待汇总） | 分任务日志 `data/public-deploy/v3` |
| Collector配置 | 官方0.160.0容器 `validate` | PASS | `collector-config-validation.log` |
| Tempo配置 | 官方2.10.8容器 `-config.verify=true` | PASS | `tempo-config-validation-final.log` |
| 云端变更前 | 23容器，自动化 activeRuns=0、pendingApprovals=0；订单BASELINE | PASS | `cloud-preflight-current.log`、当前预检输出 |

## 配置专项：CFG-01—CFG-12 与 E2E-C

更新至 **2026-09-06 14:52 UTC（北京时间22:52）**。V3 r3与Trace启动后，在公网环境完成单实例配置闭环，实际业务字段已恢复。以下区分线上运行、本地测试与尚未覆盖的分支；不能将单实例成功视为配置全部门禁通过。

| 编号 | 已执行与结果 | 尚未在线覆盖 / 限制 |
| --- | --- | --- |
| CFG-01 | **线上PASS**：订单business/runtime两个不同Nacos源，完整身份、实际正文版本、目标归属一致，revision不同；业务源revision与实际应用一致。直接Nacos源hash与API读取一致 | 六个应用YAML实际同为连接标记片段，并非完整应用配置；未将它们误报为同一来源 |
| CFG-02 | **本地PASS**：配置两个服务共享同一完整来源，目标范围排序去重、共享标识明确、共享业务源不可发布 | 线上真实多服务共享源场景 **NOT_RUN** |
| CFG-03 | **前端composable测试PASS**：人为延迟A，再选B，A迟到不替换B；旧diff、账号切换及返回身份不符均受保护 | 浏览器真实网络延迟注入 **NOT_RUN** |
| CFG-04 | **本地PASS**：分别测试NOT_FOUND/FORBIDDEN/UPSTREAM_UNAVAILABLE/UNSUPPORTED/INVALID_CONTENT，无默认正文；**线上PASS**：非Nacos未接入源为UNSUPPORTED。只读直连核实Nacos缺失源实际HTTP404/code20004 | 线上403、真实超时、损坏内容注入 **NOT_RUN** |
| CFG-05 | **线上PASS**：白名单折扣0→1，未修改的业务标题/提示保持不变，最终回退；**本地PASS**：密钥脱敏、未修改秘密/额外字段保留、掩码拒绝写回，省略/空字符串/null/删除语义 | 线上向源加入秘密字段验证保留 **NOT_RUN**，没有为测试增添真实密钥 |
| CFG-06 | **本地PASS**：未知字段、危险路径、错误类型、越界值、掩码/null/删除在写入前拒绝 | 线上危险字段与无效结构提交 **NOT_RUN** |
| CFG-07 | **线上部分PASS**：同账号基于同一旧版本保存两提案；先批准发布新值，再精确批准旧提案，后端拒绝冲突，源版本及历史条数不变；本地覆盖版本冲突和owner隔离 | 严格两个用户同时提交的在线场景 **NOT_RUN** |
| CFG-08 | **本地PASS**：提案摘要漂移、owner/run绑定、目标实例/源命名空间变化、审批后内容变化不能沿旧审批执行 | 线上批准后逐项篡改目标、字段、版本、环境 **NOT_RUN** |
| CFG-09 | **线上单实例PASS**：真实源历史版本4为PUBLISHED时，目标仍使用旧应用版本与旧报价，Agent为NEEDS_ATTENTION；解除应用暂停后原运行对账为APPLIED | 多实例全部应用 **BLOCKED：仅一个纳管隔离订单JVM**；没有用伪造实例补齐 |
| CFG-10 | **本地PASS**：模拟发布响应超时后UNCONFIRMED，读取实际源与业务恢复为APPLIED；重复请求不再发布。**线上PASS**：PUBLISHED分支恢复原run/提案/审批，历史版本ID和条数不变 | 上游已写而HTTP响应丢失的线上网络故障 **NOT_RUN**；未将应用暂停冒充响应丢失 |
| CFG-11 | **线上PASS**：新回退提案与独立审批，把原历史版本3的内容作为新版本5发布；真实业务恢复折扣0/报价100，版本记录保留 | 独立第三方在回退审批期间再次改源的在线场景 **NOT_RUN**；普通旧版本CAS拒绝已有本地及线上证据 |
| CFG-12 | **本地PASS**：DEMO/OPS无写权限，原公共直接发布/回退对ADMIN也关闭，未纳管路径拒绝；固定内部暂停端点拒绝普通JWT。**线上PASS**：未接入源无读写能力、runtime源只读 | 线上非管理员直接调用发布/提案API **NOT_RUN** |

E2E-C在线执行时间为14:46:05—14:49:56 UTC。固定实例为 `77dcdf06-2475-4842-934f-73c53b3e92c4`，来源为Nacos `public / OPSAGENT_DEMO / ops-demo-order-business.json`，`sourceInstanceId=nacos-222f5a1ba249617a`。三个运行均不调用模型、tokens=0、不创建虚假工单；配置写入均经不可变提案与原Agent精确审批。

- 主运行 `78676357-343e-499e-a4c4-a7539867bce7`：PUBLISHED/旧业务 → NEEDS_ATTENTION → 原审批与原意图对账 → COMPLETED/APPLIED，历史版本仍为4、历史总数仍为4。
- 旧版负向运行 `04f3d3b2-3203-4ed1-b078-e1c8e1ef71da`：旧基础版本经审批后被拒绝，源和历史不变，保留NEEDS_ATTENTION冲突证据。
- 回退运行 `070da3aa-7ad0-4768-8f2a-18a5b300eec4`：独立审批后COMPLETED/APPLIED，产生版本5，原标题、提示及折扣0恢复，实际HTTP200/报价100。

应用暂停仅通过既有内部token调用固定隔离订单端点，绑定pauseId、实例、基础版本与最多120秒的绝对期限。本次在线暂停约63秒后手动恢复，真实重新读取Nacos源；TTL自动恢复及读取失败重试由本地测试覆盖，**线上TTL自动到期分支NOT_RUN**。最终暂停为inactive，实例未变化，源/应用/业务版本一致，没有本次验收待审批。

**清理结果**：14:52:39 UTC通过正常API对上述旧版运行发起取消。API接受请求，但既有取消实现不转换NEEDS_ATTENTION，因此状态保持不变；该状态不计活动运行额度、没有待审批，也不会自动执行。未删除审计记录、未直接更新数据库、未把API成功误报为CANCELLED。之后再次确认原业务基线与暂停恢复均正常。

证据均位于Git忽略目录 `data/public-deploy/v3/`：

- `configuration-read-acceptance.json`、`nacos-source-identity-audit.json`：真实来源只读核对。
- `configuration-tests.log`、`configuration-pause-tests.log`、`configuration-frontend-tests.log`、`root-configuration-agent-tests.log`：本地单测与前端行为测试。
- `configuration-e2e-c-report.md`：在线过程与限制说明。
- `configuration-smoke-2026-09-06T14-46-05-682Z-cb9e7048/2026-09-06T14-47-38-571Z-deferred-published-38372395.json`：源已发布但应用/业务仍旧。
- 同目录 `2026-09-06T14-48-39-385Z-main-applied-0fcedbd5.json`、`2026-09-06T14-49-15-033Z-stale-conflict-9662d255.json`：同意图对账与旧版本拒绝。
- 同目录 `2026-09-06T14-49-56-852Z-final-ff5a0913.json`、`2026-09-06T14-52-39-336Z-stale-cleanup.json`：完整恢复及取消请求的实际结果。

## 等待对应构建运行的门禁

全量 `clean verify`、最终前端 build、BASE-01 版本映射、G1 新采样与巡检、配置真实审批发布/回滚、Trace/消息/实例/历史/差异、E2E-A/B/C、知识审核检索、浏览器导航和真实 G6 交互、资源实测：当前均 NOT_RUN，后续必须按实际输出更新。

多副本应用分支：当前仅一个隔离业务 JVM，不伪造第二实例；单实例未应用分支仍需实际执行。Pod 专属检查因 Docker Compose 环境不适用。

构建通过不替代页面、来源、失败分支或恢复验证。用户会话和原始敏感数据保存在已忽略的证据目录中，不加入Git。
