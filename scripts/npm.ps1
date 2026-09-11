param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$NpmArguments
)

$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$WebRoot = Join-Path $ProjectRoot "lingshu-web"
. (Join-Path $PSScriptRoot "toolchain.ps1")
$CondaExecutable = Resolve-LingShuConda

Push-Location $WebRoot
try {
    & $CondaExecutable run --no-capture-output -n $script:LingShuCondaEnvironment `
        npm @NpmArguments
    exit $LASTEXITCODE
} finally {
    Pop-Location
}
