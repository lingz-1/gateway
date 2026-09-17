param(
    [ValidateRange(1, 1000000000)]
    [int]$Requests = 10000,
    [int[]]$Concurrency = @(20, 50, 100, 200),
    [ValidateRange(1, 1000000000)]
    [int]$PromptCardinality = 1000,
    [ValidateRange(0.0, 1.0)]
    [double]$StreamRatio = 0.5,
    [ValidateRange(0, 1000000)]
    [int]$WarmupRequests = 1000,
    [ValidateRange(1, 20)]
    [int]$Repetitions = 3,
    [switch]$WarmCache
)

$ErrorActionPreference = "Stop"
$Timestamp = Get-Date -Format "yyyyMMdd-HHmmss"

foreach ($value in $Concurrency) {
    if ($value -lt 1) {
        throw "Concurrency values must be positive."
    }
}

foreach ($value in $Concurrency) {
    for ($repetition = 1; $repetition -le $Repetitions; $repetition++) {
        $Mode = if ($WarmCache) { "exact" } else { "uncached" }
        $Label = "$Mode-$Requests-c$value-r$repetition"
        $RunId = "$Label-$Timestamp"
        $Arguments = @(
            (Join-Path $PSScriptRoot "load-test-large.py"),
            "--requests", "$Requests",
            "--concurrency", "$value",
            "--prompt-cardinality", "$PromptCardinality",
            "--stream-ratio", "$StreamRatio",
            "--warmup-requests", "$WarmupRequests",
            "--label", $Label,
            "--run-id", $RunId
        )
        if ($WarmCache) {
            $Arguments += "--warm-cache"
        }

        Write-Host "Running $Label"
        & (Join-Path $PSScriptRoot "python.ps1") @Arguments
        if ($LASTEXITCODE -ne 0) {
            exit $LASTEXITCODE
        }
    }
}
