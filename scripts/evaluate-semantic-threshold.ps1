param(
    [string]$Endpoint = "http://127.0.0.1:8090/embed",
    [string]$Dataset,
    [string]$Report,
    [string]$RawOutput
)

$ErrorActionPreference = "Stop"
$DataRoot = if ([string]::IsNullOrWhiteSpace($env:LINGSHU_DATA_ROOT)) {
    "E:\LingShuData"
} else {
    $env:LINGSHU_DATA_ROOT
}
if ([string]::IsNullOrWhiteSpace($Dataset)) {
    $Dataset = Join-Path $DataRoot "datasets\semantic-eval-samples-240.jsonl"
}
if ([string]::IsNullOrWhiteSpace($Report)) {
    $Report = Join-Path $DataRoot "logs\semantic-threshold-report.json"
}
if ([string]::IsNullOrWhiteSpace($RawOutput)) {
    $RawOutput = Join-Path $DataRoot "logs\semantic-threshold-raw.json"
}
$pythonScript = Join-Path $PSScriptRoot "evaluate_semantic_threshold.py"

& (Join-Path $PSScriptRoot "python.ps1") $pythonScript `
    --endpoint $Endpoint `
    --dataset $Dataset `
    --report $Report `
    --raw-output $RawOutput
exit $LASTEXITCODE
