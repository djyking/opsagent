# OpsAgent 企业演示数据

本目录为“星云商城（Nebula Mall）”虚构生产运维场景提供可重复生成的数据文件，不包含真实公司、地址、令牌或客户信息。

- `generated/knowledge-docs`：18 篇真实上传并由 Tika 解析的 Markdown Runbook 与复盘文档。
- `generated/attachments`：5 个日志/指标附件和一个故意损坏的 `broken-demo.pdf`。
- `scripts/New-DemoData.ps1`：重新生成文件。
- `scripts/Initialize-EnterpriseDemo.ps1`：执行企业 SQL，通过 HTTP 上传文档并把解析任务发布到 RabbitMQ。

启动中间件和本机 Java 服务后执行：

```powershell
powershell -ExecutionPolicy Bypass -File .\demo-data\scripts\Initialize-EnterpriseDemo.ps1
```

新增企业账号统一使用本地演示密码 `OpsAgent@123`，管理员仍使用 `admin / Admin@123`。这些密码仅用于本机。`broken-demo.pdf` 专门验证三次重试、失败任务和 `ops.knowledge.parse.dlq`，不是有效 PDF。
