# OpsAgent 企业演示数据

本目录为“OpsAgent 演示环境”虚构生产运维场景提供可重复生成的数据文件，不包含真实公司、地址、令牌或客户信息。

- `generated/knowledge-docs`：18 篇真实上传并由 Tika 解析的 Markdown Runbook 与复盘文档。
- `generated/attachments`：5 个日志/指标附件和一个故意损坏的 `broken-demo.pdf`。
- `scripts/New-DemoData.ps1`：重新生成文件。
- `scripts/Initialize-EnterpriseDemo.ps1`：执行企业 SQL，通过 HTTP 上传文档并把解析任务发布到 RabbitMQ。

脚本会重建约定的 2000～2999 演示工单区间，仅用于明确需要重新初始化演示数据的场景。已有环境的文案或配置修复不应重新执行它。

启动系统后，在 PowerShell 7 中使用已登录管理员的有效访问令牌执行（令牌不写入仓库）：

```powershell
pwsh -File .\demo-data\scripts\Initialize-EnterpriseDemo.ps1 -Token $env:OPSAGENT_ADMIN_TOKEN
```

也可以显式传入 `-AdminPassword`、`-CaptchaId`、`-CaptchaCode` 完成验证码登录。优先使用 `-Token`；脚本会在生成文件和执行 SQL 前验证管理员会话，缺失或无效的凭据不会初始化数据。

企业演示账号和本地密码见仓库外的 `D:\middleware\docs\OpsAgent本地地址与密码.md`，本文件不保存明文密码。`broken-demo.pdf` 专门验证三次重试、失败任务和 `ops.knowledge.parse.dlq`，不是有效 PDF。
