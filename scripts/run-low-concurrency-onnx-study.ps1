param(
    # The -File entrypoint binds CSV strings reliably on Windows PowerShell.
    [ValidatePattern('^[0-9]{1,3}(,[0-9]{1,3})*$')]
    [string]$Callers = "1,2,4,8",
    [ValidateRange(2, 8)][int]$Repetitions = 2,
    [ValidateRange(12, 5000)][int]$Events = 200,
    [ValidateRange(1, 60000)][int]$MaskingDeadlineMillis = 2000,
    [ValidateRange(1, 64)][int]$CpuThreads = 4,
    [ValidateRange(1, 32)][int]$WindowBatchLimit = 4,
    [ValidateRange(1, 10000)][int]$MaskingQueueCapacity = 64,
    [ValidateRange(1, 2147483647)][int]$RandomSeed = 20261017,
    [switch]$UseExampleOrganizationRules,
    [switch]$CommitResults,
    [switch]$PushResults
)

$ErrorActionPreference = "Stop"
if ($PushResults -and -not $CommitResults) {
    throw "-PushResults requires -CommitResults."
}
$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$runner = Join-Path $PSScriptRoot "run-parallel-onnx-benchmark.ps1"
$branchName = "chatgpt/hybrid-detection-pipeline"

$callerCounts = @(
    foreach ($raw in $Callers.Split(',')) {
        [int]$count = 0
        if (-not [int]::TryParse($raw, [ref]$count) -or
                $count -lt 1 -or $count -gt 200 -or
                $count -gt $Events) {
            throw "Invalid caller count (1..min(200,Events)): $raw"
        }
        $count
    }
)
if (@($callerCounts | Select-Object -Unique).Count -ne $callerCounts.Count) {
    throw "Caller counts must not repeat: $Callers"
}

$currentBranch = (& git -C $root branch --show-current).Trim()
if ($LASTEXITCODE -ne 0 -or $currentBranch -ne $branchName) {
    throw "Run the study on branch $branchName, not $currentBranch."
}
$sourceCommit = (& git -C $root rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0) {
    throw "Cannot capture source revision."
}

$startedAt = [DateTime]::UtcNow
$studyId = $startedAt.ToString(
    "yyyyMMddTHHmmssZ",
    [System.Globalization.CultureInfo]::InvariantCulture
) + "-low-caller-seed$RandomSeed-" +
    [guid]::NewGuid().ToString("N").Substring(0, 12)
$studyRelative = "reports/parallel-onnx/studies/$studyId"
$studyDir = Join-Path $root $studyRelative
if (Test-Path -LiteralPath $studyDir) {
    throw "Refusing to overwrite an existing study."
}
New-Item -ItemType Directory -Path $studyDir | Out-Null
$receiptDir = Join-Path $studyDir "receipts"
New-Item -ItemType Directory -Path $receiptDir | Out-Null

# Randomize the order of concurrency levels independently each round.
# For a given level, run paired AB in odd rounds and BA in even ones.
# Record the seed, source SHA and exact plan for reproducibility.
$rng = [System.Random]::new($RandomSeed)
$plan = [System.Collections.Generic.List[object]]::new()
for ($repeat = 1; $repeat -le $Repetitions; $repeat++) {
    $order = @($callerCounts)
    for ($i = $order.Count - 1; $i -gt 0; $i--) {
        $j = $rng.Next($i + 1)
        $tmp = $order[$i]
        $order[$i] = $order[$j]
        $order[$j] = $tmp
    }
    $modes = if (($repeat % 2) -eq 1) {
        @("platform", "virtual")
    } else {
        @("virtual", "platform")
    }
    foreach ($count in $order) {
        foreach ($mode in $modes) {
            $plan.Add([pscustomobject]@{
                index = ($plan.Count + 1)
                repeat = $repeat
                callers = [int]$count
                mode = [string]$mode
            })
        }
    }
}
$planName = "plan-$studyId.json"
$planDoc = [ordered]@{
    schema_version = 1
    study_id = $studyId
    created_at_utc = $startedAt.ToString("o")
    source_commit_sha = $sourceCommit
    synthetic_workload = "200 events by default + four warmup"
    schedule = "seeded Fisher-Yates per round, AB/BA thread mode alternation"
    random_seed = $RandomSeed
    repetitions = $Repetitions
    caller_counts = @($callerCounts)
    event_count = $Events
    deadline_millis = $MaskingDeadlineMillis
    cpu_threads = $CpuThreads
    max_inference_windows_per_batch = $WindowBatchLimit
    masking_queue_capacity = $MaskingQueueCapacity
    synthetic_org_rules_enabled = [bool]$UseExampleOrganizationRules
    plan = @($plan.ToArray())
}
$planDoc | ConvertTo-Json -Depth 10 |
    Set-Content -LiteralPath (Join-Path $studyDir $planName) -Encoding UTF8

