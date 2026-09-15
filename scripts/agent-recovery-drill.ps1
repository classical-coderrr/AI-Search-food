[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string] $RunId,

    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string] $Token,

    [string] $BaseUrl = "http://localhost",

    [ValidatePattern('^PT([0-9]+)([SMH])$')]
    [string] $StaleAfter = "PT5S",

    [ValidatePattern('^PT([0-9]+)([SMH])$')]
    [string] $ScanDelay = "PT2S",

    [ValidateRange(15, 600)]
    [int] $TimeoutSeconds = 90
)

$ErrorActionPreference = "Stop"
$script:composeOverrides = @{
    AGENT_RECOVERY_ENABLED = "true"
    AGENT_RECOVERY_STALE_AFTER = $StaleAfter
    AGENT_RECOVERY_SCAN_DELAY = $ScanDelay
}

function Invoke-Compose {
    param(
        [Parameter(Mandatory = $true)]
        [string[]] $Arguments,
        [switch] $WithRecoveryOverrides
    )

    $previous = @{}
    if ($WithRecoveryOverrides) {
        foreach ($entry in $script:composeOverrides.GetEnumerator()) {
            $previous[$entry.Key] = [Environment]::GetEnvironmentVariable($entry.Key, "Process")
            [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value, "Process")
        }
    }
    try {
        & docker compose @Arguments
        if ($LASTEXITCODE -ne 0) {
            throw "docker compose $($Arguments -join ' ') 执行失败，退出码 $LASTEXITCODE"
        }
    }
    finally {
        if ($WithRecoveryOverrides) {
            foreach ($entry in $previous.GetEnumerator()) {
                [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value, "Process")
            }
        }
    }
}

function Get-RunStatus {
    $headers = @{ Authorization = "Bearer $Token" }
    return Invoke-RestMethod -Method Get -Uri "$BaseUrl/api/agent/runs/$RunId" -Headers $headers
}

function Get-StatusValue($response) {
    if ($null -ne $response.data.status) {
        return [string] $response.data.status
    }
    if ($null -ne $response.status) {
        return [string] $response.status
    }
    return "UNKNOWN"
}

Write-Host "[1/5] 校验 runId 是否属于当前账号…"
$before = Get-RunStatus
$beforeStatus = Get-StatusValue $before
if ($beforeStatus -notin @("RUNNING", "RECOVERING")) {
    throw "演练要求任务仍在 RUNNING/RECOVERING，当前状态为 $beforeStatus。请先从小厨灵发起一个较慢的请求，再立即执行脚本。"
}
Write-Host "      演练前状态: $beforeStatus, runId: $RunId"

Write-Host "[2/5] 记录后端容器并模拟 SIGKILL…"
$beforeContainer = (& docker compose ps -q backend).Trim()
if ([string]::IsNullOrWhiteSpace($beforeContainer)) {
    throw "找不到 backend 容器，请先执行 docker compose up -d。"
}
Invoke-Compose -Arguments @("kill", "-s", "SIGKILL", "backend")

Write-Host "[3/5] 以 $StaleAfter/$ScanDelay 启动恢复扫描…"
Invoke-Compose -Arguments @("up", "-d", "backend") -WithRecoveryOverrides

Write-Host "[4/5] 等待后端健康并轮询 Redis 中的运行状态…"
$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
$healthy = $false
$lastStatus = $beforeStatus
while ((Get-Date) -lt $deadline) {
    $health = (& docker compose ps --format json backend | ConvertFrom-Json)
    if ($health -and $health.Health -eq "healthy") {
        $healthy = $true
        try {
            $response = Get-RunStatus
            $lastStatus = Get-StatusValue $response
            Write-Host "      当前状态: $lastStatus"
            if ($lastStatus -in @("COMPLETED", "WAITING_CONFIRMATION", "FAILED")) {
                break
            }
        }
        catch {
            # Backend may be healthy before the HTTP route is ready.
        }
    }
    Start-Sleep -Seconds 2
}
if (-not $healthy) {
    throw "后端在 $TimeoutSeconds 秒内未恢复 healthy。"
}

Write-Host "[5/5] 检查恢复日志并输出证据…"
$logs = (& docker compose logs --no-color --since "10m" backend 2>&1) -join [Environment]::NewLine
$recoveryLog = $logs | Select-String -SimpleMatch "Recovering agent run $RunId"
if ($recoveryLog) {
    Write-Host "      已发现恢复扫描日志。"
}
else {
    throw "未在最近 10 分钟日志中找到该 runId 的恢复日志；拒绝把普通完成误判为恢复成功，请检查 stale-after、scan-delay 和任务是否在 kill 前已完成。"
}

if ($lastStatus -eq "FAILED") {
    throw "任务已恢复但最终状态为 FAILED，请查看 backend 日志定位错误。"
}
if ($lastStatus -notin @("COMPLETED", "WAITING_CONFIRMATION")) {
    throw "演练超时，最终状态为 $lastStatus。"
}

$after = Get-RunStatus
Write-Host "演练通过：backend 容器已被 SIGKILL，Redis 中的 runId=$RunId 从 checkpoint 继续，最终状态为 $lastStatus。"
Write-Host ("恢复后状态: " + ($after | ConvertTo-Json -Compress))
