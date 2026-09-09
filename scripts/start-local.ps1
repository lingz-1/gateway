param(
    [ValidateSet("Stub", "DeepSeek")]
    [string]$Provider = "Stub",
    [switch]$Build,
    [switch]$FullInfrastructure
)

$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$EnvironmentFile = Join-Path $ProjectRoot ".env"

if (Test-Path -LiteralPath $EnvironmentFile) {
    foreach ($line in Get-Content -LiteralPath $EnvironmentFile) {
        if ($line -match '^\s*([^#][^=]*)=(.*)$') {
            [Environment]::SetEnvironmentVariable($Matches[1].Trim(), $Matches[2].Trim(), "Process")
        }
    }
} elseif ($FullInfrastructure -or $Provider -eq "DeepSeek") {
    throw ".env not found. Copy .env.example to .env and configure local infrastructure."
}

$DataRoot = if ([string]::IsNullOrWhiteSpace($env:LINGSHU_DATA_ROOT)) { "E:\LingShuData" } else { $env:LINGSHU_DATA_ROOT }
$LogRoot = Join-Path $DataRoot "logs"
New-Item -ItemType Directory -Path $LogRoot -Force | Out-Null

if ($FullInfrastructure -or $Provider -eq "DeepSeek") {
    & (Join-Path $PSScriptRoot "infra.ps1") up
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }
}

if ($Build) {
    & (Join-Path $PSScriptRoot "mvn.ps1") verify
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }
    & (Join-Path $PSScriptRoot "npm.ps1") ci
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }
    & (Join-Path $PSScriptRoot "npm.ps1") run build
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }
} elseif (-not (Test-Path -LiteralPath (Join-Path $ProjectRoot "lingshu-web\node_modules"))) {
    throw "Frontend dependencies are missing. Run .\scripts\start-local.ps1 -Build first."
}

function Test-Health([string]$Uri) {
    try {
        $health = Invoke-RestMethod -Uri $Uri -TimeoutSec 2
        return $health.status -eq "UP"
    } catch {
        return $false
    }
}

function Wait-Health([string]$Name, [string]$Uri) {
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        if (Test-Health $Uri) {
            return
        }
        Start-Sleep -Seconds 1
    }
    throw "$Name did not become healthy. Check logs under $LogRoot."
}

function Test-Web([string]$Uri) {
    try {
        $response = Invoke-WebRequest -Uri $Uri -TimeoutSec 2 -UseBasicParsing
        return $response.StatusCode -eq 200
    } catch {
        return $false
    }
}

function Wait-Web([string]$Uri) {
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        if (Test-Web $Uri) {
            return
        }
        Start-Sleep -Seconds 1
    }
    throw "Web did not become ready. Check logs under $LogRoot."
}

function Assert-PortAvailable([string]$Name, [int]$Port) {
    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, $Port)
    try {
        $listener.Start()
    } catch {
        throw "$Name port $Port is already in use, but its health endpoint is not UP. Stop the stale process first."
    } finally {
        $listener.Stop()
    }
}

$PowerShellExecutable = Join-Path $PSHOME "pwsh.exe"
if (-not (Test-Path -LiteralPath $PowerShellExecutable)) {
    $PowerShellExecutable = "powershell.exe"
}

$CoreHealth = "http://127.0.0.1:8081/actuator/health"
if (-not (Test-Health $CoreHealth)) {
    Assert-PortAvailable "Core" 8081
    if ($Provider -eq "DeepSeek") {
        $CoreScript = "run-core-deepseek.ps1"
    } elseif ($FullInfrastructure) {
        $CoreScript = "run-core-local.ps1"
    } else {
        $env:LINGSHU_PROVIDER_DEEPSEEK_ENABLED = "false"
        $env:LINGSHU_CACHE_SEMANTIC_ENABLED = "false"
        $env:LINGSHU_CACHE_EXACT_STORE = "memory"
        $env:LINGSHU_DATABASE_MIGRATION_ENABLED = "false"
        $env:LINGSHU_TENANT_POLICY_PERSISTENCE_ENABLED = "false"
        $env:LINGSHU_TENANT_POLICY_NACOS_ENABLED = "false"
        $env:LINGSHU_VIRTUAL_BILLING_PERSISTENCE_ENABLED = "false"
        $env:LINGSHU_BILLING_ENABLED = "false"
        $env:LINGSHU_BILLING_OUTBOX_ENABLED = "false"
        $env:LINGSHU_KAFKA_ENABLED = "false"
        $env:MANAGEMENT_HEALTH_REDIS_ENABLED = "false"
        $CoreScript = "run-core.ps1"
    }
    Start-Process -FilePath $PowerShellExecutable `
        -ArgumentList @("-NoProfile", "-File", (Join-Path $PSScriptRoot $CoreScript)) `
        -WorkingDirectory $ProjectRoot `
        -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $LogRoot "core.out.log") `
        -RedirectStandardError (Join-Path $LogRoot "core.err.log")
    Wait-Health "Core" $CoreHealth
}

$GatewayHealth = "http://127.0.0.1:8080/actuator/health"
if (-not (Test-Health $GatewayHealth)) {
    Assert-PortAvailable "Gateway" 8080
    Start-Process -FilePath $PowerShellExecutable `
        -ArgumentList @("-NoProfile", "-File", (Join-Path $PSScriptRoot "run-gateway.ps1")) `
        -WorkingDirectory $ProjectRoot `
        -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $LogRoot "gateway.out.log") `
        -RedirectStandardError (Join-Path $LogRoot "gateway.err.log")
    Wait-Health "Gateway" $GatewayHealth
}

$WebUri = "http://127.0.0.1:5173"
if (-not (Test-Web $WebUri)) {
    Assert-PortAvailable "Web" 5173
    Start-Process -FilePath $PowerShellExecutable `
        -ArgumentList @("-NoProfile", "-File", (Join-Path $PSScriptRoot "run-web.ps1")) `
        -WorkingDirectory $ProjectRoot `
        -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $LogRoot "web.out.log") `
        -RedirectStandardError (Join-Path $LogRoot "web.err.log")
    Wait-Web $WebUri
}

Write-Host "LingShu is ready."
Write-Host "Web:        $WebUri"
Write-Host "Gateway:    http://127.0.0.1:8080"
Write-Host "Core:       http://127.0.0.1:8081"
if ($FullInfrastructure -or $Provider -eq "DeepSeek") {
    Write-Host "Prometheus: http://127.0.0.1:9090"
    Write-Host "Grafana:    http://127.0.0.1:3001"
}
Write-Host "Provider:   $Provider"
Write-Host "Infrastructure: $(if ($FullInfrastructure -or $Provider -eq 'DeepSeek') { 'full' } else { 'none (in-memory)' })"
