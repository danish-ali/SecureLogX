param(
    [Parameter(Mandatory=$true)][string]$BaselineStudy,
    [Parameter(Mandatory=$true)][string]$PreQueueStudy,
    [string]$OutputPath
)

$ErrorActionPreference = "Stop"

function Read-Study([string]$name) {
    $resolved = (Resolve-Path -LiteralPath $name -ErrorAction Stop).Path
    $doc = Get-Content -LiteralPath $resolved -Raw | ConvertFrom-Json
    if ($doc.kind -cne "compact-real-onnx-steady-arrival-study" -or
        $doc.status -cne "COMPLETE_VERIFIED" -or
        $doc.security_validated_all_runs -ne $true -or
        [int]$doc.validated_trials -ne [int]$doc.expected_trials -or
        @($doc.trials).Count -ne [int]$doc.expected_trials) {
        throw "Not a complete, synthetic-security-validated steady ONNX study: $resolved"
    }
    return $doc
}
function Require([bool]$condition, [string]$message) {
    if (-not $condition) { throw $message }
}
function Sum-Field([object[]]$rows, [string]$field) {
    $sum = 0.0
    foreach ($item in $rows) {
        if ($null -eq $item.$field) { throw "Missing trial metric: $field" }
        $sum += [double]$item.$field
    }
    return $sum
}
function Max-Field([object[]]$rows, [string]$field) {
    $found = $false
    $max = [double]::NegativeInfinity
    foreach ($item in $rows) {
        if ($null -eq $item.$field) { throw "Missing trial metric: $field" }
        $max = [math]::Max($max, [double]$item.$field)
        $found = $true
    }
    if (-not $found) { throw "Empty trial group" }
    return $max
}

$off = Read-Study $BaselineStudy
$on = Read-Study $PreQueueStudy
Require ([bool]$off.experimental_pre_queue_enabled -eq $false) "Baseline study must use pre-queue OFF."
Require ([bool]$on.experimental_pre_queue_enabled -eq $true) "Experimental study must use pre-queue ON."
$keys = @(
    "source_commit_sha", "configured_model_sha256",
    "configured_tokenizer_sha256", "repetitions", "duration_seconds",
    "caller_mode", "max_outstanding", "memory_sample_interval_millis",
    "deadline_millis", "cpu_threads", "max_windows_per_batch",
    "masking_queue_capacity", "random_seed"
)
foreach ($key in $keys) {
    Require ($null -ne $off.$key -and $null -ne $on.$key) "Missing $key; comparison requires original complete studies."
    Require ([string]$off.$key -ceq [string]$on.$key) "Cannot compare studies with different $key."
}
Require ([string]$off.configured_model_sha256 -match '^[A-Fa-f0-9]{64}$') "Missing usable model SHA-256."
Require ([string]$off.configured_tokenizer_sha256 -match '^[A-Fa-f0-9]{64}$') "Missing usable tokenizer SHA-256."

$offPlan = @($off.rate_plan)
$onPlan = @($on.rate_plan)
Require ($offPlan.Count -eq $onPlan.Count) "Mismatched rate-plan lengths."
for ($i=0; $i -lt $offPlan.Count; $i++) {
    Require ([int]$offPlan[$i].rate -eq [int]$onPlan[$i].rate -and
        [int]$offPlan[$i].repetition -eq [int]$onPlan[$i].repetition) "Mismatched run plan at trial $($i + 1)."
}
$offTrials = @($off.trials)
$onTrials = @($on.trials)
for ($i=0; $i -lt $offTrials.Count; $i++) {
    $a=$offTrials[$i]; $b=$onTrials[$i]
    Require ([int]$a.run -eq [int]$b.run -and
        [int]$a.repetition -eq [int]$b.repetition -and
        [int]$a.offered_rate_per_second -eq [int]$b.offered_rate_per_second -and
        [int]$a.events -eq [int]$b.events) "Trial $($i + 1) identity and expected synthetic load differ."
    Require ($a.security_passed -eq $true -and $b.security_passed -eq $true) "Trial $($i+1) failed synthetic confidentiality/sequence acceptance."
    Require ($a.pre_queue_enabled -eq $false -and $b.pre_queue_enabled -eq $true) "Trial $($i+1) does not match OFF/ON routing status."
    Require ([string]$a.configured_model_sha256 -ceq [string]$off.configured_model_sha256 -and
        [string]$b.configured_model_sha256 -ceq [string]$on.configured_model_sha256 -and
        [string]$a.configured_tokenizer_sha256 -ceq [string]$off.configured_tokenizer_sha256 -and
        [string]$b.configured_tokenizer_sha256 -ceq [string]$on.configured_tokenizer_sha256) "Inconsistent model/tokenizer hash within trial $($i+1)."
}

