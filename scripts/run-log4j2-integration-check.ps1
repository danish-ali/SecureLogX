param()

$ErrorActionPreference = "Stop"

Write-Host "Active Maven/JDK:"
& mvn -version
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host ""
Write-Host "Running SecureLogX Log4j2 integration compile/tests..."
& mvn "-pl" "securelogx-log4j2" "-am" "clean" "test"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host ""
Write-Host "SecureLogX Log4j2 integration check passed."
