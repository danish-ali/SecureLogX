$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "phase2-input-integrity.ps1")

$tempRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("securelogx-phase2-integrity-" + [guid]::NewGuid().ToString("N"))
$repo = Join-Path $tempRoot "repo"
$externalModelDir = Join-Path $tempRoot "external-model"
New-Item -ItemType Directory -Path $repo -Force | Out-Null
New-Item -ItemType Directory -Path $externalModelDir -Force | Out-Null
$counts = [ordered]@{ passed = 0; failed = 0 }

function Assert-Phase2 {
    param([string]$Name, [scriptblock]$Action, [switch]$ExpectBlock, [string]$MessagePattern)
    $blocked = $false
    $message = ""
    try {
        & $Action | Out-Null
    } catch {
        $blocked = $true
        $message = $_.Exception.Message
    }
    if (-not $ExpectBlock -and -not $blocked) {
        $counts.passed++
        Write-Host "PASS $Name"
    } elseif (-not $ExpectBlock) {
        $counts.failed++
        Write-Host "FAIL $Name : $message"
    } elseif (-not $blocked) {
        $counts.failed++
        Write-Host "FAIL $Name : expected block did not occur"
    } elseif ($MessagePattern -and $message -notmatch $MessagePattern) {
        $counts.failed++
        Write-Host "FAIL $Name : unexpected block evidence: $message"
    } else {
        $counts.passed++
        Write-Host "PASS $Name (blocked as expected)"
    }
}

