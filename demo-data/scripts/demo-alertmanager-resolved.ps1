param([string]$BaseUrl = "http://127.0.0.1:18080")

$ErrorActionPreference = "Stop"
if ([string]::IsNullOrWhiteSpace($env:OPS_ALERTMANAGER_WEBHOOK_TOKEN)) {
    throw "请先设置 OPS_ALERTMANAGER_WEBHOOK_TOKEN；Token 不得写入仓库脚本。"
}
$headers = @{ Authorization = "Bearer $env:OPS_ALERTMANAGER_WEBHOOK_TOKEN" }
$payload = Join-Path (Split-Path $PSScriptRoot) `
    "alertmanager\order-service-high-latency-resolved.json"
Invoke-RestMethod -Method Post -Headers $headers -ContentType "application/json" `
    -InFile $payload -Uri "$BaseUrl/api/integrations/alertmanager/webhook"
