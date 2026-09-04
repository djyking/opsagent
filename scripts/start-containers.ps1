param([switch]$Build, [switch]$WithReranker)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot

Push-Location $projectRoot
try {
    $profiles = @("--profile", "apps")
    if ($WithReranker) {
        $profiles += @("--profile", "rerank")
    }
    $arguments = @("compose") + $profiles + @("up", "-d")
    if ($Build) {
        $arguments += "--build"
    }
    & docker @arguments
    if ($LASTEXITCODE -ne 0) {
        throw "OpsAgent 容器启动失败，请执行 docker compose logs 查看原因。"
    }
    & docker compose @profiles ps
} finally {
    Pop-Location
}
