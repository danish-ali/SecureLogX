param(
    [Parameter(Mandatory=$true)][string]$BaselineStudy,
    [Parameter(Mandatory=$true)][string]$PreQueueStudy,
    [string]$OutputPath
)
$ErrorActionPreference = "Stop"

function Require([bool]$ok, [string]$message) {
    if (-not $ok) { throw $message }
}
function Read-Study([string]$path) {
    $file = (Resolve-Path -LiteralPath $path -ErrorAction Stop).Path
    $study = Get-Content -LiteralPath $file -Raw | ConvertFrom-Json
    Require ($study.kind -ceq "compact-real-onnx-steady-arrival-study") "Not a steady-ONNX study: $file."
    Require ($study.status -ceq "COMPLETE_VERIFIED" -and
        $study.security_validated_all_runs -eq $true -and
        [int]$study.validated_trials -eq [int]$study.expected_trials -and
        @($study.trials).Count -eq [int]$study.expected_trials) "Study lacks complete synthetic validation: $file."
    return $study
}
function Sum-Field([object[]]$items, [string]$field) {
    $total = 0.0
    foreach ($item in $items) {
        Require ($null -ne $item.$field) "Missing numeric metric $field."
        $total += [double]$item.$field
    }
    return $total
}
function Max-Field([object[]]$items, [string]$field) {
    Require ($items.Count -gt 0) "No rows for $field."
    $maximum = [double]::NegativeInfinity
    foreach ($item in $items) {
        Require ($null -ne $item.$field) "Missing numeric metric $field."
        $maximum = [math]::Max($maximum, [double]$item.$field)
    }
    return $maximum
}

$off = Read-Study $BaselineStudy
$on = Read-Study $PreQueueStudy
Require ($off.experimental_pre_queue_enabled -eq $false) "Baseline must have pre-queue OFF."
Require ($on.experimental_pre_queue_enabled -eq $true) "Experimental study must have pre-queue ON."

$identical = @(
    "source_commit_sha", "configured_model_sha256",
    "configured_tokenizer_sha256", "repetitions", "duration_seconds",
    "caller_mode", "max_outstanding", "memory_sample_interval_millis",
    "deadline_millis", "cpu_threads", "max_windows_per_batch",
    "masking_queue_capacity", "random_seed",
    "synthetic_organization_rules_enabled"
)
foreach ($key in $identical) {
    Require ($null -ne $off.$key -and $null -ne $on.$key) "Missing source/configuration field $key."
    Require ([string]$off.$key -ceq [string]$on.$key) "OFF/ON configuration differs in $key."
}
Require ([string]$off.source_commit_sha -cmatch '^[0-9a-f]{40}$') "Source commit SHA is missing/invalid."
Require ([string]$off.configured_model_sha256 -match '^[a-f0-9]{64}$') "Model SHA-256 is missing/invalid."
Require ([string]$off.configured_tokenizer_sha256 -match '^[a-f0-9]{64}$') "Tokenizer SHA-256 is missing/invalid."
if ($off.synthetic_organization_rules_enabled -eq $true) {
    Require ([string]$off.synthetic_organization_rules_sha256 -match '^[a-f0-9]{64}$') "Synthetic organization rules hash missing."
    Require ([string]$off.synthetic_organization_rules_sha256 -ceq
        [string]$on.synthetic_organization_rules_sha256) "Synthetic organization rules differ."
}

$offPlan = @($off.rate_plan)
$onPlan = @($on.rate_plan)
Require ($offPlan.Count -gt 0 -and $offPlan.Count -eq $onPlan.Count) "OFF/ON plans differ."
$offTrials = @($off.trials)
$onTrials = @($on.trials)
Require ($offTrials.Count -eq $onTrials.Count) "OFF/ON trial counts differ."
for ($i=0; $i -lt $offPlan.Count; $i++) {
    Require ([int]$offPlan[$i].rate -eq [int]$onPlan[$i].rate -and
        [int]$offPlan[$i].repetition -eq [int]$onPlan[$i].repetition) "Trial plan mismatch at index $i."
}
for ($i=0; $i -lt $offTrials.Count; $i++) {
    $a = $offTrials[$i]
    $b = $onTrials[$i]
    Require ($a.security_passed -eq $true -and $b.security_passed -eq $true) "Synthetic security check failed in trial $($i + 1)."
    Require ([int]$a.run -eq [int]$b.run -and
        [int]$a.repetition -eq [int]$b.repetition -and
        [int]$a.offered_rate_per_second -eq [int]$b.offered_rate_per_second -and
        [int]$a.events -eq [int]$b.events) "Workload mismatch in trial $($i + 1)."
    Require ($a.pre_queue_enabled -eq $false -and
        $b.pre_queue_enabled -eq $true) "Unexpected routing flag in trial $($i + 1)."
    foreach ($item in @($a,$b)) {
        Require ([string]$item.configured_model_sha256 -ceq
            [string]$off.configured_model_sha256 -and
            [string]$item.configured_tokenizer_sha256 -ceq
            [string]$off.configured_tokenizer_sha256) "Model fingerprint changed within trial $($i + 1)."
        Require (([int]$item.normal_masked + [int]$item.fail_closed) -eq
            [int]$item.events) "Normal masking plus fail-closed is incomplete in trial $($i + 1)."
    }
}

