param(
    [ValidateSet("up", "down", "status", "logs", "pull")]
    [string]$Action = "status"
)

$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$DockerPrefix = @()
$DockerCompose = $env:LINGSHU_DOCKER_COMPOSE_EXE
if (-not [string]::IsNullOrWhiteSpace($DockerCompose)) {
    if (-not (Test-Path -LiteralPath $DockerCompose)) {
        throw "LINGSHU_DOCKER_COMPOSE_EXE does not exist: $DockerCompose"
    }
} else {
    $DockerCommand = Get-Command docker -ErrorAction SilentlyContinue
    if ($null -ne $DockerCommand) {
        $PreviousErrorActionPreference = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        $null = & $DockerCommand.Path compose version 2>&1
        $ComposePluginExitCode = $LASTEXITCODE
        $ErrorActionPreference = $PreviousErrorActionPreference
        if ($ComposePluginExitCode -eq 0) {
            $DockerCompose = $DockerCommand.Path
            $DockerPrefix = @("compose")
        }
    }
    if ([string]::IsNullOrWhiteSpace($DockerCompose)) {
        $ComposeCommand = Get-Command docker-compose -ErrorAction SilentlyContinue
        if ($null -eq $ComposeCommand) {
            throw "Docker Compose was not found. Add docker to PATH or set LINGSHU_DOCKER_COMPOSE_EXE."
        }
        $DockerCompose = $ComposeCommand.Path
    }
}
$ComposeArgs = @("--project-directory", $ProjectRoot, "--env-file", (Join-Path $ProjectRoot ".env"), "-f", (Join-Path $ProjectRoot "compose.yaml"))

switch ($Action) {
    "up" {
        & $DockerCompose @DockerPrefix @ComposeArgs up --detach --wait
    }
    "down" {
        & $DockerCompose @DockerPrefix @ComposeArgs down
    }
    "status" {
        & $DockerCompose @DockerPrefix @ComposeArgs ps
    }
    "logs" {
        & $DockerCompose @DockerPrefix @ComposeArgs logs --tail 100
    }
    "pull" {
        & $DockerCompose @DockerPrefix @ComposeArgs pull
    }
}

if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}
