param(
    [Parameter(Mandatory=$true)][string]$BaselineStudy,
    [Parameter(Mandatory=$true)][string]$PreQueueStudy,
    [Parameter(Mandatory=$true)][string]$BaselineDetailZip,
    [Parameter(Mandatory=$true)][string]$PreQueueDetailZip
)
$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.IO.Compression.FileSystem

function Check([bool]$ok,[string]$message) {
    if (-not $ok) { throw $message }
}

$off = Get-Content -LiteralPath (
    (Resolve-Path -LiteralPath $BaselineStudy).Path
) -Raw | ConvertFrom-Json
$on = Get-Content -LiteralPath (
    (Resolve-Path -LiteralPath $PreQueueStudy).Path
) -Raw | ConvertFrom-Json

Check ($off.status -ceq "COMPLETE_VERIFIED" -and
    $on.status -ceq "COMPLETE_VERIFIED") "Both studies must be complete."
Check ($off.security_validated_all_runs -eq $true -and
    $on.security_validated_all_runs -eq $true) "Synthetic security validation must pass."
Check ($off.experimental_pre_queue_enabled -eq $false -and
    $on.experimental_pre_queue_enabled -eq $true) "Expected baseline OFF and experimental ON."
Check ($null -ne $off.source_commit_sha -and
    $off.source_commit_sha -ceq $on.source_commit_sha) "Different source revision."
Check ($off.configured_model_sha256 -ceq $on.configured_model_sha256 -and
    $off.configured_tokenizer_sha256 -ceq
        $on.configured_tokenizer_sha256) "Model or tokenizer differs."
Check ($off.synthetic_organization_rules_enabled -eq
    $on.synthetic_organization_rules_enabled -and
    $off.synthetic_organization_rules_sha256 -ceq
        $on.synthetic_organization_rules_sha256) "Synthetic rules differ."
foreach ($key in @(
    "repetitions","duration_seconds","caller_mode",
    "max_outstanding","deadline_millis","cpu_threads",
    "max_windows_per_batch","masking_queue_capacity","random_seed"
)) {
    Check ([string]$off.$key -ceq [string]$on.$key) "Configuration differs: $key."
}
$a = @($off.trials)
$b = @($on.trials)
Check ($a.Count -eq $b.Count -and
    $a.Count -eq [int]$off.expected_trials -and
    $b.Count -eq [int]$on.expected_trials) "Trial count differs."

