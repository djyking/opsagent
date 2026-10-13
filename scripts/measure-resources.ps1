#requires -Version 7.3
<#
.SYNOPSIS
    Read-only Docker/JVM resource sampling for the OpsAgent demo stack.
.EXAMPLE
    .\scripts\measure-resources.ps1 -Samples 6 -IntervalSeconds 10
.NOTES
    Stores selected resource metrics only; never reads environment variables,
    process command lines, credentials, application data, or forces a GC.
    A last sample is not automatically proof of steady state. Warm up the same
    workload before comparing runs; observed peaks are limited to sample times.
#>
[CmdletBinding()]
param(
    [ValidateRange(1, 10000)]
    [int] $Samples = 1,

    [ValidateRange(1, 3600)]
    [int] $IntervalSeconds = 10,

    [string] $OutputDirectory = (
        Join-Path (Split-Path $PSScriptRoot -Parent) (
            'data/resource-audit/' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff')
        )
    )
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$culture = [System.Globalization.CultureInfo]::InvariantCulture

function Invoke-DockerResourceCommand {
    param([string[]] $DockerArguments)
    $result = @(& docker @DockerArguments 2>$null)
    if ($LASTEXITCODE -ne 0) {
        throw "Docker resource inspection failed (exit code $LASTEXITCODE)."
    }
    return $result
}

function ConvertTo-ByteCount {
    param([string] $Text)
    if ($Text -notmatch '^\s*([0-9]+(?:\.[0-9]+)?)\s*([a-zA-Z]+)\s*$') {
        throw "Unrecognized Docker memory unit: $Text"
    }
    $amount = [double]::Parse($Matches[1], $culture)
    $units = @{
        B = 1.0; kB = 1000.0; MB = 1000000.0; GB = 1000000000.0; TB = 1000000000000.0
        KiB = 1024.0; MiB = 1048576.0; GiB = 1073741824.0; TiB = 1099511627776.0
    }
    $unit = $Matches[2]
    if (-not $units.ContainsKey($unit)) { throw "Unsupported Docker memory unit: $unit" }
    return [long][math]::Round($amount * $units[$unit])
}

function Get-MetricTotal {
    param([object[]] $Metrics, [string] $Name, [string] $LabelPattern = '')
    $values = @($Metrics | Where-Object {
        $_.Name -eq $Name -and $_.Labels -match $LabelPattern -and $_.Value -ge 0
    })
    if ($values.Count -eq 0) { return $null }
    return [double](($values | Measure-Object -Property Value -Sum).Sum)
}

function Get-ApplicationResources {
    param([string] $ContainerId, [string] $Service, [int] $Port)
    $lines = Invoke-DockerResourceCommand -DockerArguments @(
        'exec', $ContainerId, 'curl', '-fsS', '--max-time', '5',
        "http://127.0.0.1:$Port/actuator/prometheus"
    )
    $metrics = @(
        foreach ($line in $lines) {
            if ($line -match '^(jvm_memory_(?:used|committed|max)_bytes|jvm_threads_(?:live|daemon|peak)_threads|hikaricp_connections_(?:min|max|active|idle|pending))(?:\{([^}]*)\})?\s+(\S+)') {
                $name, $labels, $valueText = $Matches[1], $Matches[2], $Matches[3]
                $value = 0.0
                if ([double]::TryParse($valueText, [System.Globalization.NumberStyles]::Float, $culture, [ref]$value)) {
                    [pscustomobject]@{ Name = $name; Labels = $labels; Value = $value }
                }
            }
        }
    )
    $hikariMetrics = @($metrics | Where-Object { $_.Name -like 'hikaricp_connections_*' })
    $pools = @(
        foreach ($group in ($hikariMetrics | Group-Object -Property Labels)) {
            $poolName = $group.Name
            if ($group.Name -match '(?:^|,)pool="([^"]+)"') { $poolName = $Matches[1] }
            [pscustomobject]@{
                pool = $poolName
                minimum = Get-MetricTotal $group.Group 'hikaricp_connections_min'
                maximum = Get-MetricTotal $group.Group 'hikaricp_connections_max'
                active = Get-MetricTotal $group.Group 'hikaricp_connections_active'
                idle = Get-MetricTotal $group.Group 'hikaricp_connections_idle'
                pending = Get-MetricTotal $group.Group 'hikaricp_connections_pending'
            }
        }
    )
    return [pscustomobject]@{
        service = $Service
        containerId = $ContainerId
        capturedAtUtc = [DateTime]::UtcNow.ToString('o')
        heapUsedBytes = Get-MetricTotal $metrics 'jvm_memory_used_bytes' '(?:^|,)area="heap"(?:,|$)'
        heapCommittedBytes = Get-MetricTotal $metrics 'jvm_memory_committed_bytes' '(?:^|,)area="heap"(?:,|$)'
        heapMaxBytes = Get-MetricTotal $metrics 'jvm_memory_max_bytes' '(?:^|,)area="heap"(?:,|$)'
        nonHeapUsedBytes = Get-MetricTotal $metrics 'jvm_memory_used_bytes' '(?:^|,)area="nonheap"(?:,|$)'
        threadsLive = Get-MetricTotal $metrics 'jvm_threads_live_threads'
        threadsDaemon = Get-MetricTotal $metrics 'jvm_threads_daemon_threads'
        threadsProcessPeak = Get-MetricTotal $metrics 'jvm_threads_peak_threads'
        hikariPools = $pools
    }
}

function Get-NacosProcesses {
    param([string] $ContainerId)
    # Nacos starts with a shell in some images. Identify Java by comm, not by PID 1.
    $probe = @'
for p in /proc/[0-9]*; do
  if [ "$(cat "$p/comm" 2>/dev/null)" = java ]; then
    echo PROCESS
    awk '/^(Name|Pid|PPid|VmRSS|VmHWM|Threads):/ {print}' "$p/status"
  fi
done
'@
    $probe = $probe.Replace("`r`n", "`n")
    $output = @(Invoke-DockerResourceCommand -DockerArguments @('exec', $ContainerId, 'sh', '-c', $probe))
    $processes = @(
        foreach ($block in (($output -join "`n") -split '(?m)^PROCESS\s*$')) {
            if ($block -notmatch '(?m)^Pid:\s+(\d+)') { continue }
            $processId = [int]$Matches[1]
            $parentId, $rssBytes, $peakRssBytes, $threads = $null, $null, $null, $null
            if ($block -match '(?m)^PPid:\s+(\d+)') { $parentId = [int]$Matches[1] }
            # Linux /proc labels these values kB, but they are kibibytes.
            if ($block -match '(?m)^VmRSS:\s+(\d+)\s+kB') { $rssBytes = [long]$Matches[1] * 1024 }
            if ($block -match '(?m)^VmHWM:\s+(\d+)\s+kB') { $peakRssBytes = [long]$Matches[1] * 1024 }
            if ($block -match '(?m)^Threads:\s+(\d+)') { $threads = [int]$Matches[1] }
            [pscustomobject]@{
                containerId = $ContainerId
                pid = $processId
                parentPid = $parentId
                isPid1 = ($processId -eq 1)
                rssBytes = $rssBytes
                processPeakRssBytes = $peakRssBytes
                threads = $threads
            }
        }
    )
    if ($processes.Count -eq 0) { throw 'No Nacos Java process was found.' }
    return $processes
}

function Get-CgroupResources {
    param([string] $ContainerId)
    $probe = @'
if [ -r /sys/fs/cgroup/cgroup.controllers ]; then
  echo version 2
  for key in memory.current memory.peak memory.max; do
    if [ -r "/sys/fs/cgroup/$key" ]; then
      printf '%s ' "$key"
      cat "/sys/fs/cgroup/$key"
    fi
  done
  if [ -r /sys/fs/cgroup/memory.stat ]; then
    awk '$1 == "anon" || $1 == "file" || $1 == "inactive_file" || $1 == "active_file" {print}' /sys/fs/cgroup/memory.stat
  fi
fi
'@
    $probe = $probe.Replace("`r`n", "`n")
    $lines = @(Invoke-DockerResourceCommand -DockerArguments @('exec', $ContainerId, 'sh', '-c', $probe))
    $values = @{}
    foreach ($line in $lines) {
        if ($line -match '^(\S+) (\d+)\s*$') { $values[$Matches[1]] = [long]$Matches[2] }
    }
    return [pscustomobject]@{
        capturedAtUtc = [DateTime]::UtcNow.ToString('o')
        version = $values['version']
        memoryCurrentBytes = $values['memory.current']
        memoryLifetimePeakBytes = $values['memory.peak']
        memoryLimitBytes = $values['memory.max']
        anonymousBytes = $values['anon']
        fileBytes = $values['file']
        inactiveFileBytes = $values['inactive_file']
        activeFileBytes = $values['active_file']
    }
}

function Get-ObservedPeak {
    param([object[]] $Rows, [string] $Property)
    $values = @($Rows | Where-Object { $null -ne $_.$Property })
    if ($values.Count -eq 0) { return $null }
    return ($values | Measure-Object -Property $Property -Maximum).Maximum
}

Get-Command docker -ErrorAction Stop | Out-Null
$outputPath = [System.IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Path $outputPath -Force | Out-Null
$applicationPorts = @{
    'ops-auth-app' = 8101; 'ops-ticket-app' = 8102; 'ops-knowledge-app' = 8103
    'ops-rag-app' = 8104; 'ops-platform-app' = 8105; 'ops-gateway-app' = 18080
}
$inspectTemplate = @'
{"id":"{{.Id}}","name":{{json .Name}},"service":{{json (index .Config.Labels "com.docker.compose.service")}},"running":{{.State.Running}},"oomKilled":{{.State.OOMKilled}},"restartCount":{{.RestartCount}},"memoryLimitBytes":{{.HostConfig.Memory}},"nanoCpus":{{.HostConfig.NanoCpus}},"cpuQuota":{{.HostConfig.CpuQuota}},"cpuPeriod":{{.HostConfig.CpuPeriod}},"health":{{with index .State "Health"}}{{json .Status}}{{else}}null{{end}}}
'@
$measurements = [System.Collections.Generic.List[object]]::new()

for ($sampleIndex = 1; $sampleIndex -le $Samples; $sampleIndex++) {
    $sampleStarted = [DateTime]::UtcNow
    $issues = [System.Collections.Generic.List[string]]::new()
    $ids = @(Invoke-DockerResourceCommand -DockerArguments @(
        'ps', '-a', '--filter', 'label=com.docker.compose.project=opsagent', '--format', '{{.ID}}'
    ))
    if ($ids.Count -eq 0) { throw 'No containers with Docker Compose project label opsagent were found.' }
    $inspected = @(
        Invoke-DockerResourceCommand -DockerArguments (@('inspect', '--format', $inspectTemplate) + $ids) |
            ForEach-Object { $_ | ConvertFrom-Json }
    )
    $runningIds = @($inspected | Where-Object running | ForEach-Object id)
    $statsById = @{}
    if ($runningIds.Count -gt 0) {
        $stats = Invoke-DockerResourceCommand -DockerArguments (
            @('stats', '--no-stream', '--no-trunc', '--format', '{{json .}}') + $runningIds
        )
        foreach ($line in $stats) {
            $stat = $line | ConvertFrom-Json
            $statsById[$stat.ID] = $stat
        }
    }
    $containers = @(
        foreach ($item in $inspected) {
            $memoryBytes, $effectiveLimitBytes, $cpuPercent, $taskCount = $null, $null, $null, $null
            $cgroup = $null
            if ($statsById.ContainsKey($item.id)) {
                $stat = $statsById[$item.id]
                $memoryParts = $stat.MemUsage -split '\s*/\s*'
                $memoryBytes = ConvertTo-ByteCount $memoryParts[0]
                $effectiveLimitBytes = ConvertTo-ByteCount $memoryParts[1]
                $cpuPercent = [double]::Parse($stat.CPUPerc.TrimEnd('%'), $culture)
                $taskCount = [long]::Parse($stat.PIDs, $culture)
            } elseif ($item.running) {
                $issues.Add("Docker stats unavailable for $($item.service).")
            }
            if ($item.running) {
                try { $cgroup = Get-CgroupResources $item.id }
                catch { $issues.Add("Cgroup metrics unavailable for $($item.service).") }
            }
            [pscustomobject]@{
                service = $item.service
                name = $item.name.TrimStart('/')
                containerId = $item.id
                running = $item.running
                health = $item.health
                oomKilled = $item.oomKilled
                restartCount = $item.restartCount
                memoryUsageBytes = $memoryBytes
                configuredMemoryLimitBytes = $item.memoryLimitBytes
                effectiveMemoryLimitBytes = $effectiveLimitBytes
                configuredNanoCpus = $item.nanoCpus
                configuredCpuQuota = $item.cpuQuota
                configuredCpuPeriod = $item.cpuPeriod
                cpuPercent = $cpuPercent
                pidsIncludingThreads = $taskCount
                cgroup = $cgroup
            }
        }
    )
    $applications = @(
        foreach ($item in ($inspected | Where-Object { $_.running -and $applicationPorts.ContainsKey($_.service) })) {
            try {
                Get-ApplicationResources $item.id $item.service $applicationPorts[$item.service]
            } catch {
                $issues.Add("JVM metrics unavailable for $($item.service).")
            }
        }
    )
    $nacosProcesses = @(
        foreach ($item in ($inspected | Where-Object { $_.running -and $_.service -eq 'nacos' })) {
            try { Get-NacosProcesses $item.id }
            catch { $issues.Add('Nacos Java process metrics unavailable.') }
        }
    )
    $totalMemory = ($containers | Measure-Object -Property memoryUsageBytes -Sum).Sum
    $cgroupRows = @($containers | Where-Object { $null -ne $_.cgroup } | ForEach-Object cgroup |
        Where-Object { $null -ne $_.memoryCurrentBytes })
    $totalCgroupMemory = $null
    if ($cgroupRows.Count -gt 0) {
        $totalCgroupMemory = [long](($cgroupRows | Measure-Object -Property memoryCurrentBytes -Sum).Sum)
    }
    $sample = [pscustomobject]@{
        number = $sampleIndex
        startedAtUtc = $sampleStarted.ToString('o')
        completedAtUtc = [DateTime]::UtcNow.ToString('o')
        totalObservedMemoryBytes = [long]$totalMemory
        sumDockerWorkingSetBytes = [long]$totalMemory
        sumCgroupCurrentBytes = $totalCgroupMemory
        runningContainers = $runningIds.Count
        dockerStatsAvailableContainers = $statsById.Count
        cgroupCurrentAvailableContainers = $cgroupRows.Count
        containers = $containers
        applications = $applications
        nacosJavaProcesses = $nacosProcesses
        issues = @($issues.ToArray())
    }
    $measurements.Add($sample)
    # Persist each sample immediately so an interrupted longer run remains usable.
    $sample | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath (
        Join-Path $outputPath ('sample-{0:D4}.json' -f $sampleIndex)
    ) -Encoding UTF8
    Write-Host ('Sample {0}/{1}: {2:N3} GiB, {3} containers, {4} JVMs, {5} issues' -f
        $sampleIndex, $Samples, ($totalMemory / 1GB), $containers.Count, $applications.Count, $issues.Count)
    if ($null -ne $totalCgroupMemory) {
        Write-Host ('  Cgroup current (including charged cache): {0:N3} GiB, available for {1}/{2} containers' -f
            ($totalCgroupMemory / 1GB), $cgroupRows.Count, $runningIds.Count)
    }
    if ($sampleIndex -lt $Samples) { Start-Sleep -Seconds $IntervalSeconds }
}

$last = $measurements[$measurements.Count - 1]
$allContainers = @($measurements | ForEach-Object { $_.containers })
$containerSummary = @(
    foreach ($group in ($allContainers | Group-Object service | Sort-Object Name)) {
        $lastContainer = $group.Group[-1]
        $cgroupSamples = @($group.Group | Where-Object { $null -ne $_.cgroup } | ForEach-Object cgroup)
        [pscustomobject]@{
            service = $group.Name
            peakObservedMemoryBytes = Get-ObservedPeak $group.Group 'memoryUsageBytes'
            peakObservedCpuPercent = Get-ObservedPeak $group.Group 'cpuPercent'
            peakObservedPidsIncludingThreads = Get-ObservedPeak $group.Group 'pidsIncludingThreads'
            peakObservedCgroupCurrentBytes = Get-ObservedPeak $cgroupSamples 'memoryCurrentBytes'
            cgroupLifetimePeakBytes = Get-ObservedPeak $cgroupSamples 'memoryLifetimePeakBytes'
            oomKilledObserved = (@($group.Group | Where-Object oomKilled).Count -gt 0)
            lastObserved = $lastContainer
        }
    }
)
$allApplications = @($measurements | ForEach-Object { $_.applications })
$applicationSummary = @(
    foreach ($group in ($allApplications | Group-Object service | Sort-Object Name)) {
        [pscustomobject]@{
            service = $group.Name
            peakObservedHeapUsedBytes = Get-ObservedPeak $group.Group 'heapUsedBytes'
            peakObservedHeapCommittedBytes = Get-ObservedPeak $group.Group 'heapCommittedBytes'
            peakObservedThreadsLive = Get-ObservedPeak $group.Group 'threadsLive'
            lastObserved = $group.Group[-1]
        }
    }
)
$allNacos = @($measurements | ForEach-Object { $_.nacosJavaProcesses })
$summary = [pscustomobject]@{
    project = 'opsagent'
    sampleCount = $measurements.Count
    intervalSecondsBetweenSamples = $IntervalSeconds
    startedAtUtc = $measurements[0].startedAtUtc
    completedAtUtc = $last.completedAtUtc
    peakObservedTotalMemoryBytes = Get-ObservedPeak $measurements.ToArray() 'totalObservedMemoryBytes'
    lastObservedTotalMemoryBytes = $last.totalObservedMemoryBytes
    peakObservedSumDockerWorkingSetBytes = Get-ObservedPeak $measurements.ToArray() 'sumDockerWorkingSetBytes'
    peakObservedSumCgroupCurrentBytes = Get-ObservedPeak $measurements.ToArray() 'sumCgroupCurrentBytes'
    lastSumDockerWorkingSetBytes = $last.sumDockerWorkingSetBytes
    lastSumCgroupCurrentBytes = $last.sumCgroupCurrentBytes
    peakObservedNacosRssBytes = Get-ObservedPeak $allNacos 'rssBytes'
    peakObservedNacosThreads = Get-ObservedPeak $allNacos 'threads'
    containers = $containerSummary
    applications = $applicationSummary
    lastSample = $last
    notes = @(
        'Docker stats memory is the Docker-reported working usage, not total host/WSL memory.'
        'On cgroup v2 Linux Docker stats subtracts inactive_file; cgroup memory.current includes charged file cache.'
        'Cgroup fields are null when unsupported or unreadable; cgroup sums cover only available containers.'
        'Cgroup memory.peak is a cgroup lifetime high-water mark, not necessarily a peak from this sampling run.'
        'A configured memory limit of zero means no explicit container memory limit.'
        'Docker PIDs includes threads. Java heap max is a limit, not resident memory.'
        'Unknown JVM pool maximums (-1) are excluded from the reported heap maximum.'
        'Observed peaks are sampled peaks; lastSample is not a guarantee of steady state.'
        'Restart counts and OOM state apply to each recorded container ID, not historical deleted containers.'
    )
}
$summaryPath = Join-Path $outputPath 'summary.json'
$summary | ConvertTo-Json -Depth 15 | Set-Content -LiteralPath $summaryPath -Encoding UTF8
$containerSummary | Select-Object service,
    @{Name = 'Last MiB'; Expression = {
        if ($null -ne $_.lastObserved.memoryUsageBytes) { [math]::Round($_.lastObserved.memoryUsageBytes / 1MB, 1) }
    }},
    @{Name = 'Peak MiB'; Expression = {
        if ($null -ne $_.peakObservedMemoryBytes) { [math]::Round($_.peakObservedMemoryBytes / 1MB, 1) }
    }},
    @{Name = 'Cgroup MiB'; Expression = {
        if ($null -ne $_.lastObserved.cgroup -and $null -ne $_.lastObserved.cgroup.memoryCurrentBytes) {
            [math]::Round($_.lastObserved.cgroup.memoryCurrentBytes / 1MB, 1)
        }
    }},
    @{Name = 'Limit MiB'; Expression = {
        if ($_.lastObserved.configuredMemoryLimitBytes -gt 0) {
            [math]::Round($_.lastObserved.configuredMemoryLimitBytes / 1MB, 1)
        } else { 'unlimited' }
    }},
    @{Name = 'Restarts'; Expression = { $_.lastObserved.restartCount }},
    @{Name = 'OOM'; Expression = { $_.oomKilledObserved }} |
    Format-Table -AutoSize | Out-Host
Write-Host "Resource summary: $summaryPath"
