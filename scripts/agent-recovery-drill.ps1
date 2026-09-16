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
            throw "docker compose $($Arguments -join ' ') failed with exit code $LASTEXITCODE"
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

Write-Host "[1/5] Validate that the runId belongs to the current account..."
$before = Get-RunStatus
$beforeStatus = Get-StatusValue $before
if ($beforeStatus -notin @("RUNNING", "RECOVERING")) {
    throw "The drill requires RUNNING or RECOVERING, but the current status is $beforeStatus. Start a slower agent request and run this script immediately."
}
Write-Host "      Status before drill: $beforeStatus, runId: $RunId"

Write-Host "[2/5] Locate the backend container and simulate SIGKILL..."
$beforeContainer = (& docker compose ps -q backend).Trim()
if ([string]::IsNullOrWhiteSpace($beforeContainer)) {
    throw "The backend container was not found. Run docker compose up -d first."
}
Invoke-Compose -Arguments @("kill", "-s", "SIGKILL", "backend")

Write-Host "[3/5] Start recovery scanning with $StaleAfter/$ScanDelay..."
Invoke-Compose -Arguments @("up", "-d", "--force-recreate", "backend") -WithRecoveryOverrides

Write-Host "[4/5] Wait for backend health and poll the persisted run state..."
$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
$healthy = $false
$lastStatus = $beforeStatus
while ((Get-Date) -lt $deadline) {
    # `docker compose ps --format json` may emit truncated or multi-line JSON
    # while a container is starting. Read the health state through inspect instead.
    $healthContainer = (& docker compose ps -q backend 2>$null | Select-Object -First 1)
    $health = ""
    if ($healthContainer) {
        $healthContainer = ([string] $healthContainer).Trim()
        $health = ((& docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' $healthContainer 2>$null | Select-Object -First 1))
        if ($health) {
            $health = ([string] $health).Trim()
        }
    }
    if ($health -eq "healthy") {
        $healthy = $true
        try {
            $response = Get-RunStatus
            $lastStatus = Get-StatusValue $response
            Write-Host "      Current status: $lastStatus"
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
    throw "The backend did not become healthy within $TimeoutSeconds seconds."
}

Write-Host "[5/5] Check recovery logs and print evidence..."
$logs = (& docker compose logs --no-color --since "10m" backend 2>&1) -join [Environment]::NewLine
$recoveryLog = $logs | Select-String -SimpleMatch "Recovering agent run $RunId"
if ($recoveryLog) {
    Write-Host "      Recovery scan log found."
}
else {
    throw "No recovery log for this runId was found in the last 10 minutes. The drill refuses to treat an ordinary completion as recovery success. Check stale-after, scan-delay, and whether the task finished before SIGKILL."
}

if ($lastStatus -eq "FAILED") {
    throw "The task was detected after restart but ended in FAILED. Check backend logs for the cause."
}
if ($lastStatus -notin @("COMPLETED", "WAITING_CONFIRMATION")) {
    throw "The drill timed out with final status $lastStatus."
}

$after = Get-RunStatus
Write-Host "Drill passed: backend was SIGKILLed and runId=$RunId continued from its checkpoint. Final status: $lastStatus."
Write-Host ("Status after recovery: " + ($after | ConvertTo-Json -Compress))
