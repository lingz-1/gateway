$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "toolchain.ps1")
$CondaExecutable = Resolve-LingShuConda
$JarPath = Join-Path $PSScriptRoot "..\lingshu-core\target\lingshu-core-0.1.0-SNAPSHOT.jar"

if (-not (Test-Path -LiteralPath $JarPath)) {
    throw "Core JAR not found. Run .\scripts\mvn.ps1 verify first."
}

& $CondaExecutable run --no-capture-output -n $script:LingShuCondaEnvironment `
    java -jar $JarPath @args
exit $LASTEXITCODE
