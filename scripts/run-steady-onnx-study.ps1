param(
    [ValidatePattern('^[0-9]{1,2}(,[0-9]{1,2})*$')][string]$Rates = "5,6,7",
    [ValidateRange(1,8)][int]$Repetitions = 2,
    [ValidateRange(20,600)][int]$DurationSeconds = 60,
    [ValidateSet("platform","virtual")][string]$CallerMode = "platform",
    [ValidateRange(1,128)][int]$MaxOutstanding = 32,
    [ValidateRange(1000,30000)][int]$MemorySampleMillis = 5000,
    [ValidateRange(1,60000)][int]$MaskingDeadlineMillis = 2000,
    [ValidateRange(1,64)][int]$CpuThreads = 4,
    [ValidateRange(1,32)][int]$WindowBatchLimit = 4,
    [ValidateRange(1,10000)][int]$MaskingQueueCapacity = 64,
    [ValidateRange(1,2147483647)][int]$RandomSeed = 20261017,
    [switch]$UseExampleOrganizationRules,
    [switch]$KeepLocalDetails,
    # Rebuild a compact report from already finished local results;
    # never reruns Maven or ONNX when recovering.
    [switch]$RecoverLatest,
    [switch]$CommitResults,
    [switch]$PushResults
)
$ErrorActionPreference = "Stop"
if (($DurationSeconds * 1000) -lt (2 * $MemorySampleMillis)) {
    throw "The study requires at least two expected RSS samples per trial. Reduce -MemorySampleMillis."
}
if ($PushResults -and -not $CommitResults) {
    throw "-PushResults requires -CommitResults."
}
$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$branchName = (& git -C $root branch --show-current).Trim()
if ($LASTEXITCODE -ne 0 -or $branchName -ne "chatgpt/hybrid-detection-pipeline") {
    throw "Require branch chatgpt/hybrid-detection-pipeline."
}
$sourceSha = (& git -C $root rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0) { throw "Failed to capture source SHA." }
$rateValues = @(
    foreach ($raw in $Rates.Split(',')) {
        [int]$value = 0
        if (-not [int]::TryParse($raw,[ref]$value) -or $value -lt 1 -or $value -gt 30) {
            throw "Invalid rate $raw, expected 1..30/s."
        }
        $value
    }
)
if (@($rateValues | Select-Object -Unique).Count -ne $rateValues.Count) {
    throw "Rates must be distinct."
}
foreach ($modelArtifact in @(
    (Join-Path $root "onnx-model\ml-v1.3\bert-base-cased\model.onnx"),
    (Join-Path $root "onnx-model\ml-v1.3\bert-base-cased\tokenizer.json")
)) {
    if (-not (Test-Path -LiteralPath $modelArtifact -PathType Leaf)) {
        throw "Real ONNX model artifact missing: $modelArtifact"
    }
}
if ($UseExampleOrganizationRules -and -not (Test-Path -LiteralPath (Join-Path $root "examples\organization-rules.example.json"))) {
    throw "Synthetic organization policy not found."
}

$localRoot = Join-Path $root "reports\parallel-onnx\.local-steady-runs"
if ($RecoverLatest) {
    $candidates = @(Get-ChildItem -LiteralPath $localRoot -Directory -ErrorAction Stop |
        Where-Object { $_.Name -match '^\d{8}T\d{6}Z-steady-[A-Za-z0-9]+$' } |
        Sort-Object LastWriteTimeUtc -Descending)
    if ($candidates.Count -lt 1) {
        throw "No existing local steady-rate study can be recovered."
    }
    $studyId = $candidates[0].Name
    Write-Host "RECOVERY ONLY: $studyId. No Maven or ONNX execution."
} else {
    $studyId = [DateTime]::UtcNow.ToString("yyyyMMddTHHmmssZ") +
        "-steady-" + [guid]::NewGuid().ToString("N").Substring(0,12)
}
$detailDir = Join-Path $localRoot $studyId
$publishedRelative = "reports/parallel-onnx/steady/study-$studyId.json"
$publishedPath = Join-Path $root $publishedRelative
if (Test-Path -LiteralPath $publishedPath) {
    throw "Compact study file already exists; refuse overwrite."
}
if (-not $RecoverLatest) {
    if (Test-Path -LiteralPath $detailDir) {
        throw "Steady study detail directory already exists."
    }
    New-Item -ItemType Directory -Path $detailDir -Force | Out-Null
} elseif (-not (Test-Path -LiteralPath $detailDir -PathType Container)) {
    throw "Recovery detail directory missing."
}
New-Item -ItemType Directory -Path (Split-Path $publishedPath -Parent) -Force | Out-Null