$rates = @($offPlan | ForEach-Object { [int]$_.rate } | Sort-Object -Unique)
$rows = [System.Collections.Generic.List[object]]::new()
foreach ($rate in $rates) {
    $a = @($offTrials | Where-Object { $_.offered_rate_per_second -eq $rate })
    $b = @($onTrials | Where-Object { $_.offered_rate_per_second -eq $rate })
    Require ($a.Count -eq [int]$off.repetitions -and $b.Count -eq $a.Count) "Missing repeated trials at rate $rate/s."
    $baselineNormal = Sum-Field $a "normal_masked"
    $enabledNormal = Sum-Field $b "normal_masked"
    $baselineTotal = Sum-Field $a "events"
    $enabledTotal = Sum-Field $b "events"
    Require ($baselineTotal -eq $enabledTotal) "Mismatched offered events at rate $rate/s."
    $baselineFail = Sum-Field $a "fail_closed"
    $enabledFail = Sum-Field $b "fail_closed"
    $baselineFunctional = @($a | Where-Object { $_.functional_passed -eq $true }).Count
    $enabledFunctional = @($b | Where-Object { $_.functional_passed -eq $true }).Count

    $rows.Add([pscustomobject]@{
        rate_per_second = $rate
        trials_per_mode = $a.Count
        offered_events_per_mode = [int]$baselineTotal
        baseline_success_count = [int]$baselineNormal
        enabled_success_count = [int]$enabledNormal
        baseline_fail_closed = [int]$baselineFail
        enabled_fail_closed = [int]$enabledFail
        baseline_functional_passes = $baselineFunctional
        enabled_functional_passes = $enabledFunctional
        baseline_mean_normal_masked_per_second = [math]::Round(
            (Sum-Field $a "normal_masked_per_second") / $a.Count, 3)
        enabled_mean_normal_masked_per_second = [math]::Round(
            (Sum-Field $b "normal_masked_per_second") / $b.Count, 3)
        baseline_worst_p99_ms = [math]::Round((Max-Field $a "caller_p99_ms"), 3)
        enabled_worst_p99_ms = [math]::Round((Max-Field $b "caller_p99_ms"), 3)
        baseline_worst_max_latency_ms = [math]::Round((Max-Field $a "caller_max_ms"), 3)
        enabled_worst_max_latency_ms = [math]::Round((Max-Field $b "caller_max_ms"), 3)
        baseline_mean_queue_wait_ms = [math]::Round(
            (Sum-Field $a "queue_wait_avg_ms") / $a.Count, 3)
        enabled_mean_queue_wait_ms = [math]::Round(
            (Sum-Field $b "queue_wait_avg_ms") / $b.Count, 3)
        baseline_onnx_calls = [int](Sum-Field $a "native_onnx_calls")
        enabled_onnx_calls = [int](Sum-Field $b "native_onnx_calls")
        baseline_onnx_windows = [int](Sum-Field $a "native_windows")
        enabled_onnx_windows = [int](Sum-Field $b "native_windows")
        pre_queue_deterministic_fields = [int](Sum-Field $b "pre_queue_deterministic_fields")
        pre_queue_escalated_fields = [int](Sum-Field $b "pre_queue_escalated_fields")
        safe_fixture_checks_reported_both = $true
        exact_output_parity_validated = $false
        frozen_high_risk_recall_validated = $false
    })
}

$comparison = [ordered]@{
    schema_version = 1
    experiment = "steady-onnx-prequeue-deterministic-opt-in-ab"
    baseline_study_id = $off.study_id
    pre_queue_study_id = $on.study_id
    same_code_revision = $true
    source_commit_sha = $off.source_commit_sha
    same_model_and_tokenizer_hash = $true
    configured_model_sha256 = $off.configured_model_sha256
    configured_tokenizer_sha256 = $off.configured_tokenizer_sha256
    comparable_workloads = $true
    rows = @($rows.ToArray())
    assessment = "SYNTHETIC_METRIC_COMPARISON_ONLY"
    release_gate = "OPEN: requires protected-output bitwise/semantic parity on local ZIP fixtures and locked high-risk corpus recall plus longer soak; never enable production from aggregate benchmark metrics alone."
}
Write-Host "Pre-queue real ONNX A/B comparison (same source, model, seed and workload)"
foreach ($row in $rows) {
    Write-Host ("rate={0}/s normalMasked off/on={1}/{2}, failClosed off/on={3}/{4}, ONNX calls off/on={5}/{6}, mean queueMs off/on={7}/{8}" -f
        $row.rate_per_second, $row.baseline_success_count,
        $row.enabled_success_count,
        $row.baseline_fail_closed,
        $row.enabled_fail_closed,
        $row.baseline_onnx_calls,
        $row.enabled_onnx_calls,
        $row.baseline_mean_queue_wait_ms,
        $row.enabled_mean_queue_wait_ms)
}
Write-Host "Release gate remains OPEN; this comparison does not audit exact protected output or locked high-risk recall."
if (-not [string]::IsNullOrWhiteSpace($OutputPath)) {
    $destination = [System.IO.Path]::GetFullPath($OutputPath)
    if (Test-Path -LiteralPath $destination) {
        throw "Refusing to overwrite an existing A/B scorecard: $destination"
    }
    $parent = Split-Path -Path $destination -Parent
    if (-not (Test-Path -LiteralPath $parent -PathType Container)) {
        throw "A/B scorecard parent folder missing: $parent"
    }
    $comparison | ConvertTo-Json -Depth 12 |
        Set-Content -LiteralPath $destination -Encoding UTF8
    Write-Host "Wrote compact A/B scorecard to: $destination"
}
