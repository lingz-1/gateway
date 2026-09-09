$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$Services = @(
    @{ Name = "Gateway"; Port = 8080; Marker = "lingshu-gateway" },
    @{ Name = "Core"; Port = 8081; Marker = "lingshu-core" },
    @{ Name = "Web"; Port = 5173; Marker = "lingshu-web" }
)

foreach ($service in $Services) {
    $connections = Get-NetTCPConnection -State Listen -LocalPort $service.Port -ErrorAction SilentlyContinue
    foreach ($connection in $connections) {
        $processInfo = Get-CimInstance Win32_Process -Filter "ProcessId = $($connection.OwningProcess)"
        if ($null -eq $processInfo) {
            continue
        }
        $commandLine = [string]$processInfo.CommandLine
        if ($commandLine -notlike "*$($service.Marker)*" -and $commandLine -notlike "*$ProjectRoot*") {
            throw "$($service.Name) port $($service.Port) belongs to an unrelated process. It was not stopped."
        }
        Stop-Process -Id $connection.OwningProcess -Force
        Write-Host "Stopped $($service.Name) on port $($service.Port)."
    }
}
