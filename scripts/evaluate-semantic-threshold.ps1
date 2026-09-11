param(
    [string]$Endpoint = "http://127.0.0.1:8090/embed",
    [string]$Dataset,
    [string]$Report
)

$ErrorActionPreference = "Stop"
$DataRoot = if ([string]::IsNullOrWhiteSpace($env:LINGSHU_DATA_ROOT)) {
    "E:\LingShuData"
} else {
    $env:LINGSHU_DATA_ROOT
}
if ([string]::IsNullOrWhiteSpace($Dataset)) {
    $Dataset = Join-Path $DataRoot "datasets\semantic-eval-samples.jsonl"
}
if ([string]::IsNullOrWhiteSpace($Report)) {
    $Report = Join-Path $DataRoot "logs\semantic-threshold-report.txt"
}
$pythonScript = Join-Path $PSScriptRoot "evaluate_semantic_threshold.py"

& (Join-Path $PSScriptRoot "python.ps1") $pythonScript `
    --endpoint $Endpoint `
    --dataset $Dataset `
    --report $Report
exit $LASTEXITCODE
