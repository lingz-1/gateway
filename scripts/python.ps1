param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$PythonArguments
)

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "toolchain.ps1")
$CondaExecutable = Resolve-LingShuConda

& $CondaExecutable run --no-capture-output -n $script:LingShuCondaEnvironment `
    python @PythonArguments
exit $LASTEXITCODE
