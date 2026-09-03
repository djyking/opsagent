param([string]$BaseUrl = "http://127.0.0.1:8080",[string]$AdminPassword = "Admin@123")

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$dockerBin = "C:\Users\77190\AppData\Local\Programs\DockerDesktop\resources\bin"
$docker = Join-Path $dockerBin "docker.exe"
$env:Path = "$dockerBin;$env:Path"
& (Join-Path $PSScriptRoot "New-DemoData.ps1")

$container = (& $docker compose -f (Join-Path $projectRoot "compose.yaml") ps -q mysql).Trim()
if (-not $container) { throw "Docker MySQL is not running." }
Get-Content -LiteralPath (Join-Path $projectRoot "sql\07_enterprise_demo_data.sql") -Raw |
    & $docker exec -i $container mysql -uroot -p2491125
if ($LASTEXITCODE -ne 0) { throw "Enterprise SQL initialization failed." }

$login = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/auth/login" -ContentType "application/json" `
    -Body (@{ username="admin"; password=$AdminPassword } | ConvertTo-Json)
$token = $login.data.accessToken
if (-not $token) { throw "Admin login did not return an access token." }
$headers = @{ Authorization="Bearer $token" }
$docs = Join-Path (Split-Path $PSScriptRoot -Parent) "generated\knowledge-docs"
foreach ($file in Get-ChildItem -LiteralPath $docs -Filter "*.md" | Sort-Object Name) {
    $baseId = [int]$file.BaseName.Substring(0,3)
    $existing = Invoke-RestMethod -Method Get -Uri "$BaseUrl/api/knowledge/bases/$baseId/documents" -Headers $headers
    $names = @($existing.data | ForEach-Object { $_.original_name })
    if ($file.Name -in $names) { Write-Host "Skip existing $($file.Name)"; continue }
    $uploaded = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/knowledge/bases/$baseId/documents" -Headers $headers -Form @{ file=$file }
    $task = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/knowledge/documents/$($uploaded.data)/parse" -Headers $headers
    Write-Host "Queued $($file.Name): document=$($uploaded.data) task=$($task.data)"
}
$broken = Get-Item -LiteralPath (Join-Path (Split-Path $docs -Parent) "attachments\broken-demo.pdf")
$existing = Invoke-RestMethod -Method Get -Uri "$BaseUrl/api/knowledge/bases/204/documents" -Headers $headers
if ($broken.Name -notin @($existing.data | ForEach-Object { $_.original_name })) {
    $uploaded = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/knowledge/bases/204/documents" -Headers $headers -Form @{ file=$broken }
    $task = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/knowledge/documents/$($uploaded.data)/parse" -Headers $headers
    Write-Host "Queued intentional DLQ sample: document=$($uploaded.data) task=$($task.data)"
}
Write-Host "Enterprise demo initialized; parsing continues asynchronously."
