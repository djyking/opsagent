param([string]$BaseUrl = "http://127.0.0.1:18080")

$ErrorActionPreference = "Stop"
if ([string]::IsNullOrWhiteSpace($env:OPS_ALERTMANAGER_WEBHOOK_TOKEN)) {
    throw "请先设置 OPS_ALERTMANAGER_WEBHOOK_TOKEN；Token 不得写入仓库脚本。"
}
$headers = @{ Authorization = "Bearer $env:OPS_ALERTMANAGER_WEBHOOK_TOKEN" }
$demoRoot = Join-Path (Split-Path $PSScriptRoot) "alertmanager"
foreach ($file in "order-service-high-latency-firing.json", "order-service-high-latency-duplicate.json") {
    Invoke-RestMethod -Method Post -Headers $headers -ContentType "application/json" `
        -InFile (Join-Path $demoRoot $file) `
        -Uri "$BaseUrl/api/integrations/alertmanager/webhook"
}
