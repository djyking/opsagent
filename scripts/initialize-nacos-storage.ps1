$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$volumeName = "opsagent_nacos-data"

function Invoke-DockerChecked {
    param([string[]]$DockerArguments)
    $result = & docker @DockerArguments
    if ($LASTEXITCODE -ne 0) { throw "Docker operation failed: $($DockerArguments[0])" }
    return $result
}

# Existing installations kept Derby/configuration data in the container writable layer.
# Take a stopped, consistent copy before Compose replaces that container with a volume mount.
$containerIds = @(Invoke-DockerChecked @("ps", "-aq", "--filter", "label=com.docker.compose.project=opsagent", "--filter", "label=com.docker.compose.service=nacos"))
if ($containerIds.Count -eq 0) { return }
if ($containerIds.Count -ne 1) { throw "Expected one Nacos container; inspect the existing deployment before migration." }
$containerId = $containerIds[0]
$container = (Invoke-DockerChecked @("inspect", $containerId) | ConvertFrom-Json)[0]
if (@($container.Mounts | Where-Object { $_.Destination -eq "/home/nacos/data" }).Count -gt 0) { return }

# Resume after a successful copy if Compose was interrupted before recreating the container.
$migrationDirectory = Join-Path $projectRoot "data/nacos-migration"
if (-not $container.State.Running -and (Test-Path -LiteralPath $migrationDirectory)) {
    foreach ($markerFile in (Get-ChildItem -LiteralPath $migrationDirectory -Filter migration.json -Recurse -File)) {
        $marker = Get-Content -LiteralPath $markerFile.FullName -Raw | ConvertFrom-Json
        if ($marker.originalContainer -eq $containerId -and $marker.volume -eq $volumeName) {
            Invoke-DockerChecked @("volume", "inspect", "--format", "{{.Name}}", $volumeName) | Out-Null
            return
        }
    }
}

$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$backupRoot = Join-Path $projectRoot "data/nacos-migration/$stamp"
$backupData = Join-Path $backupRoot "data"
New-Item -ItemType Directory -Path $backupData -Force | Out-Null
$helperName = "opsagent-nacos-migration-$stamp"
$wasRunning = $container.State.Running
try {
    Write-Host "Preserving Nacos data before adding persistent storage..."
    if ($wasRunning) { Invoke-DockerChecked @("stop", "--time", "60", $containerId) | Out-Null }
    Invoke-DockerChecked @("cp", "${containerId}:/home/nacos/data/.", $backupData) | Out-Null
    if (-not (Test-Path -LiteralPath (Join-Path $backupData "derby-data"))) {
        throw "Nacos Derby backup is missing; the old container is preserved."
    }
    Invoke-DockerChecked @("volume", "create", "--label", "com.docker.compose.project=opsagent", "--label", "com.docker.compose.volume=nacos-data", $volumeName) | Out-Null
    Invoke-DockerChecked @("run", "-d", "--name", $helperName, "--network", "none", "--memory", "64m", "--cpus", "0.25", "--mount", "type=volume,source=$volumeName,target=/persist", "--entrypoint", "sh", $container.Image, "-c", "sleep 600") | Out-Null
    # Never overwrite an existing volume containing data from another installation or attempt.
    Invoke-DockerChecked @("exec", $helperName, "sh", "-c", 'test -z "$(ls -A /persist)"') | Out-Null
    Invoke-DockerChecked @("cp", "$backupData/.", "${helperName}:/persist") | Out-Null
    Invoke-DockerChecked @("exec", $helperName, "sh", "-c", "test -d /persist/derby-data && test -d /persist/tenant-config-data") | Out-Null
    @{ completedAt = (Get-Date).ToString("o"); originalContainer = $containerId; volume = $volumeName } |
        ConvertTo-Json | Set-Content -LiteralPath (Join-Path $backupRoot "migration.json")
    Write-Host "Nacos data preserved. Backup: $backupRoot"
} catch {
    if ($wasRunning) { & docker start $containerId | Out-Null }
    throw
} finally {
    & docker rm -f $helperName 2>$null | Out-Null
}
