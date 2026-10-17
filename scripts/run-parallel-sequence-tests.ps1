param(
    [switch]$FullSuite,
    [switch]$SkipArchive,
    [switch]$CommitResults,
    [switch]$PushResults
)

$ErrorActionPreference = "Stop"
if ($PushResults -and -not $CommitResults) {
    throw "-PushResults requires -CommitResults."
}
if ($SkipArchive -and ($CommitResults -or $PushResults)) {
    throw "Cannot publish test results when -SkipArchive is set."
}
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
    if (-not $SkipArchive) {
        & (Join-Path $PSScriptRoot "archive-parallel-sequence-tests.ps1") -RunLabel $(if ($FullSuite) { "parallel-fullsuite" } else { "parallel-targeted" }) -CommitResults:$CommitResults -PushResults:$PushResults
    }
} finally {
    Pop-Location
}
