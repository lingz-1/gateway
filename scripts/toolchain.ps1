$script:LingShuCondaEnvironment = "lingshu-dev"

function Resolve-LingShuConda {
    $configured = $env:LINGSHU_CONDA_EXE
    if (-not [string]::IsNullOrWhiteSpace($configured)) {
        if (-not (Test-Path -LiteralPath $configured)) {
            throw "LINGSHU_CONDA_EXE does not exist: $configured"
        }
        return (Resolve-Path -LiteralPath $configured).Path
    }

    $legacyPath = "D:\anaconda\Scripts\conda.exe"
    if (Test-Path -LiteralPath $legacyPath) {
        return $legacyPath
    }

    $command = Get-Command conda -ErrorAction SilentlyContinue
    if ($null -ne $command) {
        if (-not [string]::IsNullOrWhiteSpace($command.Path)) {
            return $command.Path
        }
        return $command.Source
    }

    throw "Conda was not found. Add it to PATH or set LINGSHU_CONDA_EXE."
}

function Resolve-LingShuEnvironmentPrefix {
    param(
        [Parameter(Mandatory = $true)]
        [string]$CondaExecutable
    )

    $output = & $CondaExecutable run --no-capture-output -n $script:LingShuCondaEnvironment `
        python -c "import sys; print(sys.prefix)"
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to resolve the $script:LingShuCondaEnvironment environment prefix."
    }
    $prefix = [string]($output | Select-Object -Last 1)
    if ([string]::IsNullOrWhiteSpace($prefix)) {
        throw "Conda returned an empty environment prefix."
    }
    return $prefix.Trim()
}
