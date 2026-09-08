$CondaExecutable = "D:\anaconda\Scripts\conda.exe"
$MavenRepository = "D:\anaconda\envs\lingshu-dev\.m2\repository"

& $CondaExecutable run --no-capture-output -n lingshu-dev mvn "-Dmaven.repo.local=$MavenRepository" @args
exit $LASTEXITCODE