try {
    $inputSpecs = @(
        @{ name = "onnx-model"; path = "onnx-model/ml-v1.3/bert-base-cased/model.onnx"; content = "synthetic-model-v1" },
        @{ name = "tokenizer"; path = "onnx-model/ml-v1.3/bert-base-cased/tokenizer.json"; content = "synthetic-tokenizer-v1" },
        @{ name = "securelogx-runtime-config"; path = "securelogx-core/src/main/resources/securelogx-dev.properties"; content = "synthetic-config-v1" },
        @{ name = "example-organization-policy"; path = "examples/organization-rules.example.json"; content = "synthetic-policy-v1" }
    )
    $expectedInputs = [System.Collections.Generic.List[string]]::new()
    $manifestInputs = [System.Collections.Generic.List[object]]::new()
    foreach ($spec in $inputSpecs) {
        $path = Join-Path $repo ($spec.path.Replace('/', [System.IO.Path]::DirectorySeparatorChar))
        New-Item -ItemType Directory -Path (Split-Path -Parent $path) -Force | Out-Null
        [System.IO.File]::WriteAllText($path, $spec.content)
        $hash = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToUpperInvariant()
        $expectedInputs.Add("$($spec.name)|$($spec.path)|$hash")
        $manifestInputs.Add([pscustomobject]@{ name=$spec.name; path=$spec.path; sha256=$hash })
    }
    $phase2 = [ordered]@{
        rates_per_second=@(3,5); repetitions=2; duration_seconds=60; caller_mode="platform"
        max_outstanding=32; deadline_millis=2000; cpu_threads=4; max_windows_per_batch=4
        masking_queue_capacity=64; memory_sample_interval_millis=5000; random_seed=20261026
        organization_rules_enabled=$true; pre_queue_routing_enabled=$true
        scan_reuse_baseline=$false; scan_reuse_optimized=$true
    }
    $fixtureManifestPath = Join-Path $repo "scripts/phase2-benchmark-inputs.json"
    New-Item -ItemType Directory -Path (Split-Path -Parent $fixtureManifestPath) -Force | Out-Null
    $manifest = [ordered]@{
        kind="securelogx-phase2-benchmark-inputs"; schema_version=1
        evidence_snapshot="synthetic-test-snapshot"; inputs=@($manifestInputs.ToArray()); phase2=$phase2
    }
    $manifestJson = $manifest | ConvertTo-Json -Depth 8
    [System.IO.File]::WriteAllText($fixtureManifestPath, $manifestJson)
    $trustedManifestHash = Get-Phase2CanonicalTextSha256 -Path $fixtureManifestPath
    $fixtureConfig = @{
        rates_per_second="3,5"; repetitions=2; duration_seconds=60; caller_mode="platform"
        max_outstanding=32; deadline_millis=2000; cpu_threads=4; max_windows_per_batch=4
        masking_queue_capacity=64; memory_sample_interval_millis=5000; random_seed=20261026
        organization_rules_enabled=$true; pre_queue_routing_enabled=$true
        scan_reuse_baseline=$false; scan_reuse_optimized=$true
    }
    $check = {
        Test-Phase2BenchmarkInputs -RepoRoot $repo -ManifestPath "scripts/phase2-benchmark-inputs.json" `
            -TrustedManifestSha256 $trustedManifestHash -EvidenceSnapshot "synthetic-test-snapshot" `
            -ExpectedInputs $expectedInputs.ToArray() -ExpectedConfiguration $fixtureConfig
    }

    Assert-Phase2 "synthetic model/tokenizer/config/policy exact hashes" $check

    # Junction tests use a separate physical directory for the model bytes.
    $modelDirectory = Join-Path $repo "onnx-model/ml-v1.3/bert-base-cased"
    $physicalModelDirectory = Join-Path $externalModelDir "bert-base-cased"
    $modelLogical = Join-Path $modelDirectory "model.onnx"
    $modelPhysical = Join-Path $physicalModelDirectory "model.onnx"
    Move-Item -LiteralPath $modelDirectory -Destination $physicalModelDirectory
    New-Item -ItemType Junction -Path $modelDirectory -Target $physicalModelDirectory | Out-Null
    Assert-Phase2 "directory-junction model hash" $check
    [System.IO.File]::AppendAllText($modelPhysical, "changed")
    Assert-Phase2 "modified model hash" $check -ExpectBlock -MessagePattern "onnx-model.*expected=.*actual="
    # Restore a known synthetic byte string so subsequent checks stay deterministic.
    [System.IO.File]::WriteAllText($modelPhysical, "synthetic-model-v1")

    $tokenizerPath = Join-Path $repo "onnx-model/ml-v1.3/bert-base-cased/tokenizer.json"
    [System.IO.File]::AppendAllText($tokenizerPath, "changed")
    Assert-Phase2 "modified tokenizer hash" $check -ExpectBlock -MessagePattern "tokenizer.*expected=.*actual="
    [System.IO.File]::WriteAllText($tokenizerPath, "synthetic-tokenizer-v1")

    $policyPath = Join-Path $repo "examples/organization-rules.example.json"
    [System.IO.File]::AppendAllText($policyPath, "changed")
    Assert-Phase2 "modified policy hash" $check -ExpectBlock -MessagePattern "example-organization-policy.*expected=.*actual="
    [System.IO.File]::WriteAllText($policyPath, "synthetic-policy-v1")
    $configPath = Join-Path $repo "securelogx-core/src/main/resources/securelogx-dev.properties"
    [System.IO.File]::AppendAllText($configPath, "changed")
    Assert-Phase2 "modified configuration hash" $check -ExpectBlock -MessagePattern "securelogx-runtime-config.*expected=.*actual="
    [System.IO.File]::WriteAllText($configPath, "synthetic-config-v1")

    Assert-Phase2 "added untracked Java file blocks" { if (Test-Phase2DirtyPathAllowed "src/main/java/New.java") { throw "Java path allowed" } }
    Assert-Phase2 "modified POM blocks" { if (Test-Phase2DirtyPathAllowed "securelogx-core/pom.xml") { throw "POM path allowed" } }
    Assert-Phase2 "modified PowerShell blocks" { if (Test-Phase2DirtyPathAllowed "scripts/run-steady-onnx-study.ps1") { throw "PowerShell path allowed" } }
    Assert-Phase2 "modified security policy blocks" { if (Test-Phase2DirtyPathAllowed "examples/organization-rules.example.json") { throw "Policy path allowed" } }
    Assert-Phase2 "modified benchmark config blocks" { if (Test-Phase2DirtyPathAllowed "scripts/phase2-benchmark-inputs.json") { throw "Config path allowed" } }
    Assert-Phase2 "harmless IDE output allowed" { Test-Phase2DirtyPathAllowed ".idea/workspace.xml" }
    Assert-Phase2 "harmless Maven output allowed" { Test-Phase2DirtyPathAllowed "securelogx-core/target/classes/App.class" }

    $savedManifest = [System.IO.File]::ReadAllText($fixtureManifestPath)
    [System.IO.File]::WriteAllText($fixtureManifestPath, $savedManifest + " ")
    Assert-Phase2 "modified trusted manifest blocks" $check -ExpectBlock -MessagePattern "Trusted benchmark manifest mismatch"
    Remove-Item -LiteralPath $fixtureManifestPath
    Assert-Phase2 "missing trusted manifest blocks" $check -ExpectBlock -MessagePattern "Trusted benchmark manifest missing"
    [System.IO.File]::WriteAllText($fixtureManifestPath, $savedManifest)

    # Simulate the wrapper's post-baseline check before the optimized arm.
    Assert-Phase2 "baseline input check" $check
    [System.IO.File]::AppendAllText($policyPath, "changed-between-arms")
    Assert-Phase2 "changed input between arms blocks" $check -ExpectBlock -MessagePattern "example-organization-policy.*expected=.*actual="

    Write-Host ("INTEGRITY TESTS: passed={0} failed={1}" -f $counts.passed, $counts.failed)
    if ($counts.failed -ne 0) { exit 1 }
} finally {
    $resolvedTemp = [System.IO.Path]::GetFullPath($tempRoot)
    $tempPrefix = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())
    if (-not $resolvedTemp.StartsWith($tempPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to remove test directory outside TEMP."
    }
    $junction = Join-Path $repo "onnx-model/ml-v1.3/bert-base-cased"
    if (Test-Path -LiteralPath $junction) {
        $item = Get-Item -LiteralPath $junction -Force
        if ($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) {
            [System.IO.Directory]::Delete($junction, $false)
        }
    }
    Remove-Item -LiteralPath $resolvedTemp -Recurse -Force -ErrorAction SilentlyContinue
}
