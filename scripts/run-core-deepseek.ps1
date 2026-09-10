$ErrorActionPreference = "Stop"
$EnvironmentFile = Join-Path $PSScriptRoot "..\.env"

if (-not (Test-Path -LiteralPath $EnvironmentFile)) {
    throw ".env not found. Copy .env.example to .env and set local passwords and DEEPSEEK_API_KEY."
}

foreach ($line in Get-Content -LiteralPath $EnvironmentFile) {
    if ($line -match '^\s*([^#][^=]*)=(.*)$') {
        [Environment]::SetEnvironmentVariable($Matches[1].Trim(), $Matches[2].Trim(), "Process")
    }
}

if ([string]::IsNullOrWhiteSpace($env:DEEPSEEK_API_KEY)) {
    throw "DEEPSEEK_API_KEY is missing from .env."
}

$env:LINGSHU_PROVIDER_DEEPSEEK_ENABLED = "true"
$env:LINGSHU_DEEPSEEK_MODEL = "deepseek-v4flash"
$env:LINGSHU_DEEPSEEK_MAX_ATTEMPTS = "2"
$env:LINGSHU_CACHE_SEMANTIC_ENABLED = "true"
$env:LINGSHU_CACHE_EXACT_STORE = "redis"
$env:LINGSHU_EMBEDDING_PROVIDER = "tei"
$env:LINGSHU_EMBEDDING_ENDPOINT = "http://127.0.0.1:8090/embed"
$env:LINGSHU_EMBEDDING_MODEL = "Qwen3-Embedding-4B"
$env:LINGSHU_EMBEDDING_DIMENSION = "2560"
$env:LINGSHU_VIRTUAL_BILLING_PERSISTENCE_ENABLED = "true"
$env:LINGSHU_DATABASE_MIGRATION_ENABLED = "true"
$env:LINGSHU_TENANT_POLICY_PERSISTENCE_ENABLED = "true"
$env:LINGSHU_TENANT_RATE_LIMIT_STORE = "redis"
$env:LINGSHU_BILLING_ENABLED = "true"
$env:LINGSHU_BILLING_OUTBOX_ENABLED = "true"
$env:LINGSHU_KAFKA_ENABLED = "true"

if (-not [string]::IsNullOrWhiteSpace($env:LINGSHU_INTERNAL_ADMIN_KEY)) {
    $env:LINGSHU_INTERNAL_ADMIN_KEY_ENABLED = "true"
}

& (Join-Path $PSScriptRoot "run-core.ps1") @args
exit $LASTEXITCODE
