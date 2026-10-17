param(
    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9_-]{0,39}$')]
    [string]$RunLabel = "application",
    [switch]$IncludeExistingReports,
    [switch]$CommitResults,
    [switch]$PushResults
)

$ErrorActionPreference = "Stop"
if ($PushResults -and -not $CommitResults) {
    throw "-PushResults requires -CommitResults."
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$reportsDir = Join-Path $repoRoot "reports\application-log-benchmark"
$resultPath = Join-Path $reportsDir "result.json"
$protectedPath = Join-Path $reportsDir "protected-application.log"
$reconstructedPath = Join-Path $reportsDir "reconstructed-application.log"

if (-not (Test-Path -LiteralPath $resultPath -PathType Leaf)) {
    throw "No current application benchmark result.json exists."
}
if (-not (Test-Path -LiteralPath $protectedPath -PathType Leaf)) {
    throw "No protected synthetic benchmark log exists."
}

function Read-SafeSyntheticReport([string]$path) {
    $report = Get-Content -LiteralPath $path -Raw -ErrorAction Stop |
        ConvertFrom-Json -ErrorAction Stop

    if ($report.passed -ne $true -or
            $report.raw_payload_forwarded -ne $false -or
            [long]$report.fail_closed_events -ne 0 -or
            [long]$report.events -lt 1) {
        throw "Refusing to archive a report that lacks successful synthetic safety gates: $path"
    }
    if ($null -ne $report.protected_sequence_audit -and
            $report.protected_sequence_audit.unique_ingress_ids -ne $true) {
        throw "Sequence audit failed in synthetic result: $path"
    }
    return $report
}

$report = Read-SafeSyntheticReport $resultPath
$protectedText = [System.IO.File]::ReadAllText($protectedPath)

# Match the exact synthetic RAW_SENSITIVE_VALUES used by the Java benchmark.
# Passing this check does not establish real-user production-log safety.
$rawSyntheticFixtures = @(
    "123-45-6789",
    "jane.doe@example.com",
    "4111111111111111",
    "TEST_API_KEY_1234567890",
    "eyJTEST0.TESTPAYLOAD.TESTSIGNATURE",
    "9876543210",
    "7045551212"
)
foreach ($fixture in $rawSyntheticFixtures) {
    if ($protectedText.Contains($fixture)) {
        throw "Refusing to archive: a synthetic raw fixture is present in protected output."
    }
}
if (-not $protectedText.Contains("eventId=")) {
    throw "Protected benchmark log contains no recognizable benchmark events."
}

# Guard against pairing an older protected log with a newer report.
$protectedEvents = @(
    ($protectedText -split '\r?\n') |
        Where-Object { $_ -like 'eventId=*' }
)
$warmupCount = if ($null -ne $report.warmup_events) {
    [int]$report.warmup_events
} else {
    0
}
$expectedProtectedEvents = [int]$report.events + $warmupCount
if ($protectedEvents.Count -ne $expectedProtectedEvents) {
    throw "Protected record count does not match result.json (expected $expectedProtectedEvents, got $($protectedEvents.Count))."
}
if ($null -ne $report.protected_sequence_audit -and
        [int]$report.protected_sequence_audit.records_including_warmup -ne $protectedEvents.Count) {
    throw "Protected log record count disagrees with its recorded sequence audit."
}

$items = [System.Collections.Generic.List[object]]::new()
$items.Add([pscustomobject]@{
    Role = "measured_result"
    Source = $resultPath
    Name = "result"
    Extension = ".json"
})
$items.Add([pscustomobject]@{
    Role = "protected_synthetic_log"
    Source = $protectedPath
    Name = "protected-application"
    Extension = ".log"
})

if (Test-Path -LiteralPath $reconstructedPath -PathType Leaf) {
    $reconstructedText = [System.IO.File]::ReadAllText($reconstructedPath)
    foreach ($fixture in $rawSyntheticFixtures) {
        if ($reconstructedText.Contains($fixture)) {
            throw "Refusing to archive: a synthetic raw fixture is present in reconstructed output."
        }
    }
    # Reconstruction may be from a previous run. Require the same protected
    # event identities, count, and content before including it in this run.
    $protectedLines = @($protectedText -split '\r?\n' |
        Where-Object { $_ -like 'eventId=*' })
    $reconstructedLines = @($reconstructedText -split '\r?\n' |
        Where-Object { $_ -like 'eventId=*' })
    $consistent = $protectedLines.Count -eq $reconstructedLines.Count
    if ($consistent) {
        $protectedSorted = @($protectedLines | Sort-Object)
        $reconstructedSorted = @($reconstructedLines | Sort-Object)
        for ($i = 0; $i -lt $protectedSorted.Count; $i++) {
            if ($protectedSorted[$i] -cne $reconstructedSorted[$i]) {
                $consistent = $false
                break
            }
        }
    }
    if ($consistent) {
        $items.Add([pscustomobject]@{
            Role = "reconstructed_synthetic_log"
            Source = $reconstructedPath
            Name = "reconstructed-application"
            Extension = ".log"
        })
    } else {
        Write-Warning "Reconstructed log does not correspond to the latest benchmark run; not copying stale data."
    }
}

if ($IncludeExistingReports) {
    # Bring in earlier manually saved comparisons, preserving their original
    # names and recording that they are HISTORICAL, not the current run.
    foreach ($file in @(Get-ChildItem -LiteralPath $reportsDir -File -Filter "*.json")) {
        if ($file.Name -eq "result.json") {
            continue
        }
        try {
            $null = Read-SafeSyntheticReport $file.FullName
            $safeStem = [regex]::Replace(
                [System.IO.Path]::GetFileNameWithoutExtension($file.Name),
                '[^A-Za-z0-9_-]', '-'
            )
            $items.Add([pscustomobject]@{
                Role = "historical_comparison"
                Source = $file.FullName
                Name = "historical-$safeStem"
                Extension = ".json"
            })
        } catch {
            Write-Warning "Skipping historical report without verified synthetic safety gates: $($file.Name)"
        }
    }
}

$timestamp = [DateTime]::UtcNow.ToString(
    "yyyyMMddTHHmmssZ",
    [System.Globalization.CultureInfo]::InvariantCulture
)
$idSuffix = [guid]::NewGuid().ToString("N").Substring(0, 12)
$runId = "$timestamp-$RunLabel-$idSuffix"
$archiveRelative = "reports/application-log-benchmark/history/$runId"
$archiveDir = Join-Path $repoRoot (
    "reports\application-log-benchmark\history\" + $runId
)
New-Item -ItemType Directory -Path $archiveDir -ErrorAction Stop |
    Out-Null

$fileEntries = [System.Collections.Generic.List[object]]::new()
foreach ($item in $items) {
    $archivedName = "$($item.Name)-$runId$($item.Extension)"
    $destination = Join-Path $archiveDir $archivedName
    if (Test-Path -LiteralPath $destination) {
        throw "Refusing to overwrite an existing archived result: $destination"
    }
    Copy-Item -LiteralPath $item.Source -Destination $destination -ErrorAction Stop
    $hash = (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash
    $fileEntries.Add([pscustomobject]@{
        role = $item.Role
        archived_file = $archivedName
        source_file = [System.IO.Path]::GetFileName($item.Source)
        sha256 = $hash
        bytes = (Get-Item -LiteralPath $destination).Length
    })
}

$manifestName = "manifest-$runId.json"
$manifest = [ordered]@{
    schema_version = 1
    created_at_utc = [DateTime]::UtcNow.ToString("o")
    run_id = $runId
    label = $RunLabel
    application_benchmark_passed = $true
    synthetic_raw_fixture_check_passed = $true
    events = [int]$report.events
    warmup_events = $report.warmup_events
    fail_closed_events = [int]$report.fail_closed_events
    masking_deadline_millis = $report.masking_deadline_millis
    max_configured_cpu_threads = $report.max_configured_cpu_threads
    max_inference_windows_per_batch = $report.max_inference_windows_per_batch
    historical_reports_are_separate_runs = $true
    files = @($fileEntries.ToArray())
}
$manifestPath = Join-Path $archiveDir $manifestName
$manifest | ConvertTo-Json -Depth 10 |
    Set-Content -LiteralPath $manifestPath -Encoding UTF8

Write-Host "Archived $($fileEntries.Count) benchmark artifact(s)."
Write-Host "Archive: $archiveRelative"
Write-Host "Manifest: $manifestName"

if ($CommitResults) {
    # Never commit unreviewed production logs. This script is specifically
    # restricted to the known synthetic SecureLogX application benchmark.
    $branch = (& git -C $repoRoot branch --show-current).Trim()
    if ($LASTEXITCODE -ne 0 -or
            $branch -ne "chatgpt/hybrid-detection-pipeline") {
        throw "Result commits require branch chatgpt/hybrid-detection-pipeline; current branch: $branch"
    }

    & git -C $repoRoot add -- $archiveRelative
    if ($LASTEXITCODE -ne 0) {
        throw "git add failed; archive remains on disk."
    }

    # --only commits the new archive paths and leaves any unrelated staged
    # developer changes untouched.
    & git -C $repoRoot commit --only -m "Archive synthetic application benchmark $runId" -- $archiveRelative
    if ($LASTEXITCODE -ne 0) {
        throw "git commit failed; archive remains on disk."
    }

    Write-Host "Local Git commit created for: $archiveRelative"
    if ($PushResults) {
        & git -C $repoRoot push origin "HEAD:refs/heads/chatgpt/hybrid-detection-pipeline"
        if ($LASTEXITCODE -ne 0) {
            throw "git push failed; local archive and commit remain available."
        }
        Write-Host "Archived synthetic results pushed to GitHub."
    }
} else {
    Write-Host "Archive is local only. Use -CommitResults (and -PushResults) after review to publish it."
}
