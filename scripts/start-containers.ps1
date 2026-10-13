param([switch]$Build, [switch]$WithReranker, [switch]$StandardResources)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot

Push-Location $projectRoot
try {
    & (Join-Path $PSScriptRoot "initialize-nacos-storage.ps1")
    $composeFiles = @("-f", "compose.yaml")
    if (-not $StandardResources) {
        $composeFiles += @("-f", "compose.demo.yaml")
    }
    $profiles = @("--profile", "apps")
    if ($WithReranker) {
        $profiles += @("--profile", "rerank")
    }
    $arguments = @("compose") + $composeFiles + $profiles + @("up", "-d")
    if ($Build) {
        $arguments += "--build"
    }
    & docker @arguments
    if ($LASTEXITCODE -ne 0) {
        throw "OpsAgent 容器启动失败，请执行 docker compose logs 查看原因。"
    }
    & docker compose @composeFiles @profiles ps
} finally {
    Pop-Location
}
