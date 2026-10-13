# 隔离运维实验服务

此服务只改变自身 `/health` HTTP 状态，正常为 200，故障为 503。平台工作流真实执行
基线检查 → 注入 → 读取故障 → 恢复 → 再次验证，并在 `ops_platform` 保存运行、步骤和审计记录。
不挂载 Docker socket，不执行命令，不修改任何业务服务；没有宿主机端口映射。

将 `compose.snippet.yaml` 内容合并到 `deploy/public/compose.yaml` 后构建启动。
构建上下文相对于该公开部署目录。由部署方生成至少32字符的随机 `OPS_OPERATIONS_LAB_TOKEN`，
只保存在忽略的服务器环境文件；同一个值提供给实验容器和平台。不要写入Git或浏览器。

- `GET /health`：返回固定白名单状态，故障会在45秒后自动过期。
- `POST /fault`、`POST /recover`：必须携带 `X-Lab-Token`；只有上述两种固定动作。
- 实验服务声明 `scope=ISOLATED_LAB`，平台拒绝没有该标记的响应。
- 平台执行期间异常会尝试恢复；即使平台退出，实验故障仍有45秒TTL。
- 默认单实例平台一次只执行一个工作流。多平台实例部署需另行加入共享执行租约。

平台新版本默认通过 `operations-schema.sql` 幂等创建3张独立表，不修改已有业务表。
数据库账号若没有建表权限，先由管理员在 `ops_platform` 执行
`ops-platform-service/src/main/resources/operations-schema.sql`，再设置
`OPS_OPERATIONS_INITIALIZE_SCHEMA=false`。不会删除已有运行历史。

新增平台环境变量：

| 变量 | 用途 |
| --- | --- |
| `OPS_OPERATIONS_RAG_URL` | 固定内部RAG地址，用登录Bearer只读查询真实Sentinel规则和计数 |
| `OPS_OPERATIONS_NACOS_URL` | 固定Nacos内部基础地址，包含 `/nacos` |
| `OPS_OPERATIONS_NACOS_USERNAME/PASSWORD` | 可选Nacos只读账号，仅服务端使用 |
| `OPS_OPERATIONS_NACOS_IDENTITY_KEY/VALUE` | Nacos3管理接口的现有服务端身份，只提供给平台服务 |
| `OPS_OPERATIONS_NACOS_NAMESPACE` | Nacos namespace ID，默认public空值 |
| `OPS_OPERATIONS_LAB_URL/TOKEN` | 固定实验地址及控制令牌 |
| `OPS_OPERATIONS_INSPECTION_ENABLED` | 定时只读巡检开关，默认false |
| `OPS_OPERATIONS_INSPECTION_INTERVAL_MS` | 完成后延迟多久再巡检，默认900000（15分钟），最短1分钟 |
| `OPS_OPERATIONS_INSPECTION_INITIAL_DELAY_MS` | 启动后首次巡检延迟，默认120000（2分钟） |

定时巡检仅执行 `HEALTH_CHECK`，操作者记为 `system-health`；不调用付费AI，不自动注入故障。
与手动工作流共享单实例执行锁，忙碌时跳过本次调度，不积压队列。页面返回配置频率与最近运行时间。

运维趋势仅使用 Prometheus 的 JVM 堆和 HTTP 5xx 时序，不代表宿主机磁盘或Docker统计。
最小二乘趋势外推15分钟，返回样本数、最近采样时间、方法与不足原因；无数据时返回未知。
Nacos只对外投影注册数量和配置名称/分组；Sentinel来源为RAG运行时已加载规则。

Nacos3已经移除旧`/v1/ns/catalog/services`及`/v1/cs/configs`管理接口。
平台使用`/v3/admin/ns/service/list`和`/v3/admin/cs/config/list`，读取`data.pageItems`元数据；
配置列表只包含名称、分组和时间等元信息，不请求配置正文。空namespace按`public`读取。
公共部署复用已经配置的Nacos server identity，通过固定内部HTTP请求头鉴权，
不会通过UI/RAG响应或日志返回该身份；不需要关闭Nacos管理鉴权或暴露新的宿主机端口。
