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

        $projectProcessIds = [System.Collections.Generic.List[int]]::new()
        $currentProcess = $processInfo
        while ($null -ne $currentProcess -and $currentProcess.ProcessId -ne $PID) {
            $currentCommandLine = [string]$currentProcess.CommandLine
            $isProjectProcess = $currentCommandLine -like "*$($service.Marker)*" `
                -or $currentCommandLine -like "*$ProjectRoot*"
            if ($projectProcessIds.Count -gt 0 -and -not $isProjectProcess) {
                break
            }
            $projectProcessIds.Add([int]$currentProcess.ProcessId)
            if ($currentProcess.ParentProcessId -le 0) {
                break
            }
            $currentProcess = Get-CimInstance Win32_Process `
                -Filter "ProcessId = $($currentProcess.ParentProcessId)" -ErrorAction SilentlyContinue
        }

        Stop-Process -Id $projectProcessIds.ToArray() -Force -ErrorAction SilentlyContinue
        Write-Host "Stopped $($service.Name) on port $($service.Port)."
    }
}
