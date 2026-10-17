param(
    [string]$InputPath = ".\reports\application-log-benchmark\protected-application.log",
    [string]$OutputPath = ".\reports\application-log-benchmark\reconstructed-application.log"
)

$ErrorActionPreference = "Stop"

# Intentionally limited to the single-line synthetic application benchmark
# layout. For production logs, ingest securelogx.instanceId and securelogx.seq
# as structured, typed fields in the log store. Across instances, no global
# total order exists from the sequence counters alone.
$inputFile = [System.IO.Path]::GetFullPath($InputPath)
$outputFile = [System.IO.Path]::GetFullPath($OutputPath)
if (-not [System.IO.File]::Exists($inputFile)) {
    throw "Protected application log was not found: $inputFile"
}
if ([string]::Equals(
        $inputFile, $outputFile,
        [System.StringComparison]::OrdinalIgnoreCase
    )) {
    throw "Refusing to overwrite the original protected log."
}

$pattern = '^eventId=[^|]*\|level=[^|]*\|instanceId=(?<instance>[0-9a-fA-F-]{36})\|seq=(?<seq>[0-9]+)\|ingressId=(?<ingress>[^|]+)\|'
$seen = [System.Collections.Generic.HashSet[string]]::new()
$records = [System.Collections.Generic.List[object]]::new()
$maximumSeen = @{}
$inversions = 0
$lineIndex = 0

foreach ($line in [System.IO.File]::ReadLines($inputFile)) {
    if ([string]::IsNullOrWhiteSpace($line)) {
        continue
    }

    # Reject unexpected multiline records rather than silently dropping
    # output. This utility deliberately targets the benchmark's one-line
    # sanitized event format, NOT arbitrary user-supplied logs.
    if ($line -notmatch $pattern) {
        throw "Line $($lineIndex + 1) is not in the expected protected benchmark format."
    }

    $instance = $Matches['instance']
    $guid = [guid]::Empty
    if (-not [guid]::TryParse($instance, [ref]$guid) -or
            $guid.ToString() -cne $instance) {
        throw "Invalid canonical instanceId at line $($lineIndex + 1)."
    }

    [long]$seq = 0
    if (-not [long]::TryParse($Matches['seq'], [ref]$seq) -or $seq -le 0) {
        throw "Invalid numeric sequence at line $($lineIndex + 1)."
    }
    $ingress = $Matches['ingress']
    $expected = "${instance}-$seq"
    if ($ingress -cne $expected) {
        throw "Mismatched ingressId at line $($lineIndex + 1)."
    }
    if (-not $seen.Add($expected)) {
        throw "Duplicate ingressId detected at line $($lineIndex + 1)."
    }

    if ($maximumSeen.ContainsKey($instance)) {
        if ($seq -lt $maximumSeen[$instance]) {
            $inversions++
        }
        if ($seq -gt $maximumSeen[$instance]) {
            $maximumSeen[$instance] = $seq
        }
    } else {
        $maximumSeen[$instance] = $seq
    }

    $records.Add([pscustomobject]@{
        InstanceId = $instance
        Seq = $seq
        IngressId = $ingress
        OriginalLine = $lineIndex
        Text = $line
    })
    $lineIndex++
}

if ($records.Count -eq 0) {
    throw "No protected benchmark records found."
}

# Sorting is numeric within each producer/instance. Group order across
# different instanceIds is arbitrary and MUST NOT be interpreted as time.
$ordered = @($records | Sort-Object -Property InstanceId, Seq)
$targetDir = [System.IO.Path]::GetDirectoryName($outputFile)
if (-not [System.IO.Directory]::Exists($targetDir)) {
    [System.IO.Directory]::CreateDirectory($targetDir) | Out-Null
}
[System.IO.File]::WriteAllLines(
    $outputFile,
    [string[]]@($ordered | ForEach-Object { $_.Text })
)

Write-Host "Protected records: $($records.Count)"
Write-Host "Distinct instance IDs: $($maximumSeen.Count)"
Write-Host "Physical write-order inversions: $inversions"
Write-Host "Duplicate ingress IDs: 0"
Write-Host "Reconstructed output: $outputFile"
if ($maximumSeen.Count -gt 1) {
    Write-Warning "Multiple instance IDs: order is reconstructed within each instance only. No global cross-JVM chronology is implied."
}