$rng = [System.Random]::new($RandomSeed)
$plan = [System.Collections.Generic.List[object]]::new()
for ($round=1; $round -le $Repetitions; $round++) {
    $levels=@($rateValues)
    for ($i=$levels.Count-1; $i -gt 0; $i--) {
        $j=$rng.Next($i+1)
        $temp=$levels[$i]; $levels[$i]=$levels[$j]; $levels[$j]=$temp
    }
    foreach ($rate in $levels) {
        $plan.Add([pscustomobject]@{ repetition=$round; rate=[int]$rate })
    }
}

$oldJavaOptions = [Environment]::GetEnvironmentVariable("JAVA_TOOL_OPTIONS","Process")
$trials = [System.Collections.Generic.List[object]]::new()
Push-Location $root
try {
    if (-not $RecoverLatest) {
        Write-Host "Testing and compiling once for a rate-controlled real ONNX study."
        & mvn "-pl" "securelogx-log4j2" "-am" "clean" "test"
        if ($LASTEXITCODE -ne 0) { throw "Maven test reactor failed." }
        & mvn "-pl" "securelogx-log4j2" "-am" "-DskipTests" "install"
        if ($LASTEXITCODE -ne 0) { throw "Maven install reactor failed." }
    }

    $javaOptions = @(
        $oldJavaOptions,
        "-Dsecurelogx.maxCpuThreads=$CpuThreads",
        "-Dsecurelogx.model.maxInferenceWindowsPerBatch=$WindowBatchLimit",
        "-Dsecurelogx.runtime.maskingQueueCapacity=$MaskingQueueCapacity",
        "-Dsecurelogx.runtime.maskingDeadlineMillis=$MaskingDeadlineMillis",
        "-Dsecurelogx.adaptive.mlRoutingTelemetryEnabled=false"
    )
    if ($UseExampleOrganizationRules) {
        $javaOptions += @(
            "-Dsecurelogx.adaptive.enabled=true",
            "-Dsecurelogx.organization.id=ORG42",
            "-Dsecurelogx.organization.approvedRulesFile=examples/organization-rules.example.json"
        )
    }
    $env:JAVA_TOOL_OPTIONS = ($javaOptions | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }) -join " "

    $ordinal=0
    foreach ($item in $plan) {
        $ordinal++
        $rate=[int]$item.rate
        $runDir = Join-Path $detailDir ("run-" + $ordinal.ToString("000") + "-r$rate")
        $resultPath = Join-Path $runDir "result.json"
        $logPath = Join-Path $runDir "protected-application.log"
        if ($RecoverLatest) {
            if (-not (Test-Path -LiteralPath $resultPath) -and
                    -not (Test-Path -LiteralPath $logPath)) {
                Write-Host "Unexecuted trial $ordinal at $rate/s; keeping summary partial."
                continue
            }
            Write-Host "Validating existing trial $ordinal/$($plan.Count) at rate=$rate/s."
        } else {
            if (Test-Path -LiteralPath $runDir) {
                throw "Refusing to overwrite an existing trial directory."
            }
            New-Item -ItemType Directory -Path $runDir | Out-Null
            $execArgs = '"' + $resultPath + '" ' + $rate + ' ' + $DurationSeconds + ' ' + $CallerMode + ' ' + $MaxOutstanding + ' ' + $MemorySampleMillis
            Write-Host "STEADY RUN $ordinal/$($plan.Count): rate=$rate/s, duration=$DurationSeconds, mode=$CallerMode"
            & mvn "-pl" "securelogx-log4j2" "-Dexec.mainClass=com.securelogx.log4j2.SecureLogXSteadyArrivalOnnxBenchmark" "-Dexec.args=$execArgs" "org.codehaus.mojo:exec-maven-plugin:3.5.0:java"
            if ($LASTEXITCODE -ne 0) {
                throw "Steady run $ordinal failed. Detailed local evidence retained: $detailDir"
            }
        }
        if (-not (Test-Path -LiteralPath $resultPath -PathType Leaf) -or -not (Test-Path -LiteralPath $logPath -PathType Leaf)) {
            throw "Missing original result or protected log at run $ordinal."
        }
        $result = Get-Content -LiteralPath $resultPath -Raw | ConvertFrom-Json
        $expected = $DurationSeconds * $rate
        if ($result.benchmark_kind -cne "real-onnx-steady-arrival-log4j2" -or
            $result.security_passed -ne $true -or
            $result.raw_payload_forwarded -ne $false -or
            [int]$result.generator_rejections -ne 0 -or
            [int]$result.offered_rate_per_second -ne $rate -or
            [int]$result.duration_seconds -ne $DurationSeconds -or
            $result.caller_mode -cne $CallerMode -or
            [int]$result.max_outstanding_callers -ne $MaxOutstanding -or
            [int]$result.masking_deadline_millis -ne $MaskingDeadlineMillis -or
            [int]$result.max_configured_cpu_threads -ne $CpuThreads -or
            [int]$result.max_inference_windows_per_batch -ne $WindowBatchLimit -or
            [int]$result.masking_queue_capacity -ne $MaskingQueueCapacity -or
            [int]$result.planned_events -ne $expected -or
            [int]$result.completed_callers -ne $expected -or
            [double]$result.observed_dispatched_rate_per_second -le 0 -or
            [int]$result.memory_sample_count -lt 2 -or
            [int64]$result.sampled_peak_working_set_bytes -le 0 -or
            ([int]$result.successful_masked_events + [int]$result.fail_closed_events) -ne $expected -or
            [int]$result.total_protected_records -ne ($expected + [int]$result.warmup_events) -or
            $result.protected_sequence_audit.unique_ingress_ids -ne $true -or
            [int]$result.final_owned_contexts -ne 0 -or
            [int]$result.final_owned_maskers -ne 0 -or
            [int]$result.real_inference_measured_only.onnx_inference_calls -le 0) {
            throw "Run $ordinal failed synthetic completeness or safety gate. Local evidence retained."
        }
        $safeLog = [System.IO.File]::ReadAllText($logPath)
        foreach ($raw in @("123-45-6789","jane.doe@example.com","4111111111111111","TEST_API_KEY_1234567890","eyJTEST0.TESTPAYLOAD.TESTSIGNATURE","9876543210","7045551212")) {
            if ($safeLog.Contains($raw)) { throw "Synthetic secret leakage detected. Do not publish." }
        }
        $physicalCount = @($safeLog -split '\r?\n' | Where-Object { $_ -like 'eventId=*' }).Count
        if ($physicalCount -ne [int]$result.total_protected_records) {
            throw "Physical log count mismatch in run $ordinal."
        }

        $trials.Add([pscustomobject]@{
            run=$ordinal; repetition=[int]$item.repetition
            offered_rate_per_second=$rate; mode=$CallerMode
            events=$expected; normal_masked=[int]$result.successful_masked_events
            fail_closed=[int]$result.fail_closed_events
            fail_closed_reasons=$result.fail_closed_reason_counts
            security_passed=$true
            rate_fidelity_passed=[bool]$result.rate_fidelity_passed
            functional_passed=[bool]$result.functional_passed
            observed_dispatch_rate=[double]$result.observed_dispatched_rate_per_second
            protected_completion_rate=[double]$result.protected_completions_per_second
            producer_dispatch_lag_p95_ms=[double]$result.producer_dispatch_lag_millis.p95
            normal_masked_per_second=[double]$result.normal_masked_events_per_second
            caller_p95_ms=[double]$result.caller_latency_millis.p95
            caller_p99_ms=[double]$result.caller_latency_millis.p99
            caller_max_ms=[double]$result.caller_latency_millis.max
            scheduler_start_lag_p95_ms=[double]$result.caller_start_lag_millis.p95
            queue_wait_avg_ms=[double]$result.queue_wait.average_millis
            queue_wait_max_ms_including_warmup=[double]$result.queue_wait.max_millis_including_warmup
            # Older recovery source did not measure this separately.
            queue_wait_max_ms_measured_only=$(if ($null -ne $result.queue_wait.max_millis_measured_only) {
                [double]$result.queue_wait.max_millis_measured_only
            } else { $null })
            benign_version_preserved=$(if ($null -ne $result.benign_version_preserved) {
                [bool]$result.benign_version_preserved
            } else { $null })
            benign_control_success_count=$(if ($null -ne $result.benign_control_success_count) {
                [int]$result.benign_control_success_count
            } else { $null })
            queue_wait_buckets=$result.queue_wait.mutually_exclusive_bucket_counts
            queue_wait_upper_bounds_ms=$result.queue_wait.upper_bounds_millis
            worker_started=[int]$result.queue_wait.worker_started_requests
            peak_masking_queue=[int]$result.bounded_executor_measured_only.peak_queued_requests_including_warmup
            deadline_rejections=[int]$result.bounded_executor_measured_only.deadline_exceeded_requests
            overload_rejections=[int]$result.bounded_executor_measured_only.overload_rejected_requests
            native_onnx_calls=[int]$result.real_inference_measured_only.onnx_inference_calls
            native_windows=[int]$result.real_inference_measured_only.ml_inference_windows
            physical_order_inversions=[int]$result.protected_sequence_audit.physical_order_inversions
            rss_peak_sampled_bytes=$result.sampled_peak_working_set_bytes
            private_peak_sampled_bytes=$result.sampled_peak_private_bytes
            rss_samples=[int]$result.memory_sample_count
            sampling_errors=[int]$result.memory_sample_errors
            gc_collections=[int]$result.jvm_gc_collection_count_delta
            gc_time_millis=[int]$result.jvm_gc_collection_millis_delta
            local_result_sha256=(Get-FileHash -LiteralPath $resultPath -Algorithm SHA256).Hash
            local_protected_log_sha256=(Get-FileHash -LiteralPath $logPath -Algorithm SHA256).Hash
        })
        Write-Host ("Validated rate={0}, normal={1}, failClosed={2}, p95={3}ms, queueAvg={4}ms" -f $rate,$result.successful_masked_events,$result.fail_closed_events,$result.caller_latency_millis.p95,$result.queue_wait.average_millis)
    }

    $groups = @(
        foreach ($rate in ($rateValues | Sort-Object)) {
            $g = @($trials.ToArray() | Where-Object {
                $_.offered_rate_per_second -eq $rate
            })
            if ($g.Count -eq 0) {
                [pscustomobject]@{
                    rate_per_second=[int]$rate; trials=0
                    functional_passes=0; fail_closed_total=0
                    min_normal_masked_per_second=$null
                    worst_p99_millis=$null
                    worst_queue_wait_avg_millis=$null
                }
                continue
            }
            $failures = 0
            foreach ($trial in $g) {
                $failures += [int]$trial.fail_closed
            }
            $normalRates = @($g | ForEach-Object {
                [double]$_.normal_masked_per_second
            })
            $p99Values = @($g | ForEach-Object {
                [double]$_.caller_p99_ms
            })
            $queueWaits = @($g | ForEach-Object {
                [double]$_.queue_wait_avg_ms
            })
            [pscustomobject]@{
                rate_per_second=[int]$rate; trials=$g.Count
                functional_passes=@($g | Where-Object {
                    $_.functional_passed
                }).Count
                fail_closed_total=$failures
                min_normal_masked_per_second=[math]::Round(
                    [double]($normalRates | Measure-Object -Minimum).Minimum, 3)
                worst_p99_millis=[math]::Round(
                    [double]($p99Values | Measure-Object -Maximum).Maximum, 3)
                worst_queue_wait_avg_millis=[math]::Round(
                    [double]($queueWaits | Measure-Object -Maximum).Maximum, 3)
            }
        }
    )

    $completeStudy = ($trials.Count -eq $plan.Count)
    if ($trials.Count -eq 0) {
        throw "No local complete benchmark records were recovered."
    }
    $compact = [ordered]@{
        schema_version=2; kind="compact-real-onnx-steady-arrival-study"
        study_id=$studyId; created_at_utc=[DateTime]::UtcNow.ToString("o")
        # Old raw reports did not capture the Git SHA, do not invent it.
        source_commit_sha=$(if ($RecoverLatest) { $null } else { $sourceSha })
        aggregation_commit_sha=$sourceSha
        recovery_from_existing_local_evidence=[bool]$RecoverLatest
        status=$(if ($completeStudy) { "COMPLETE_VERIFIED" } else { "PARTIAL_VERIFIED" })
        expected_trials=$plan.Count
        validated_trials=$trials.Count
        synthetic_only=$true
        rate_plan=@($plan.ToArray()); random_seed=$RandomSeed
        repetitions=$Repetitions; duration_seconds=$DurationSeconds
        caller_mode=$CallerMode; max_outstanding=$MaxOutstanding
        memory_sample_interval_millis=$MemorySampleMillis
        deadline_millis=$MaskingDeadlineMillis; cpu_threads=$CpuThreads
        max_windows_per_batch=$WindowBatchLimit
        masking_queue_capacity=$MaskingQueueCapacity; native_worker_count=1
        security_validated_all_runs=$completeStudy
        groups=$groups; trials=@($trials.ToArray())
        provenance="Original protected synthetic logs and full sample timelines are stored only in an ignored local ZIP; each published row has their SHA-256 hashes."
        limitations="Memory peaks are periodic sampled maxima; rate studies are exploratory and not an extended soak or a production SLA."
    }
    # Publication occurs only AFTER local ZIP checksums succeed, so a
    # failed archive never leaves a misleading review-ready JSON.
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zipPath = Join-Path $localRoot "detail-$studyId.zip"
    if (Test-Path -LiteralPath $zipPath) { throw "Refusing local ZIP overwrite." }
    [System.IO.Compression.ZipFile]::CreateFromDirectory($detailDir,$zipPath,[System.IO.Compression.CompressionLevel]::Optimal,$false)
    $archive = [System.IO.Compression.ZipFile]::OpenRead($zipPath)
    try {
        if ($archive.Entries.Count -ne ($trials.Count * 2)) {
            throw "Local ZIP count validation failed; original directories remain."
        }
        $digests = @{}
        foreach ($trial in $trials) {
            $name = "run-" + ([int]$trial.run).ToString("000") + "-r" + $trial.offered_rate_per_second
            $digests["$name/result.json"] = [string]$trial.local_result_sha256
            $digests["$name/protected-application.log"] = [string]$trial.local_protected_log_sha256
        }
        $hasher = [System.Security.Cryptography.SHA256]::Create()
        try {
            foreach ($entry in $archive.Entries) {
                $name = $entry.FullName.Replace([char]92, '/')
                if ($entry.Length -le 0 -or -not $digests.ContainsKey($name)) {
                    throw "Unknown/empty ZIP evidence entry: $name"
                }
                $stream = $entry.Open()
                try {
                    $zipSha = [System.BitConverter]::ToString(
                        $hasher.ComputeHash($stream)
                    ).Replace("-", "")
                } finally {
                    $stream.Dispose()
                }
                if ($zipSha -cne $digests[$name]) {
                    throw "Local ZIP SHA-256 mismatch: $name; retain original folders."
                }
            }
        } finally {
            $hasher.Dispose()
        }
    } finally { $archive.Dispose() }
    # Do not discard partial local runs: they may be needed for further
    # inspection. Complete studies can safely retain the validated ZIP only.
    if (-not $KeepLocalDetails -and $completeStudy) {
        Remove-Item -LiteralPath $detailDir -Recurse -Force
    }
    $compact | ConvertTo-Json -Depth 15 |
        Set-Content -LiteralPath $publishedPath -Encoding UTF8

    Write-Host "STEADY ONNX $($compact.status): $($trials.Count)/$($plan.Count) individually safety-validated runs."
    foreach ($g in $groups) {
        Write-Host ("Rate {0}/s: functional {1}/{2}, failClosed={3}, minNormal/s={4}, maxP99={5}ms" -f $g.rate_per_second,$g.functional_passes,$g.trials,$g.fail_closed_total,$g.min_normal_masked_per_second,$g.worst_p99_millis)
    }
    Write-Host "One compact Git report: $publishedPath"
    Write-Host "Local ignored detailed ZIP: $zipPath"
    if ($CommitResults) {
        & git -C $root add -- $publishedRelative
        if ($LASTEXITCODE -ne 0) { throw "Git add failed; compact report remains local." }
        & git -C $root commit --only -m "Archive compact steady ONNX study $studyId" -- $publishedRelative
        if ($LASTEXITCODE -ne 0) { throw "Git commit failed; compact report remains local." }
        if ($PushResults) {
            & git -C $root push origin "HEAD:refs/heads/chatgpt/hybrid-detection-pipeline"
            if ($LASTEXITCODE -ne 0) {
                throw "Git push failed; retry just git push, not a repeat of the benchmark."
            }
            Write-Host "ONE compact steady ONNX evidence report pushed to GitHub."
        }
    }
} finally {
    if ($null -eq $oldJavaOptions) {
        Remove-Item Env:\JAVA_TOOL_OPTIONS -ErrorAction SilentlyContinue
    } else {
        $env:JAVA_TOOL_OPTIONS=$oldJavaOptions
    }
    Pop-Location
}