$rates = @($offPlan | ForEach-Object { [int]$_.rate } | Sort-Object -Unique)
$rows = [System.Collections.Generic.List[object]]::new()
foreach ($rate in $rates) {
    $base = @($offTrials | Where-Object { $_.offered_rate_per_second -eq $rate })
    $experiment = @($onTrials | Where-Object { $_.offered_rate_per_second -eq $rate })
    Require ($base.Count -gt 0 -and
        $base.Count -eq [int]$off.repetitions -and
        $experiment.Count -eq $base.Count) "Missing repeated trial for rate $rate."

    $row = [pscustomobject]@{
        offered_rate_per_second = [int]$rate
        repetitions = $base.Count
        baseline_normal_masked = [int](Sum-Field $base "normal_masked")
        experimental_normal_masked = [int](Sum-Field $experiment "normal_masked")
        baseline_fail_closed = [int](Sum-Field $base "fail_closed")
        experimental_fail_closed = [int](Sum-Field $experiment "fail_closed")
        baseline_functional_passes = @($base | Where-Object { $_.functional_passed }).Count
        experimental_functional_passes = @($experiment | Where-Object { $_.functional_passed }).Count
        baseline_mean_normal_masked_per_second = [math]::Round(
            (Sum-Field $base "normal_masked_per_second") / $base.Count,3)
        experimental_mean_normal_masked_per_second = [math]::Round(
            (Sum-Field $experiment "normal_masked_per_second") / $experiment.Count,3)
        baseline_worst_p99_millis = [math]::Round(
            (Max-Field $base "caller_p99_ms"), 3)
        experimental_worst_p99_millis = [math]::Round(
            (Max-Field $experiment "caller_p99_ms"), 3)
        baseline_max_caller_latency_millis = [math]::Round(
            (Max-Field $base "caller_max_ms"), 3)
        experimental_max_caller_latency_millis = [math]::Round(
            (Max-Field $experiment "caller_max_ms"), 3)
        baseline_mean_queue_wait_millis = [math]::Round(
            (Sum-Field $base "queue_wait_avg_ms") / $base.Count,3)
        experimental_mean_queue_wait_millis = [math]::Round(
            (Sum-Field $experiment "queue_wait_avg_ms") / $experiment.Count,3)
        baseline_native_onnx_calls = [int](Sum-Field $base "native_onnx_calls")
        experimental_native_onnx_calls = [int](Sum-Field $experiment "native_onnx_calls")
        baseline_native_windows = [int](Sum-Field $base "native_windows")
        experimental_native_windows = [int](Sum-Field $experiment "native_windows")
        experimental_prequeue_deterministic_fields = [int](
            Sum-Field $experiment "pre_queue_deterministic_fields")
        experimental_prequeue_escalated_fields = [int](
            Sum-Field $experiment "pre_queue_escalated_fields")
        synthetic_security_checks_reported = $true
        protected_payload_parity_proved = $false
        frozen_high_risk_corpus_parity_proved = $false
    }
    $rows.Add($row)
}

$scorecard = [ordered]@{
    schema_version = 1
    experiment = "steady-onnx-prequeue-optin-ab"
    baseline_study_id = $off.study_id
    experimental_study_id = $on.study_id
    source_commit_sha = $off.source_commit_sha
    configured_model_sha256 = $off.configured_model_sha256
    configured_tokenizer_sha256 = $off.configured_tokenizer_sha256
    synthetic_organization_rules_sha256 = $off.synthetic_organization_rules_sha256
    assessment = "SYNTHETIC_METRICS_ONLY"
    release_gate = "OPEN: exact protected output from local ZIP and locked high-risk recall require separate validation; production default stays off."
    comparison = @($rows.ToArray())
}
Write-Host "Compared paired synthetic steady-ONNX OFF/ON studies with the same source/model/policy/configuration."
foreach ($r in $rows) {
    Write-Host ("rate={0}/s normal off/on={1}/{2}, fail-closed off/on={3}/{4}, native calls off/on={5}/{6}, average queue wait off/on={7}/{8}ms" -f
        $r.offered_rate_per_second,$r.baseline_normal_masked,
        $r.experimental_normal_masked,
        $r.baseline_fail_closed,$r.experimental_fail_closed,
        $r.baseline_native_onnx_calls,$r.experimental_native_onnx_calls,
        $r.baseline_mean_queue_wait_millis,$r.experimental_mean_queue_wait_millis)
}
Write-Host "Synthetic metrics alone cannot certify output or high-risk recall parity."
if (-not [string]::IsNullOrWhiteSpace($OutputPath)) {
    $outFile = [System.IO.Path]::GetFullPath($OutputPath)
    Require (-not (Test-Path -LiteralPath $outFile)) "Refusing to overwrite existing scorecard."
    $parent = Split-Path -Path $outFile -Parent
    Require (Test-Path -LiteralPath $parent -PathType Container) "Scorecard parent directory missing."
    $scorecard | ConvertTo-Json -Depth 12 |
        Set-Content -LiteralPath $outFile -Encoding UTF8
    Write-Host "Wrote compact scorecard: $outFile"
}
