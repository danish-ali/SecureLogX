param(
    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9_-]{0,39}$')]
    [string]$RunLabel = "parallel-sequence",
    [switch]$CommitResults,
    [switch]$PushResults,
    [switch]$AllowOlderReports
)

$ErrorActionPreference = "Stop"
if ($PushResults -and -not $CommitResults) {
    throw "-PushResults requires -CommitResults."
}

$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$targetDir = Join-Path $root "securelogx-log4j2\target\surefire-reports"
$classes = @(
    "SecureLogXParallelSequenceIntegrationTest",
    "SecureLogXSequenceAuditTest"
)
$expectedPackage = "com.securelogx.log4j2"
$suites = @()
$nowUtc = [DateTime]::UtcNow

foreach ($class in $classes) {
    $filename = "TEST-$expectedPackage.$class.xml"
    $xmlPath = Join-Path $targetDir $filename
    if (-not (Test-Path -LiteralPath $xmlPath -PathType Leaf)) {
        throw "JUnit evidence missing: $xmlPath. Run the parallel test wrapper first."
    }
    $file = Get-Item -LiteralPath $xmlPath
    $ageHours = ($nowUtc - $file.LastWriteTimeUtc).TotalHours
    if (-not $AllowOlderReports -and ($ageHours -lt -0.1 -or $ageHours -gt 24)) {
        throw "Refusing possibly stale JUnit evidence ($filename; age $([math]::Round($ageHours,2)) hours). Re-run tests, or explicitly set -AllowOlderReports."
    }

    # Surefire XML can include JVM system properties, environment details and
    # captured output. Do NOT copy or publish it. Extract only non-sensitive
    # summary fields needed for test evidence.
    [xml]$xml = Get-Content -LiteralPath $xmlPath -Raw
    $suite = $xml.DocumentElement
    if ($null -eq $suite -or $suite.LocalName -ne "testsuite") {
        throw "Unexpected JUnit XML root element: $filename"
    }
    $suiteName = $suite.GetAttribute("name")
    $expectedName = "$expectedPackage.$class"
    if ($suiteName -cne $expectedName) {
        throw "Unexpected test suite name in $filename"
    }

    [int]$tests = 0
    [int]$failures = 0
    [int]$errors = 0
    [int]$skipped = 0
    [double]$seconds = 0
    if (-not [int]::TryParse($suite.GetAttribute("tests"), [ref]$tests) -or
            -not [int]::TryParse($suite.GetAttribute("failures"), [ref]$failures) -or
            -not [int]::TryParse($suite.GetAttribute("errors"), [ref]$errors) -or
            -not [int]::TryParse($suite.GetAttribute("skipped"), [ref]$skipped) -or
            -not [double]::TryParse(
                $suite.GetAttribute("time"),
                [System.Globalization.NumberStyles]::Float,
                [System.Globalization.CultureInfo]::InvariantCulture,
                [ref]$seconds
            )) {
        throw "Invalid JUnit summary counts: $filename"
    }
    if ($tests -le 0 -or $failures -ne 0 -or $errors -ne 0 -or
            $skipped -ne 0) {
        throw "Refusing to mark incomplete, skipped or failing tests as passed: $filename"
    }

    $suites += [ordered]@{
        suite_name = $suiteName
        test_count = $tests
        failures = $failures
        errors = $errors
        skipped = $skipped
        duration_seconds = $seconds
        source_surefire_xml_sha256 = (
            Get-FileHash -LiteralPath $xmlPath -Algorithm SHA256
        ).Hash
        source_last_write_utc = $file.LastWriteTimeUtc.ToString("o")
    }
}

$runId = $nowUtc.ToString(
    "yyyyMMddTHHmmssZ",
    [System.Globalization.CultureInfo]::InvariantCulture
) + "-$RunLabel-" + [guid]::NewGuid().ToString("N").Substring(0, 12)
$relative = "reports/parallel-sequence-tests/history/$runId"
$destination = Join-Path $root "reports\parallel-sequence-tests\history\$runId"
New-Item -ItemType Directory -Path $destination -ErrorAction Stop | Out-Null

$summary = [ordered]@{
    schema_version = 1
    run_id = $runId
    archived_utc = [DateTime]::UtcNow.ToString("o")
    evidence_type = "sanitized_junit_surefire_summary"
    provenance = "Aggregates parsed from local Maven Surefire XML; raw XML intentionally excluded to avoid exposing JVM/system properties, machine paths, credentials or captured log payloads."
    passed = $true
    total_tests = ($suites | Measure-Object -Property test_count -Sum).Sum
    suite_count = $suites.Count
    no_failed_or_skipped_tests = $true
    model_free_test_scope = "Log4j sequence uniqueness, protected destination output, forced out-of-order reconstruction and sequence-audit logic"
    does_not_establish = "Real ONNX parallel request latency, queue saturation, native resource stability or production SLO"
    suites = @($suites)
}
$name = "parallel-sequence-tests-$runId.json"
$filePath = Join-Path $destination $name
$summary | ConvertTo-Json -Depth 8 |
    Set-Content -LiteralPath $filePath -Encoding UTF8
Write-Host "Archived sanitized JUnit outcome: $relative/$name"
Write-Host "Tests: $($summary.total_tests), Suites: $($summary.suite_count), Failed: 0"

if ($CommitResults) {
    $currentBranch = (& git -C $root branch --show-current).Trim()
    if ($LASTEXITCODE -ne 0 -or
            $currentBranch -ne "chatgpt/hybrid-detection-pipeline") {
        throw "Refusing result commit on branch: $currentBranch"
    }
    & git -C $root add -- $relative
    if ($LASTEXITCODE -ne 0) {
        throw "git add failed; sanitized JUnit archive remains locally saved."
    }
    & git -C $root commit --only -m "Archive sanitized parallel sequence tests $runId" -- $relative
    if ($LASTEXITCODE -ne 0) {
        throw "git commit failed; sanitized JUnit archive remains locally saved."
    }
    Write-Host "JUnit evidence committed locally."
    if ($PushResults) {
        & git -C $root push origin "HEAD:refs/heads/chatgpt/hybrid-detection-pipeline"
        if ($LASTEXITCODE -ne 0) {
            throw "git push failed; local archive and commit remain available. Retry git push after resolving connectivity."
        }
        Write-Host "JUnit evidence pushed to GitHub."
    }
} else {
    Write-Host "JUnit evidence archived locally; add -CommitResults -PushResults to publish."
}
