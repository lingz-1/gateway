$env:LINGSHU_CACHE_SEMANTIC_ENABLED = "true"
& (Join-Path $PSScriptRoot "run-core-redis.ps1") @args
exit $LASTEXITCODE
