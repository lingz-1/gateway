param(
    [ValidateSet("up", "down", "status", "logs", "pull")]
    [string]$Action = "status"
)

$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$DockerCompose = "D:\Docker\resources\bin\docker-compose.exe"
if (-not (Test-Path -LiteralPath $DockerCompose)) {
    throw "Docker Compose was not found at $DockerCompose."
}
$ComposeArgs = @("--project-directory", $ProjectRoot, "--env-file", (Join-Path $ProjectRoot ".env"), "-f", (Join-Path $ProjectRoot "compose.yaml"))

switch ($Action) {
    "up" {
        & $DockerCompose @ComposeArgs up --detach --wait
    }
    "down" {
        & $DockerCompose @ComposeArgs down
    }
    "status" {
        & $DockerCompose @ComposeArgs ps
    }
    "logs" {
        & $DockerCompose @ComposeArgs logs --tail 100
    }
    "pull" {
        & $DockerCompose @ComposeArgs pull
    }
}

if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}
