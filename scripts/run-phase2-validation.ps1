# Execute Phase 2 validation on the Windows development host.
# Stages: Tests, Onnx, Corpus, All. All writes stay local.
# The corpus checks DO NOT establish complete ONNX redaction accuracy.
param(
    [ValidateSet("Tests","Onnx","Corpus","All")]
    [string]$Stage = "All",
    [string]$NerRoot = "..\SecureLogX-NER",
    # Optional pinned checkout (typically a detached Git worktree).
    # Preflight, Maven, and fingerprinting use this tree; the harness
    # scripts may live in a different working copy.
    [string]$RepoRoot = ""
)
$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "phase2-input-integrity.ps1")
if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
    $root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
} else {
    $root = (Resolve-Path -LiteralPath $RepoRoot).Path
}
$requiredBranch = "chatgpt/phase2-request-scoped-scan-reuse"
$branch = ([string]((& git -C $root branch --show-current) | Out-String)).Trim()
$sha = (& git -C $root rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($sha)) {
    throw "Cannot resolve source SHA."
}
$tip = (& git -C $root rev-parse --verify $requiredBranch).Trim()
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($tip)) {
    throw "Cannot resolve $requiredBranch."
}
& git -C $root merge-base --is-ancestor $sha $requiredBranch | Out-Null
$onPhase2Line = ($LASTEXITCODE -eq 0)
$detached = [string]::IsNullOrWhiteSpace($branch)
if (-not $onPhase2Line -or (-not $detached -and $branch -cne $requiredBranch)) {
    throw "Switch to $requiredBranch, or a detached worktree whose HEAD is on that branch, before validation. branch='$branch' sha=$sha"
}
if ($detached) {
    Write-Host "Phase 2 detached worktree at $sha (on $requiredBranch)"
}

function ConvertFrom-GitPorcelainPaths {
    param([string]$Line)
    if ([string]::IsNullOrWhiteSpace($Line) -or $Line.Length -lt 4) {
        return @()
    }
    $rest = $Line.Substring(3)
    if ($rest.StartsWith('"') -and $rest.EndsWith('"') -and $rest.Length -ge 2) {
        $rest = $rest.Substring(1, $rest.Length - 2)
        $rest = $rest -replace '\\n', "`n" -replace '\\t', "`t" -replace '\\"', '"' -replace '\\\\', '\'
    }
    if ($rest -match ' -> ') {
        return @($rest -split ' -> ', 2)
    }
    return @($rest)
}

function Normalize-Phase2Path {
    param([string]$Path)
    $norm = ($Path -replace '\\', '/').Trim()
    if ($norm.StartsWith('./')) { $norm = $norm.Substring(2) }
    return $norm
}

