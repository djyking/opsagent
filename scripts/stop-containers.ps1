$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot

Push-Location $projectRoot
try {
    docker compose --profile apps --profile rerank down
    if ($LASTEXITCODE -ne 0) {
        throw "OpsAgent 容器停止失败。"
    }
} finally {
    Pop-Location
}
