<#
Reads existing payload-free stage timings from a previously archived local
steady ONNX trial ZIP. Never starts Maven, Java or ONNX, changes files,
or writes log content to the console. Requires the compact manifest and
the original ignored local ZIP from the same study.
#>
param(
    [Parameter(Mandatory=$true)][string]$StudyPath,
    [Parameter(Mandatory=$true)][string]$DetailZip
)
$ErrorActionPreference = "Stop"

$study = Get-Content -LiteralPath $StudyPath -Raw | ConvertFrom-Json
if ($study.kind -cne "compact-real-onnx-steady-arrival-study" -or
        $study.status -cne "COMPLETE_VERIFIED") {
    throw "Require a complete, verified compact steady ONNX study."
}
if (@($study.trials).Count -ne [int]$study.validated_trials) {
    throw "Trial count does not match the committed study manifest."
}

Add-Type -AssemblyName System.IO.Compression.FileSystem

function Assert-EntrySha256 {
    param(
        [Parameter(Mandatory=$true)]
        [System.IO.Compression.ZipArchiveEntry]$Entry,
        [Parameter(Mandatory=$true)][string]$Expected
    )
    $stream = $Entry.Open()
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $actual = [System.BitConverter]::ToString(
            $sha.ComputeHash($stream)
        ).Replace("-", "")
    } finally {
        $stream.Dispose()
        $sha.Dispose()
    }
    if (-not [string]::Equals(
            $actual, $Expected,
            [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "SHA-256 verification failed for ZIP entry $($Entry.FullName)."
    }
}

# Exact whitelist only. A raw benchmark result may contain timelines
# and metadata, but neither those nor any protected log is emitted.
$stageFields = @(
    "deterministic",
    "tokenization",
    "window_assembly",
    "tensor_preparation",
    "onnx_native_run",
    "onnx_windowed_native_run_subset",
    "decoding",
    "resolution_and_masking"
)
$archive = [System.IO.Compression.ZipFile]::OpenRead(
    (Resolve-Path -LiteralPath $DetailZip).Path
)
try {
    $rows = @(
        foreach ($trial in $study.trials) {
            $runName = "run-" + ([int]$trial.run).ToString("000") +
                "-r" + [int]$trial.offered_rate_per_second
            $resultName = "$runName/result.json"
            $logName = "$runName/protected-application.log"
            $resultEntry = $archive.GetEntry($resultName)
            $logEntry = $archive.GetEntry($logName)
            if ($null -eq $resultEntry -or $null -eq $logEntry) {
                throw "Missing result or protected-log evidence for $runName."
            }
            Assert-EntrySha256 -Entry $resultEntry -Expected ([string]$trial.local_result_sha256)
            # Verify the protected log's digest without printing its text.
            Assert-EntrySha256 -Entry $logEntry -Expected ([string]$trial.local_protected_log_sha256)

            $reader = [System.IO.StreamReader]::new(
                $resultEntry.Open(), [System.Text.Encoding]::UTF8, $true
            )
            try {
                $raw = $reader.ReadToEnd() | ConvertFrom-Json
            } finally {
                $reader.Dispose()
            }
            if ([int]$raw.offered_rate_per_second -ne
                    [int]$trial.offered_rate_per_second -or
                [int]$raw.successful_masked_events -ne
                    [int]$trial.normal_masked -or
                [int]$raw.fail_closed_events -ne
                    [int]$trial.fail_closed) {
                throw "Trial values differ from the manifest for $runName."
            }
            if ($null -eq $raw.real_inference_measured_only -or
                    $null -eq $raw.real_inference_measured_only.stage_total_millis) {
                throw "Original result lacks measured stage times for $runName."
            }
            $stage = $raw.real_inference_measured_only.stage_total_millis
            $timings = [ordered]@{}
            foreach ($key in $stageFields) {
                $prop = $stage.PSObject.Properties[$key]
                if ($null -eq $prop -or $null -eq $prop.Value) {
                    throw "Missing stage $key in $runName."
                }
                $value = [double]$prop.Value
                if ([double]::IsNaN($value) -or
                        [double]::IsInfinity($value) -or $value -lt 0) {
                    throw "Invalid numeric stage $key in $runName."
                }
                $timings[$key] = $value
            }
            [pscustomobject]@{
                run = [int]$trial.run
                offered_rate_per_second = [int]$trial.offered_rate_per_second
                normal_masked = [int]$trial.normal_masked
                queue_wait_average_millis = [double]$trial.queue_wait_avg_ms
                worker_scan_millis = [double]$trial.phase_one_worker_scan_nanos / 1000000.0
                native_attempted = [long]$trial.phase_one_native_attempted
                native_completed = [long]$trial.phase_one_native_completed
                native_failed_or_canceled = [long]$trial.phase_one_native_failed_or_canceled
                stage_total_millis = [pscustomobject]$timings
            }
        }
    )
    [pscustomobject]@{
        kind = "verified-payload-free-phase-one-timing-extract"
        study_id = [string]$study.study_id
        source_commit_sha = [string]$study.source_commit_sha
        validated_trials = $rows.Count
        note = "Stage totals are cumulative milliseconds, not per-event percentiles; windowed native time is a subset of native time. Failed native attempts may be missing from successful-stage timing."
        trials = $rows
    } | ConvertTo-Json -Depth 8
} finally {
    $archive.Dispose()
}
