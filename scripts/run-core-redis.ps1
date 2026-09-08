$ErrorActionPreference = "Stop"
$EnvironmentFile = Join-Path $PSScriptRoot "..\.env"

if (-not (Test-Path -LiteralPath $EnvironmentFile)) {
    throw ".env not found. Copy .env.example to .env and set local passwords."
}

foreach ($line in Get-Content -LiteralPath $EnvironmentFile) {
    if ($line -match '^\s*([^#][^=]*)=(.*)$') {
        [Environment]::SetEnvironmentVariable($Matches[1].Trim(), $Matches[2].Trim(), "Process")
    }
}

$env:LINGSHU_CACHE_EXACT_STORE = "redis"
& (Join-Path $PSScriptRoot "run-core.ps1") @args
exit $LASTEXITCODE
