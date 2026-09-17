param(
    [ValidateSet("Off", "Exact")]
    [string]$Cache = "Off",
    [switch]$Build
)

$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent $PSScriptRoot

& (Join-Path $PSScriptRoot "stop-local.ps1")
if (-not $?) {
    exit 1
}

$env:LINGSHU_BENCHMARK_CACHE_ENABLED = if ($Cache -eq "Exact") { "true" } else { "false" }
try {
    & (Join-Path $PSScriptRoot "start-local.ps1") -Provider Stub -Build:$Build -Benchmark
    if (-not $?) {
        exit 1
    }
} finally {
    Remove-Item Env:LINGSHU_BENCHMARK_CACHE_ENABLED -ErrorAction SilentlyContinue
}