function Read-ProtectedEntry(
    [System.IO.Compression.ZipArchive]$archive,
    [string]$entryPath,
    [string]$expectedSha
) {
    $entry = @($archive.Entries | Where-Object {
        $_.FullName.Replace('\','/') -ceq $entryPath
    })
    Check ($entry.Count -eq 1) "Expected exactly one local protected entry: $entryPath."
    $stream = $entry[0].Open()
    $buffer = [System.IO.MemoryStream]::new()
    try {
        $stream.CopyTo($buffer)
        $bytes = $buffer.ToArray()
    } finally {
        $stream.Dispose()
        $buffer.Dispose()
    }
    $hasher = [System.Security.Cryptography.SHA256]::Create()
    try {
        $observedHash = [BitConverter]::ToString(
            $hasher.ComputeHash($bytes)
        ).Replace("-", "")
    } finally {
        $hasher.Dispose()
    }
    Check ($observedHash -ceq $expectedSha) "Original protected ZIP entry hash mismatch."
    return [System.Text.Encoding]::UTF8.GetString($bytes)
}

function Normalized-ProtectedRecords(
    [string]$content, [int]$expectedEvents
) {
    $map = @{}
    $lines = @($content -split '\r?\n' | Where-Object {
        $_.StartsWith("eventId=")
    })
    Check ($lines.Count -eq ($expectedEvents + 4)) "Synthetic protected record count is incomplete."
    foreach ($line in $lines) {
        Check ($line -cmatch '^eventId=(bench-[0-9]+|warmup-[0-9]+)\|') "Unexpected/failed event identity in normal-only run."
        $id = $Matches[1]
        Check (-not $map.ContainsKey($id)) "Duplicate synthetic event ID."
        Check ($line.Contains("|failClosed=false|")) "Fail-closed record cannot satisfy exact parity gate."
        # Only remove run-dependent ingress identifiers and execution
        # telemetry, not message text, MDC/customer, stack or throwable.
        # Preserve a uniform failClosed=false marker for every record.
        $normalized = $line -creplace '\|instanceId=[^|]*\|seq=[^|]*\|ingressId=[^|]*\|ml=[^|]*\|failClosed=[^|]*\|reason=[^|]*', '|failClosed=false'
        Check ($normalized -cne $line) "Unexpected synthetic log layout."
        $map[$id] = $normalized
    }
    for ($i=0; $i -lt $expectedEvents; $i++) {
        Check ($map.ContainsKey("bench-$i")) "Missing expected benchmark event."
    }
    for ($i=0; $i -lt 4; $i++) {
        Check ($map.ContainsKey("warmup-$i")) "Missing expected warmup event."
    }
    return ,$map
}

$offZip = [System.IO.Compression.ZipFile]::OpenRead(
    (Resolve-Path -LiteralPath $BaselineDetailZip).Path
)
$onZip = [System.IO.Compression.ZipFile]::OpenRead(
    (Resolve-Path -LiteralPath $PreQueueDetailZip).Path
)
$comparedTrials=0; $comparedRecords=0; $ineligible=0
try {
    for ($i=0; $i -lt $a.Count; $i++) {
        $aa=$a[$i]; $bb=$b[$i]
        Check ([int]$aa.run -eq [int]$bb.run -and
            [int]$aa.repetition -eq [int]$bb.repetition -and
            [int]$aa.offered_rate_per_second -eq
                [int]$bb.offered_rate_per_second -and
            [int]$aa.events -eq [int]$bb.events) "Paired trial schedule differs."
        Check ($aa.security_passed -eq $true -and
            $bb.security_passed -eq $true) "Synthetic security gate failed."
        if ($aa.functional_passed -ne $true -or
                $bb.functional_passed -ne $true -or
                [int]$aa.fail_closed -ne 0 -or
                [int]$bb.fail_closed -ne 0) {
            $ineligible++
            continue
        }
        $runName = "run-" + ([int]$aa.run).ToString("000") +
            "-r" + $aa.offered_rate_per_second
        $entryPath = "$runName/protected-application.log"
        $offText=Read-ProtectedEntry $offZip $entryPath (
            [string]$aa.local_protected_log_sha256
        )
        $onText=Read-ProtectedEntry $onZip $entryPath (
            [string]$bb.local_protected_log_sha256
        )
        $offMap=Normalized-ProtectedRecords $offText ([int]$aa.events)
        $onMap=Normalized-ProtectedRecords $onText ([int]$bb.events)
        Check ($offMap.Count -eq $onMap.Count) "Output record count differs."
        foreach ($id in $offMap.Keys) {
            Check ($onMap.ContainsKey($id)) "A/B output identity set differs."
            # Never print payload content, even on mismatches.
            Check ($offMap[$id] -ceq $onMap[$id]) (
                "Protected output changed for one event in paired trial " +
                "$($i+1). No sensitive payload displayed."
            )
            $comparedRecords++
        }
        $comparedTrials++
        Write-Host "Trial $($i+1): exact protected fixture parity passed."
    }
} finally {
    $offZip.Dispose()
    $onZip.Dispose()
}
Write-Host ("PARITY RESULT: {0} paired functional trials verified, " +
    "{1} synthetic protected records matched, {2} trial pairs " +
    "not eligible because one/both had functional failures." -f
    $comparedTrials,$comparedRecords,$ineligible)
if ($comparedTrials -eq 0) {
    throw "No all-normal matched trial pair; output-parity gate remains OPEN."
}
if ($ineligible -gt 0) {
    Write-Host "PARTIAL synthetic parity only; release gate remains OPEN."
} else {
    Write-Host "Exact synthetic protected-output parity passed all paired trials."
}
Write-Host "Locked 6,463-case high-risk recall and extended soak remain OPEN regardless of this synthetic parity check."
