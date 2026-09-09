param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$NpmArguments
)

$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$WebRoot = Join-Path $ProjectRoot "lingshu-web"
$Conda = "D:\anaconda\Scripts\conda.exe"

if (-not (Test-Path -LiteralPath $Conda)) {
    throw "Conda was not found at $Conda"
}

Push-Location $WebRoot
try {
    & $Conda run --no-capture-output -n lingshu-dev npm @NpmArguments
    exit $LASTEXITCODE
} finally {
    Pop-Location
}
