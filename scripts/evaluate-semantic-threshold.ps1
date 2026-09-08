param(
    [string]$Endpoint = "http://127.0.0.1:8090/embed",
    [string]$Dataset = "E:\LingShuData\datasets\semantic-eval-samples.jsonl",
    [string]$Report = "E:\LingShuData\logs\semantic-threshold-report.txt"
)

$ErrorActionPreference = "Stop"
$condaExecutable = "D:\anaconda\Scripts\conda.exe"
$pythonScript = Join-Path $PSScriptRoot "evaluate_semantic_threshold.py"

& $condaExecutable run --no-capture-output -n lingshu-dev python $pythonScript `
    --endpoint $Endpoint `
    --dataset $Dataset `
    --report $Report
exit $LASTEXITCODE
