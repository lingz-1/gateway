$CondaExecutable = "D:\anaconda\Scripts\conda.exe"
$JarPath = Join-Path $PSScriptRoot "..\lingshu-gateway\target\lingshu-gateway-0.1.0-SNAPSHOT.jar"

if (-not (Test-Path -LiteralPath $JarPath)) {
    throw "Gateway JAR not found. Run .\scripts\mvn.ps1 verify first."
}

& $CondaExecutable run --no-capture-output -n lingshu-dev java -jar $JarPath @args
exit $LASTEXITCODE
