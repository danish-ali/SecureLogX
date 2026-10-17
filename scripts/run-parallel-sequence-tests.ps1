param(
    [switch]$FullSuite
)

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path

Push-Location $repoRoot
try {
    Write-Host "Active Maven / JDK:"
    & mvn "-version"
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to run Maven."
    }

    if ($FullSuite) {
        & mvn "-pl" "securelogx-log4j2" "-am" "clean" "test"
    } else {
        # Always quote -D arguments for Windows PowerShell / Maven.
        # -am includes securelogx-core, which may have no matching tests.
        & mvn "-pl" "securelogx-log4j2" "-am" "-Dtest=SecureLogXParallelSequenceIntegrationTest,SecureLogXSequenceAuditTest" "-Dsurefire.failIfNoSpecifiedTests=false" "test"
    }
    if ($LASTEXITCODE -ne 0) {
        throw "Parallel sequencing Maven tests failed (exit code $LASTEXITCODE)."
    }
    Write-Host "Parallel sequencing Maven tests passed."
} finally {
    Pop-Location
}
