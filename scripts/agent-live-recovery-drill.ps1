[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^1[3-9]\d{9}$')]
    [string] $Phone,

    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string] $Password,

    [string] $BaseUrl = "http://localhost",

    [string] $Message = "",

    [ValidateRange(15, 120)]
    [int] $RunIdTimeoutSeconds = 30,

    [ValidateRange(15, 600)]
    [int] $DrillTimeoutSeconds = 180
)

$ErrorActionPreference = "Stop"
$curlProcess = $null
$temporaryFiles = @()
if ([string]::IsNullOrWhiteSpace($Message)) {
    $Message = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String(
        "5oiR5pyJ6bih6JuL44CB55Wq6IyE5ZKM6JGx6Iqx77yM5biu5oiR55Sf5oiQ5LiA6YGT5pma6aSQ6I+c6LCx"
    ))
}

function Get-UserToken {
    param(
        [Parameter(Mandatory = $true)]
        [string] $LoginPhone,

        [Parameter(Mandatory = $true)]
        [string] $LoginPassword
    )

    $body = @{ phone = $LoginPhone; password = $LoginPassword } | ConvertTo-Json -Compress
    $response = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/auth/user/password-login" `
        -ContentType "application/json" -Body $body
    $token = [string] $response.data.token
    if ([string]::IsNullOrWhiteSpace($token)) {
        throw "User login did not return a JWT."
    }
    return $token
}

function Start-AgentRequest {
    param(
        [Parameter(Mandatory = $true)]
        [string] $TokenValue
    )

    $suffix = [guid]::NewGuid().ToString("N")
    $eventFile = Join-Path ([IO.Path]::GetTempPath()) ("agent-live-recovery-" + $suffix + ".sse")
    $errorFile = Join-Path ([IO.Path]::GetTempPath()) ("agent-live-recovery-" + $suffix + ".err")
    $bodyFile = Join-Path ([IO.Path]::GetTempPath()) ("agent-live-recovery-" + $suffix + ".json")
    $configFile = Join-Path ([IO.Path]::GetTempPath()) ("agent-live-recovery-" + $suffix + ".curlrc")
    $script:temporaryFiles = @($eventFile, $errorFile, $bodyFile, $configFile)

    $requestBody = @{
        conversationId = $null
        message = $Message
        confirmationId = $null
        idempotencyKey = $null
        targetRecipeSearchLogId = $null
        previousRecipeTitle = $null
    } | ConvertTo-Json -Compress
    [IO.File]::WriteAllText($bodyFile, $requestBody, [Text.UTF8Encoding]::new($false))

    $curlConfig = @(
        "silent",
        "show-error",
        "no-buffer",
        "request = POST",
        "url = `"$BaseUrl/api/agent/chat/stream`"",
        "header = `"Authorization: Bearer $TokenValue`"",
        "header = `"Accept: text/event-stream`"",
        "header = `"Content-Type: application/json`"",
        "data-binary = `"@$(($bodyFile -replace '\\', '/'))`""
    ) -join [Environment]::NewLine
    [IO.File]::WriteAllText($configFile, $curlConfig, [Text.UTF8Encoding]::new($false))

    $script:curlProcess = Start-Process -FilePath "curl.exe" -ArgumentList @("--config", $configFile) `
        -RedirectStandardOutput $eventFile -RedirectStandardError $errorFile -PassThru -WindowStyle Hidden

    $deadline = (Get-Date).AddSeconds($RunIdTimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if (Test-Path -LiteralPath $eventFile) {
            $sse = Get-Content -LiteralPath $eventFile -Raw -ErrorAction SilentlyContinue
            if ($sse -match '"runId"\s*:\s*"([^"]+)"') {
                return $Matches[1]
            }
        }
        if ($script:curlProcess.HasExited) {
            break
        }
        Start-Sleep -Milliseconds 250
    }

    $curlError = if (Test-Path -LiteralPath $errorFile) {
        (Get-Content -LiteralPath $errorFile -Raw -ErrorAction SilentlyContinue).Trim()
    } else {
        ""
    }
    if ($curlError) {
        throw "The agent request ended before exposing a runId: $curlError"
    }
    throw "The agent request did not expose a runId within $RunIdTimeoutSeconds seconds."
}

try {
    Write-Host "[1/3] Login with the dedicated drill user..."
    $token = Get-UserToken -LoginPhone $Phone -LoginPassword $Password

    Write-Host "[2/3] Start a real streaming Agent request and capture its active runId..."
    $runId = Start-AgentRequest -TokenValue $token
    Write-Host "      Active runId captured: $runId"

    Write-Host "[3/3] Execute the Docker SIGKILL and recovery drill..."
    & "$PSScriptRoot\agent-recovery-drill.ps1" `
        -RunId $runId -Token $token -BaseUrl $BaseUrl `
        -StaleAfter "PT5S" -ScanDelay "PT2S" -TimeoutSeconds $DrillTimeoutSeconds
    if ($LASTEXITCODE -ne 0) {
        throw "The Docker recovery drill exited with code $LASTEXITCODE."
    }
}
finally {
    if ($curlProcess -and -not $curlProcess.HasExited) {
        Stop-Process -Id $curlProcess.Id -Force -ErrorAction SilentlyContinue
    }
    if ($temporaryFiles.Count -gt 0) {
        Remove-Item -LiteralPath $temporaryFiles -Force -ErrorAction SilentlyContinue
    }
}
