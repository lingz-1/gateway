$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "toolchain.ps1")
$CondaExecutable = Resolve-LingShuConda
$MavenRepository = $env:LINGSHU_MAVEN_REPOSITORY
if ([string]::IsNullOrWhiteSpace($MavenRepository)) {
    $EnvironmentPrefix = Resolve-LingShuEnvironmentPrefix -CondaExecutable $CondaExecutable
    $MavenRepository = Join-Path $EnvironmentPrefix ".m2\repository"
}

& $CondaExecutable run --no-capture-output -n $script:LingShuCondaEnvironment `
    mvn "-Dmaven.repo.local=$MavenRepository" @args
exit $LASTEXITCODE