$porcelain = @(& git -C $root -c core.quotepath=false status --porcelain=v1 --untracked-files=all --no-renames)
if ($LASTEXITCODE -ne 0) {
    throw "Unable to inspect Git working-tree status."
}
$dirtyPaths = @(
    foreach ($line in $porcelain) {
        ConvertFrom-GitPorcelainPaths -Line $line
    }
) | ForEach-Object { Normalize-Phase2Path $_ } | Where-Object { $_ } | Select-Object -Unique
$relevantDirty = @($dirtyPaths | Where-Object { -not (Test-Phase2DirtyPathAllowed -Path $_) })
$ignoredDirty = @($dirtyPaths | Where-Object { Test-Phase2DirtyPathAllowed -Path $_ })
# Only the timed real-ONNX A/B study requires a clean *source* snapshot
# for comparable, source-fingerprinted performance evidence. Tests and
# the read-only corpus gate may run with tracked local changes, but their
# results describe that working tree rather than the published commit.
if ($relevantDirty.Count -gt 0) {
    $listed = $relevantDirty -join '; '
    if ($Stage -in @("Onnx","All")) {
        throw "Matched ONNX A/B requires a clean source tree (tracked and untracked). Blocking paths: $listed"
    }
    Write-Warning "Source changes detected ($listed). $Stage results describe the local working tree, not necessarily commit $sha."
} elseif ($ignoredDirty.Count -gt 0) {
    Write-Host ("Ignoring non-input artifacts: " + ($ignoredDirty -join '; '))
}
Write-Host "Phase 2 source: $sha"
Push-Location $root
try {
    if ($Stage -in @("Tests","All")) {
        Write-Host "[TESTS] Maven reactor..."
        & mvn "-pl" "securelogx-log4j2" "-am" "clean" "test"
        if ($LASTEXITCODE -ne 0) { throw "Maven regression tests failed." }
        Write-Host "PHASE2 TESTS PASSED"
    }

    if ($Stage -in @("Onnx","All")) {
        Test-Phase2BenchmarkInputs -RepoRoot $root | Out-Null
        $runner = Join-Path $PSScriptRoot "run-steady-onnx-study.ps1"
        $verify = Join-Path $PSScriptRoot "verify-scan-reuse-output-parity.ps1"
        $studyDir = Join-Path $root "reports\parallel-onnx\steady"
        $zipDir = Join-Path $root "reports\parallel-onnx\.local-steady-runs"
        $phase2 = $script:Phase2ExpectedConfiguration
        $common = @(
            "-RepoRoot", $root,
            "-Rates", $phase2.rates_per_second,
            "-Repetitions", [string]$phase2.repetitions,
            "-DurationSeconds", [string]$phase2.duration_seconds,
            "-CallerMode", $phase2.caller_mode,
            "-MaxOutstanding", [string]$phase2.max_outstanding,
            "-MaskingDeadlineMillis", [string]$phase2.deadline_millis,
            "-CpuThreads", [string]$phase2.cpu_threads,
            "-WindowBatchLimit", [string]$phase2.max_windows_per_batch,
            "-MaskingQueueCapacity", [string]$phase2.masking_queue_capacity,
            "-MemorySampleMillis", [string]$phase2.memory_sample_interval_millis,
            "-RandomSeed", [string]$phase2.random_seed
        )
        if ($phase2.organization_rules_enabled) { $common += "-UseExampleOrganizationRules" }
        if ($phase2.pre_queue_routing_enabled) { $common += "-EnablePreQueueRouting" }

        Write-Host "[ONNX] Baseline: prequeue ON, reuse OFF"
        Test-Phase2BenchmarkInputs -RepoRoot $root | Out-Null
        & powershell -NoProfile -ExecutionPolicy Bypass -File $runner @common
        if ($LASTEXITCODE -ne 0) { throw "Baseline study failed." }
        Test-Phase2BenchmarkInputs -RepoRoot $root | Out-Null
        $baseline = @(Get-ChildItem -LiteralPath $studyDir -Filter "study-*.json" -File | Sort-Object LastWriteTimeUtc -Descending)[0]
        $off = Get-Content -LiteralPath $baseline.FullName -Raw | ConvertFrom-Json
        if ($off.source_commit_sha -cne $sha -or
                $off.experimental_pre_queue_enabled -ne $true -or
                $off.experimental_pre_queue_scan_reuse_enabled -ne $false) {
            throw "Baseline source or flags mismatch."
        }

        Write-Host "[ONNX] Optimized: prequeue ON, reuse ON"
        Test-Phase2BenchmarkInputs -RepoRoot $root | Out-Null
        & powershell -NoProfile -ExecutionPolicy Bypass -File $runner @common -EnablePreQueueScanReuse
        if ($LASTEXITCODE -ne 0) { throw "Optimized study failed." }
        Test-Phase2BenchmarkInputs -RepoRoot $root | Out-Null
        $optimized = @(Get-ChildItem -LiteralPath $studyDir -Filter "study-*.json" -File | Sort-Object LastWriteTimeUtc -Descending)[0]
        $on = Get-Content -LiteralPath $optimized.FullName -Raw | ConvertFrom-Json
        if ($on.study_id -ceq $off.study_id -or $on.source_commit_sha -cne $sha -or
                $on.experimental_pre_queue_enabled -ne $true -or
                $on.experimental_pre_queue_scan_reuse_enabled -ne $true) {
            throw "Optimized source or flags mismatch."
        }

        $offZip = Join-Path $zipDir ("detail-" + $off.study_id + ".zip")
        $onZip = Join-Path $zipDir ("detail-" + $on.study_id + ".zip")
        Write-Host "[ONNX] Exact synthetic protected-output and scan-count parity"
        & powershell -NoProfile -ExecutionPolicy Bypass -File $verify -BaselineStudy $baseline.FullName -PreQueueStudy $optimized.FullName -BaselineDetailZip $offZip -PreQueueDetailZip $onZip
        if ($LASTEXITCODE -ne 0) { throw "Phase 2 ONNX parity gate failed." }
        Test-Phase2BenchmarkInputs -RepoRoot $root | Out-Null
        Write-Host "PHASE2 MATCHED SYNTHETIC ONNX PARITY PASSED"
        Write-Host "Baseline: $($baseline.FullName)"
        Write-Host "Optimized: $($optimized.FullName)"
    }

    if ($Stage -in @("Corpus","All")) {
        $ner = (Resolve-Path -LiteralPath $NerRoot -ErrorAction Stop).Path
        $local = Join-Path $root "reports\parallel-onnx\.local-steady-runs"
        New-Item -ItemType Directory -Force -Path $local | Out-Null
        $stamp = [DateTime]::UtcNow.ToString("yyyyMMddTHHmmssZ")
        $goldPath = Join-Path $local "phase2-$stamp-gold-audit.json"
        $reusePath = Join-Path $local "phase2-$stamp-reuse-audit.json"

        & mvn "-pl" "securelogx-core" "-am" "-DskipTests" "package"
        if ($LASTEXITCODE -ne 0) { throw "Corpus audit compile failed." }
        $goldArgs = '"' + $ner + '" "' + $goldPath + '"'
        Write-Host "[CORPUS] Running 6463-case labeled gold-span safety audit..."
        & mvn "-pl" "securelogx-core" "-Dexec.mainClass=com.securelogx.validation.HybridGateDatasetAudit" "-Dexec.args=$goldArgs" "org.codehaus.mojo:exec-maven-plugin:3.5.0:java"
        if ($LASTEXITCODE -ne 0) {
            # The source audit may have sensitive values in old
            # failure_samples. Never print that property or the raw JSON.
            # Only report fixed-schema aggregate counters.
            if (Test-Path -LiteralPath $goldPath -PathType Leaf) {
                $failedGate = Get-Content -LiteralPath $goldPath -Raw |
                    ConvertFrom-Json
                Write-Host ("GOLD AUDIT COUNTS: records={0}, uncovered={1}, overmask={2}, allow_conflict={3}" -f
                    [int]$failedGate.records,
                    [int]$failedGate.bypass_uncovered_gold,
                    [int]$failedGate.deterministic_overmask,
                    [int]$failedGate.allow_gold_conflict)
                foreach ($source in @($failedGate.by_source.PSObject.Properties | Sort-Object Name)) {
                    $summary = $source.Value
                    Write-Host ("DATASET {0}: records={1}, uncovered={2}, overmask={3}, allow_conflict={4}" -f
                        $source.Name,
                        [int]$summary.records,
                        [int]$summary.bypass_uncovered_gold,
                        [int]$summary.deterministic_overmask,
                        [int]$summary.allow_gold_conflict)
                }
            }
            throw "Gold-span bypass audit failed. Phase 2 privacy gate remains BLOCKED."
        }
        $gold = Get-Content -LiteralPath $goldPath -Raw | ConvertFrom-Json
        if ($gold.passed -ne $true -or [int]$gold.records -ne 6463 -or
                [int]$gold.bypass_uncovered_gold -ne 0 -or
                [int]$gold.deterministic_overmask -ne 0 -or
                [int]$gold.allow_gold_conflict -ne 0) {
            throw "Gold-span safety invariants failed."
        }

        $rules = Join-Path $root "examples\organization-rules.example.json"
        $reuseArgs = '"' + $ner + '" "' + $reusePath + '" ORG42 "' + $rules + '"'
        Write-Host "[CORPUS] Checking request-scoped scan parity on all 6463 records..."
        & mvn "-pl" "securelogx-core" "-Dexec.mainClass=com.securelogx.validation.Phase2LockedScanReuseAudit" "-Dexec.args=$reuseArgs" "org.codehaus.mojo:exec-maven-plugin:3.5.0:java"
        if ($LASTEXITCODE -ne 0) { throw "Locked scan reuse audit failed." }
        $reuse = Get-Content -LiteralPath $reusePath -Raw | ConvertFrom-Json
        if ($reuse.passed -ne $true -or [int]$reuse.record_count -ne 6463) {
            throw "Locked scan reuse evidence mismatch."
        }
        Write-Host "PHASE2 LOCKED DETERMINISTIC CORPUS GATES PASSED"
        Write-Host "Gold-span report: $goldPath"
        Write-Host "Reuse-handoff report: $reusePath"
        Write-Host "Full-corpus ONNX masked-output parity is NOT TESTED by these corpus gates."
    }
    Write-Host "Requested stages complete. Both experimental flags remain OFF by default."
} finally {
    Pop-Location
}