function Median([double[]]$values) {
    if ($values.Count -eq 0) {
        throw "Empty numeric group."
    }
    $sorted = @($values | Sort-Object)
    $m = [int][math]::Floor($sorted.Count / 2.0)
    if (($sorted.Count % 2) -eq 1) {
        return [double]$sorted[$m]
    }
    return ([double]$sorted[$m - 1] + [double]$sorted[$m]) / 2.0
}

$trials = [System.Collections.Generic.List[object]]::new()
$evidencePaths = [System.Collections.Generic.List[string]]::new()
Push-Location $root
try {
    Write-Host "Building ONNX benchmark reactor once for $($plan.Count) trials..."
    & mvn "-version"
    if ($LASTEXITCODE -ne 0) {
        throw "Maven unavailable."
    }
    & mvn "-pl" "securelogx-log4j2" "-am" "-DskipTests" "install"
    if ($LASTEXITCODE -ne 0) {
        throw "Maven build failed. No trials executed."
    }

    foreach ($item in $plan) {
        $receiptPath = Join-Path $receiptDir (
            "trial-" + $item.index.ToString("000") + ".json"
        )
        Write-Host ""
        Write-Host ("TRIAL {0}/{1}: round={2}, callers={3}, mode={4}" -f
            $item.index, $plan.Count, $item.repeat, $item.callers, $item.mode)

        $runnerArgs = @{
            Callers = [string]$item.callers
            Events = $Events
            CallerMode = [string]$item.mode
            MaskingDeadlineMillis = $MaskingDeadlineMillis
            CpuThreads = $CpuThreads
            WindowBatchLimit = $WindowBatchLimit
            MaskingQueueCapacity = $MaskingQueueCapacity
            UseExampleOrganizationRules = [bool]$UseExampleOrganizationRules
            RunReceiptPath = $receiptPath
            SkipBuild = $true
        }
        & $runner @runnerArgs
        if (-not (Test-Path -LiteralPath $receiptPath -PathType Leaf)) {
            throw "Missing validated run receipt for trial $($item.index)"
        }
        $receipt = Get-Content -LiteralPath $receiptPath -Raw |
            ConvertFrom-Json
        $relative = [string]$receipt.archive_relative_path
        if ($relative -cnotmatch
                '^reports/parallel-onnx/history/[A-Za-z0-9_.-]+$') {
            throw "Invalid receipt archive path."
        }
        if ($receipt.security_passed -ne $true -or
                [int]$receipt.callers -ne $item.callers -or
                $receipt.mode -cne $item.mode -or
                [int]$receipt.events -ne $Events -or
                $receipt.source_commit_sha -cne $sourceCommit) {
            throw "Run receipt identity/configuration mismatch."
        }

        $runDir = Join-Path $root $relative
        $resultPath = Join-Path $runDir ([string]$receipt.result_file)
        $manifestPath = Join-Path $runDir ([string]$receipt.manifest_file)
        foreach ($file in @($resultPath, $manifestPath)) {
            if (-not (Test-Path -LiteralPath $file -PathType Leaf)) {
                throw "Archived benchmark artifact missing: $file"
            }
        }
        $result = Get-Content -LiteralPath $resultPath -Raw |
            ConvertFrom-Json
        $manifest = Get-Content -LiteralPath $manifestPath -Raw |
            ConvertFrom-Json

        $normalPlusFailed = [int]$result.successful_masked_events +
            [int]$result.fail_closed_events
        $expectedOutput = $Events + [int]$result.warmup_events
        if ($result.security_passed -ne $true -or
                $result.raw_payload_forwarded -ne $false -or
                [int]$result.events -ne $Events -or
                [int]$result.parallel_callers -ne $item.callers -or
                $result.caller_thread_mode -cne $item.mode -or
                [int]$result.masking_deadline_millis -ne
                    $MaskingDeadlineMillis -or
                [int]$result.max_configured_cpu_threads -ne $CpuThreads -or
                [int]$result.max_inference_windows_per_batch -ne
                    $WindowBatchLimit -or
                [int]$result.masking_queue_capacity -ne
                    $MaskingQueueCapacity -or
                $normalPlusFailed -ne $Events -or
                [int]$result.total_protected_records -ne $expectedOutput -or
                $result.protected_sequence_audit.unique_ingress_ids -ne $true -or
                [int]$result.final_owned_contexts -ne 0 -or
                [int]$result.final_owned_maskers -ne 0 -or
                $manifest.source_commit_sha -cne $sourceCommit -or
                $manifest.security_passed -ne $true) {
            throw "Trial $($item.index) failed result/manifest security verification."
        }

        # Recalculate every declared archive file checksum. No raw
        # Surefire XML, model binaries or unrelated workspace files.
        if (@($manifest.files).Count -ne 3) {
            throw "Expected exactly three real-ONNX output files."
        }
        foreach ($artifact in @($manifest.files)) {
            $filename = [string]$artifact.filename
            if ($filename -cnotmatch '^[A-Za-z0-9_.-]+$') {
                throw "Unsafe archive filename in manifest."
            }
            $fullPath = Join-Path $runDir $filename
            if (-not (Test-Path -LiteralPath $fullPath -PathType Leaf)) {
                throw "Archive file missing: $filename"
            }
            $actualHash = (Get-FileHash -LiteralPath $fullPath -Algorithm SHA256).Hash
            if ($actualHash -cne $artifact.sha256) {
                throw "Archive checksum mismatch: $filename"
            }
        }
        $elapsedSeconds = [double]$result.elapsed_millis / 1000.0
        if ($elapsedSeconds -le 0) {
            throw "Benchmark elapsed time is invalid."
        }
        $maskedPerSecond =
            [double]$result.successful_masked_events / $elapsedSeconds
        $trials.Add([pscustomobject]@{
            index = [int]$item.index
            repetition = [int]$item.repeat
            callers = [int]$item.callers
            mode = [string]$item.mode
            run_id = [string]$receipt.run_id
            archive_relative_path = $relative
            security_passed = [bool]$result.security_passed
            functional_passed = [bool]$result.functional_passed
            measured_events = $Events
            protected_records = [int]$result.total_protected_records
            normally_masked_events = [int]$result.successful_masked_events
            fail_closed_events = [int]$result.fail_closed_events
            normal_masking_success_pct = [math]::Round(
                100.0 * $result.successful_masked_events / $Events, 2
            )
            normal_masked_events_per_second = [math]::Round(
                $maskedPerSecond, 3
            )
            all_protected_events_per_second =
                [double]$result.events_per_second
            p50_millis = [double]$result.caller_latency_millis.p50
            p95_millis = [double]$result.caller_latency_millis.p95
            p99_millis = [double]$result.caller_latency_millis.p99
            caller_deadline_exceedances =
                [int]$result.caller_events_over_masking_deadline
            overload_rejected =
                [int]$result.bounded_executor_measured_only.overload_rejected_requests
            deadline_exceeded =
                [int]$result.bounded_executor_measured_only.deadline_exceeded_requests
            peak_queued =
                [int]$result.bounded_executor_measured_only.peak_queued_requests_including_warmup
            real_onnx_calls =
                [int]$result.inference_metrics_measured_only.onnx_inference_calls
            real_onnx_windows =
                [int]$result.inference_metrics_measured_only.ml_inference_windows
            numeric_write_inversions =
                [int]$result.protected_sequence_audit.physical_order_inversions
            unique_ingress_ids =
                [bool]$result.protected_sequence_audit.unique_ingress_ids
            after_workload_working_set_bytes =
                $result.after_workload.process_working_set_bytes
            after_stop_working_set_bytes =
                $result.after_context_stop.process_working_set_bytes
        })
        $evidencePaths.Add($relative)
        Write-Host ("Verified normal={0}/{1}, failClosed={2}, p95={3}ms, normalMasked/s={4}" -f
            $result.successful_masked_events, $Events,
            $result.fail_closed_events,
            $result.caller_latency_millis.p95,
            [math]::Round($maskedPerSecond, 2))
    }

    $groups = [System.Collections.Generic.List[object]]::new()
    foreach ($count in ($callerCounts | Sort-Object)) {
        foreach ($mode in @("platform", "virtual")) {
            $g = @($trials.ToArray() | Where-Object {
                $_.callers -eq $count -and $_.mode -eq $mode
            })
            if ($g.Count -ne $Repetitions) {
                throw "Missing complete group: callers=$count mode=$mode"
            }
            $throughputs = [double[]]@(
                $g | ForEach-Object { $_.normal_masked_events_per_second }
            )
            $successPcts = [double[]]@(
                $g | ForEach-Object { $_.normal_masking_success_pct }
            )
            $p95Samples = [double[]]@(
                $g | ForEach-Object { $_.p95_millis }
            )
            $passed = @($g | Where-Object {
                $_.functional_passed -eq $true
            }).Count
            $groups.Add([pscustomobject]@{
                callers = [int]$count
                mode = $mode
                repetitions = $Repetitions
                security_passes = @($g | Where-Object {
                    $_.security_passed -eq $true
                }).Count
                functional_passes = $passed
                strict_deadline_candidate_all_repetitions =
                    ($passed -eq $Repetitions)
                median_normal_masked_events_per_second =
                    [math]::Round((Median $throughputs), 3)
                minimum_normal_masked_events_per_second =
                    [math]::Round(($throughputs | Measure-Object -Minimum).Minimum, 3)
                median_success_pct = [math]::Round((Median $successPcts), 2)
                median_caller_p95_millis = [math]::Round(
                    (Median $p95Samples), 3
                )
                maximum_caller_p95_millis =
                    [math]::Round(($p95Samples | Measure-Object -Maximum).Maximum, 3)
                total_fail_closed =
                    [int](($g | Measure-Object -Property fail_closed_events -Sum).Sum)
                total_overload_rejected =
                    [int](($g | Measure-Object -Property overload_rejected -Sum).Sum)
                total_deadline_exceeded =
                    [int](($g | Measure-Object -Property deadline_exceeded -Sum).Sum)
                trial_run_ids = @($g | ForEach-Object { $_.run_id })
            })
        }
    }

    $summary = [ordered]@{
        schema_version = 1
        study_id = $studyId
        created_at_utc = [DateTime]::UtcNow.ToString("o")
        source_commit_sha = $sourceCommit
        plan_file = $planName
        status = "SYNTHETIC_SECURITY_VALIDATED_ALL_TRIALS"
        expected_trials = $plan.Count
        validated_trials = $trials.Count
        scheduler = "seeded caller-level shuffle, paired AB/BA thread mode"
        interpretation = @(
            "One bounded ONNX worker; only Log4j2 application caller type varies.",
            "Overall log throughput includes rapid fail-closed events. Normal masking throughput is reported separately.",
            "strict_deadline_candidate_all_repetitions is exploratory, NOT a production SLA or soak test.",
            "Memory samples are endpoints, not peak native RSS.",
            "The synthetic-fixture safety check cannot prove arbitrary user log content safe to publish."
        )
        groups = @($groups.ToArray())
        trials = @($trials.ToArray())
    }
    $summaryPath = Join-Path $studyDir "summary-$studyId.json"
    $summary | ConvertTo-Json -Depth 12 |
        Set-Content -LiteralPath $summaryPath -Encoding UTF8

    Write-Host ""
    Write-Host "LOW-CONCURRENCY REAL ONNX STUDY COMPLETE"
    Write-Host "Security-validated trials: $($trials.Count)/$($plan.Count)"
    foreach ($g in $groups) {
        Write-Host ("callers={0} mode={1} functional={2}/{3} medianNormal/s={4} medianP95ms={5} failClosedTotal={6}" -f
            $g.callers, $g.mode, $g.functional_passes,
            $g.repetitions, $g.median_normal_masked_events_per_second,
            $g.median_caller_p95_millis, $g.total_fail_closed)
    }
    Write-Host "Summary: $summaryPath"

    if ($CommitResults) {
        # Commit only the enumerated validated synthetic archives and the
        # study artifacts. Preserve unrelated staged/unstaged user files.
        $paths = @($studyRelative) + @($evidencePaths.ToArray())
        & git -C $root add -- $paths
        if ($LASTEXITCODE -ne 0) {
            throw "git add failed; study artifacts remain local."
        }
        & git -C $root commit --only -m "Archive paired low-concurrency ONNX study $studyId" -- $paths
        if ($LASTEXITCODE -ne 0) {
            throw "git commit failed; study artifacts remain local."
        }
        Write-Host "Validated low-concurrency evidence committed locally."
        if ($PushResults) {
            & git -C $root push origin "HEAD:refs/heads/chatgpt/hybrid-detection-pipeline"
            if ($LASTEXITCODE -ne 0) {
                throw "git push failed; local study commit remains intact. Retry git push without re-running."
            }
            Write-Host "Validated low-concurrency study pushed to GitHub."
        }
    } else {
        Write-Host "Results archived locally; no Git commit/push was requested."
    }
} finally {
    Pop-Location
}
