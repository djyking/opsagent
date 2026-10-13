param(
    [string]$BaseUrl = "http://127.0.0.1:5173",
    [string]$Token = "",
    [string]$AdminPassword = "",
    [string]$CaptchaId = "",
    [string]$CaptchaCode = ""
)

$ErrorActionPreference = "Stop"
# Fail before generating files or running the destructive demo initializer.
# Existing authorized sessions take precedence; captcha login is explicit.
if ([string]::IsNullOrWhiteSpace($Token)) {
    if ([string]::IsNullOrWhiteSpace($AdminPassword) -or
        [string]::IsNullOrWhiteSpace($CaptchaId) -or
        [string]::IsNullOrWhiteSpace($CaptchaCode)) {
        throw "Provide -Token from an existing admin session, or -AdminPassword, -CaptchaId and -CaptchaCode. No initialization was performed."
    }
    $login = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/auth/login" -ContentType "application/json" `
        -Body (@{ username="admin"; password=$AdminPassword; captchaId=$CaptchaId; captchaCode=$CaptchaCode } | ConvertTo-Json)
    $Token = $login.data.accessToken
    if (-not $Token) { throw "Admin login did not return an access token. No initialization was performed." }
}
$headers = @{ Authorization="Bearer $Token" }
$currentUser = Invoke-RestMethod -Method Get -Uri "$BaseUrl/api/auth/me" -Headers $headers
if (-not $currentUser.data -or @($currentUser.data.roles | Where-Object { $_ -in @("ADMIN", "ROLE_ADMIN") }).Count -eq 0) {
    throw "A valid admin session is required. No initialization was performed."
}

$projectRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$dockerBin = "C:\Users\77190\AppData\Local\Programs\DockerDesktop\resources\bin"
$docker = Join-Path $dockerBin "docker.exe"
$env:Path = "$dockerBin;$env:Path"
& (Join-Path $PSScriptRoot "New-DemoData.ps1")

$container = (& $docker compose -f (Join-Path $projectRoot "compose.yaml") ps -q mysql).Trim()
if (-not $container) { throw "Docker MySQL is not running." }
$OutputEncoding = [System.Text.UTF8Encoding]::new($false)
Get-Content -LiteralPath (Join-Path $projectRoot "sql\07_enterprise_demo_data.sql") -Raw -Encoding utf8 |
    & $docker exec -i $container sh -c 'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" --default-character-set=utf8mb4'
if ($LASTEXITCODE -ne 0) { throw "Enterprise SQL initialization failed." }

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
