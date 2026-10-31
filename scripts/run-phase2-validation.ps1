# Execute Phase 2 validation on the Windows development host.
# Stages: Tests, Onnx, Corpus, All. All writes stay local.
# The corpus checks DO NOT establish complete ONNX redaction accuracy.
param(
    [ValidateSet("Tests","Onnx","Corpus","All")]
    [string]$Stage = "All",
    [string]$NerRoot = "..\SecureLogX-NER"
)
$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$branch = (& git -C $root branch --show-current).Trim()
if ($LASTEXITCODE -ne 0 -or $branch -ne "chatgpt/phase2-request-scoped-scan-reuse") {
    throw "Switch to chatgpt/phase2-request-scoped-scan-reuse before validation."
}
$sha = (& git -C $root rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0) { throw "Cannot resolve source SHA." }
$dirty = @(& git -C $root status --porcelain --untracked-files=no)
if ($LASTEXITCODE -ne 0) {
    throw "Unable to inspect Git working-tree status."
}
# Only the timed real-ONNX A/B study requires a clean source snapshot
# for comparable, source-fingerprinted performance evidence. Tests and
# the read-only corpus gate may run with tracked local changes, but their
# results describe that working tree rather than the published commit.
if ($dirty.Count -gt 0) {
    if ($Stage -in @("Onnx","All")) {
        throw "Matched ONNX A/B requires a clean tracked working tree. Run git status --short to inspect changes; preserve your edits before proceeding."
    }
    Write-Warning "Tracked changes detected. $Stage results describe the local working tree, not necessarily commit $sha."
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
        $runner = Join-Path $root "scripts\run-steady-onnx-study.ps1"
        $verify = Join-Path $root "scripts\verify-scan-reuse-output-parity.ps1"
        $studyDir = Join-Path $root "reports\parallel-onnx\steady"
        $zipDir = Join-Path $root "reports\parallel-onnx\.local-steady-runs"
        $common = @(
            "-Rates", "3,5", "-Repetitions", "2",
            "-DurationSeconds", "60", "-CallerMode", "platform",
            "-MaxOutstanding", "32", "-MaskingDeadlineMillis", "2000",
            "-CpuThreads", "4", "-WindowBatchLimit", "4",
            "-MaskingQueueCapacity", "64", "-MemorySampleMillis", "5000",
            "-RandomSeed", "20261026",
            "-UseExampleOrganizationRules", "-EnablePreQueueRouting"
        )

        Write-Host "[ONNX] Baseline: prequeue ON, reuse OFF"
        & powershell -NoProfile -ExecutionPolicy Bypass -File $runner @common
        if ($LASTEXITCODE -ne 0) { throw "Baseline study failed." }
        $baseline = @(Get-ChildItem -LiteralPath $studyDir -Filter "study-*.json" -File | Sort-Object LastWriteTimeUtc -Descending)[0]
        $off = Get-Content -LiteralPath $baseline.FullName -Raw | ConvertFrom-Json
        if ($off.source_commit_sha -cne $sha -or
                $off.experimental_pre_queue_enabled -ne $true -or
                $off.experimental_pre_queue_scan_reuse_enabled -ne $false) {
            throw "Baseline source or flags mismatch."
        }

        Write-Host "[ONNX] Optimized: prequeue ON, reuse ON"
        & powershell -NoProfile -ExecutionPolicy Bypass -File $runner @common -EnablePreQueueScanReuse
        if ($LASTEXITCODE -ne 0) { throw "Optimized study failed." }
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
