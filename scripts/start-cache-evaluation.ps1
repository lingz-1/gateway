param(
    [ValidateSet("Off", "Exact", "Semantic")]
    [string]$Mode
)

$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$EnvironmentFile = Join-Path $ProjectRoot ".env"

& (Join-Path $PSScriptRoot "stop-local.ps1")
if (-not $?) {
    exit 1
}

if (-not (Test-Path -LiteralPath $EnvironmentFile)) {
    throw ".env not found. Cache evaluation requires local Redis/PostgreSQL credentials."
}
foreach ($line in Get-Content -LiteralPath $EnvironmentFile) {
    if ($line -match '^\s*([^#][^=]*)=(.*)$') {
        [Environment]::SetEnvironmentVariable($Matches[1].Trim(), $Matches[2].Trim(), "Process")
    }
}

function Resolve-DockerCompose {
    $docker = Get-Command docker -ErrorAction SilentlyContinue
    if ($null -ne $docker) {
        $null = & $docker.Path compose version 2>&1
        if ($LASTEXITCODE -eq 0) {
            return @{ Executable = $docker.Path; Prefix = @("compose") }
        }
    }
    $compose = Get-Command docker-compose -ErrorAction SilentlyContinue
    if ($null -eq $compose) {
        throw "Docker Compose was not found."
    }
    return @{ Executable = $compose.Path; Prefix = @() }
}

function Wait-Health([string]$Name, [string]$Uri, [int]$Attempts = 90) {
    for ($attempt = 0; $attempt -lt $Attempts; $attempt++) {
        try {
            $response = Invoke-WebRequest -Uri $Uri -TimeoutSec 2 -UseBasicParsing
            if ($response.StatusCode -ge 200 -and $response.StatusCode -lt 300) {
                return
            }
        } catch {
        }
        Start-Sleep -Seconds 1
    }
    throw "$Name did not become healthy: $Uri"
}

if ($Mode -ne "Off") {
    $compose = Resolve-DockerCompose
    $composeArgs = @(
        "--project-directory", $ProjectRoot,
        "--env-file", $EnvironmentFile,
        "-f", (Join-Path $ProjectRoot "compose.yaml"),
        "up", "--detach", "--wait", "redis"
    )
    if ($Mode -eq "Semantic") {
        $composeArgs += @("postgres", "embedding")
    }
    $composePrefix = $compose.Prefix
    & $compose.Executable @composePrefix @composeArgs
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }
    if ($Mode -eq "Semantic") {
        Wait-Health "Embedding" "http://127.0.0.1:8090/health" 360
    }
}

$env:LINGSHU_PROVIDER_DEEPSEEK_ENABLED = "false"
$env:LINGSHU_CACHE_EXACT_ENABLED = if ($Mode -eq "Off") { "false" } else { "true" }
$env:LINGSHU_CACHE_EXACT_STORE = if ($Mode -eq "Off") { "memory" } else { "redis" }
$env:LINGSHU_CACHE_SEMANTIC_ENABLED = if ($Mode -eq "Semantic") { "true" } else { "false" }
$env:LINGSHU_CACHE_SEMANTIC_THRESHOLD = "0.79"
$env:LINGSHU_EMBEDDING_PROVIDER = "tei"
$env:LINGSHU_EMBEDDING_ENDPOINT = "http://127.0.0.1:8090/embed"
$env:LINGSHU_EMBEDDING_MODEL = "Qwen3-Embedding-4B"
$env:LINGSHU_EMBEDDING_DIMENSION = "2560"
$env:LINGSHU_STUB_INVOKE_DELAY = "40ms"
$env:LINGSHU_DATABASE_MIGRATION_ENABLED = "false"
$env:LINGSHU_TENANT_POLICY_PERSISTENCE_ENABLED = "false"
$env:LINGSHU_TENANT_RATE_LIMIT_STORE = "memory"
$env:LINGSHU_TENANT_POLICY_NACOS_ENABLED = "false"
$env:LINGSHU_VIRTUAL_BILLING_PERSISTENCE_ENABLED = "false"
$env:LINGSHU_BILLING_ENABLED = "false"
$env:LINGSHU_BILLING_OUTBOX_ENABLED = "false"
$env:LINGSHU_KAFKA_ENABLED = "false"
$env:MANAGEMENT_HEALTH_REDIS_ENABLED = "false"
$env:LOGGING_LEVEL_COM_LINGSHU_GATEWAY_FILTER = "WARN"
$env:LOGGING_LEVEL_COM_LINGSHU_CORE = "WARN"

$DataRoot = if ([string]::IsNullOrWhiteSpace($env:LINGSHU_DATA_ROOT)) {
    "E:\LingShuData"
} else {
    $env:LINGSHU_DATA_ROOT
}
$LogRoot = Join-Path $DataRoot "logs"
New-Item -ItemType Directory -Path $LogRoot -Force | Out-Null
$PowerShellExecutable = Join-Path $PSHOME "pwsh.exe"
if (-not (Test-Path -LiteralPath $PowerShellExecutable)) {
    $PowerShellExecutable = "powershell.exe"
}

Start-Process -FilePath $PowerShellExecutable `
    -ArgumentList @("-NoProfile", "-File", (Join-Path $PSScriptRoot "run-core.ps1")) `
    -WorkingDirectory $ProjectRoot `
    -WindowStyle Hidden `
    -RedirectStandardOutput (Join-Path $LogRoot "cache-eval-core-$Mode.out.log") `
    -RedirectStandardError (Join-Path $LogRoot "cache-eval-core-$Mode.err.log")
Wait-Health "Core" "http://127.0.0.1:8081/actuator/health"

Start-Process -FilePath $PowerShellExecutable `
    -ArgumentList @("-NoProfile", "-File", (Join-Path $PSScriptRoot "run-gateway.ps1")) `
    -WorkingDirectory $ProjectRoot `
    -WindowStyle Hidden `
    -RedirectStandardOutput (Join-Path $LogRoot "cache-eval-gateway-$Mode.out.log") `
    -RedirectStandardError (Join-Path $LogRoot "cache-eval-gateway-$Mode.err.log")
Wait-Health "Gateway" "http://127.0.0.1:8080/actuator/health"

Write-Host "Cache evaluation service is ready: $Mode"
