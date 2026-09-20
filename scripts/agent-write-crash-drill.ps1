[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateRange(1, 2147483647)]
    [long] $ConversationId,

    [Parameter(Mandatory = $true)]
    [ValidateRange(1, 2147483647)]
    [long] $ConfirmationId,

    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string] $IdempotencyKey,

    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string] $Token,

    [string] $BaseUrl = "http://localhost",

    [ValidatePattern('^PT([0-9]+)([SMH])$')]
    [string] $PauseAfter = "PT30S",

    [ValidateRange(30, 600)]
    [int] $TimeoutSeconds = 120
)

$ErrorActionPreference = "Stop"
$script:composeOverrides = @{
    AGENT_WRITE_DRILL_ENABLED = "true"
    AGENT_WRITE_DRILL_PAUSE_BEFORE = "PT0S"
    AGENT_WRITE_DRILL_PAUSE_AFTER = $PauseAfter
}

function Invoke-Compose {
    param(
        [Parameter(Mandatory = $true)]
        [string[]] $Arguments,
        [switch] $WithDrillOverrides
    )

    $previous = @{}
    if ($WithDrillOverrides) {
        foreach ($entry in $script:composeOverrides.GetEnumerator()) {
            $previous[$entry.Key] = [Environment]::GetEnvironmentVariable($entry.Key, "Process")
            [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value, "Process")
        }
    }
    try {
        & docker compose @Arguments
        if ($LASTEXITCODE -ne 0) {
            throw "docker compose $($Arguments -join ' ') failed with exit code $LASTEXITCODE"
        }
    }
    finally {
        if ($WithDrillOverrides) {
            foreach ($entry in $previous.GetEnumerator()) {
                [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value, "Process")
            }
        }
    }
}

function Get-ConfirmationStatus {
    $headers = @{ Authorization = "Bearer $Token" }
    return Invoke-RestMethod -Method Get -Uri "$BaseUrl/api/agent/confirmations/$ConfirmationId" -Headers $headers
}

function Get-WriteStatus {
    $headers = @{ Authorization = "Bearer $Token" }
    return Invoke-RestMethod -Method Get -Uri "$BaseUrl/api/agent/writes/$([uri]::EscapeDataString($IdempotencyKey))" -Headers $headers
}

function Get-BackendHealth {
    $container = (& docker compose ps -q backend 2>$null | Select-Object -First 1)
    if (-not $container) {
        return ""
    }
    $health = (& docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' $container 2>$null | Select-Object -First 1)
    return ([string]$health).Trim()
}

function Wait-BackendHealthy {
    param([int] $Seconds)
    $deadline = (Get-Date).AddSeconds($Seconds)
    while ((Get-Date) -lt $deadline) {
        if ((Get-BackendHealth) -eq "healthy") {
            return
        }
        Start-Sleep -Seconds 2
    }
    throw "The backend did not become healthy within $Seconds seconds."
}

function Get-RecentBackendLogs {
    return ((& docker compose logs --no-color --since "10m" backend 2>&1) -join [Environment]::NewLine)
}

$job = $null
$killed = $false
$normalBackendRestored = $false
try {
    Write-Host "[1/6] Validate confirmation ownership and pending state..."
    $confirmation = Get-ConfirmationStatus
    $confirmationStatus = if ($null -ne $confirmation.data.status) { [string]$confirmation.data.status } else { [string]$confirmation.status }
    if ($confirmationStatus -ne "PENDING") {
        throw "The confirmation must be PENDING, but the current status is $confirmationStatus. Create a new confirmation and retry."
    }

    Write-Host "[2/6] Restart backend with a deterministic post-write pause..."
    Invoke-Compose -Arguments @("up", "-d", "--force-recreate", "backend") -WithDrillOverrides
    Wait-BackendHealthy -Seconds $TimeoutSeconds

    Write-Host "[3/6] Submit confirmation and wait for the post-write window..."
    $body = @{
        conversationId = $ConversationId
        confirmationId = $ConfirmationId
        idempotencyKey = $IdempotencyKey
    } | ConvertTo-Json -Compress
    $job = Start-Job -ScriptBlock {
        param($url, $token, $payload)
        try {
            Invoke-WebRequest -Method Post -Uri $url -Headers @{ Authorization = "Bearer $token"; Accept = "text/event-stream" } `
                -ContentType "application/json" -Body $payload -TimeoutSec 300 | Out-Null
        }
        catch {
            # The drill intentionally terminates this request below.
        }
    } -ArgumentList "$BaseUrl/api/agent/chat/stream", $Token, $body

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $pauseLog = $false
    while ((Get-Date) -lt $deadline) {
        $logs = Get-RecentBackendLogs
        if ($logs -match [regex]::Escape($IdempotencyKey) -and $logs -match "after business write") {
            $pauseLog = $true
            break
        }
        Start-Sleep -Seconds 2
    }
    if (-not $pauseLog) {
        throw "The post-write pause log was not found. The confirmation request may have completed before the drill window."
    }

    Write-Host "[4/6] Kill backend inside the write transaction window..."
    Invoke-Compose -Arguments @("kill", "-s", "SIGKILL", "backend")
    $killed = $true
    if ($job) {
        Stop-Job -Job $job -ErrorAction SilentlyContinue
    }

    Write-Host "[5/6] Restart backend without drill overrides and query the durable ledger..."
    Invoke-Compose -Arguments @("up", "-d", "--force-recreate", "backend")
    Wait-BackendHealthy -Seconds $TimeoutSeconds
    $normalBackendRestored = $true
    $after = Get-WriteStatus
    $afterStatus = if ($null -ne $after.data.status) { [string]$after.data.status } else { [string]$after.status }
    if ($afterStatus -ne "PROCESSING") {
        throw "Expected PROCESSING after a crash window, but the durable write status is $afterStatus."
    }

    $confirmationAfter = Get-ConfirmationStatus
    $confirmationAfterStatus = if ($null -ne $confirmationAfter.data.status) { [string]$confirmationAfter.data.status } else { [string]$confirmationAfter.status }
    if ($confirmationAfterStatus -ne "PENDING") {
        throw "Expected PENDING confirmation after transaction rollback, but the status is $confirmationAfterStatus."
    }

    Write-Host "[6/6] Crash-window drill passed."
    Write-Host "The durable ledger is PROCESSING and the confirmation is PENDING. The system will not retry an unknown write automatically."
    Write-Host ("Write status: " + ($after | ConvertTo-Json -Compress))
}
finally {
    if ($job) {
        Remove-Job -Job $job -Force -ErrorAction SilentlyContinue
    }
    if (-not $normalBackendRestored) {
        # Any failed step may leave the drill pause enabled. Restore the normal backend.
        try {
            Invoke-Compose -Arguments @("up", "-d", "--force-recreate", "backend")
        }
        catch {
            Write-Warning ("Could not restore the normal backend after the drill: " + $_.Exception.Message)
        }
    }
}
