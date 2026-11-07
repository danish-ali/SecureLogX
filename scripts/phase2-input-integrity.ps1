# Shared SHA-256 gate for the Phase 2 real-ONNX benchmark inputs.
# The manifest digest is a trust anchor; change it only with reviewed study evidence.
$script:Phase2TrustedManifestSha256 = "B216328F269CF80A18C48085FF7460A48F62174B78C913434F10C61AE859AAD2"
$script:Phase2ExpectedEvidenceSnapshot = "07a8c5b417ffd3fc1d0b52c1c32dc99c6e13517f"
$script:Phase2ExpectedInputs = @(
    "onnx-model|onnx-model/ml-v1.3/bert-base-cased/model.onnx|5953ACD999083FA08C17C537E1D88C38F3C7137BC518022D8F3246C1133997C2",
    "tokenizer|onnx-model/ml-v1.3/bert-base-cased/tokenizer.json|B30EB8CA04D00E5330BADE5D5E6036BD0CE0AD68F7B96E2E38BFF11942EE30B1",
    "onnx-parity-fixture|onnx-model/ml-v1.3/bert-base-cased/java-parity-fixture.json|D29EA8F19C0904558B510C2CC30196303655F7ABC5E6BE9C825B36486CA479D4",
    "securelogx-runtime-config|securelogx-core/src/main/resources/securelogx-dev.properties|BC946E171E3AEABEF755329542D9266BF475F217F5D6FAE0A7FBEA4A827D613A",
    "example-organization-policy|examples/organization-rules.example.json|63248BA0390CCACD3C3629B4CD6A73B1FE0EDD0A896B916B7FFBC3A9E81A2EC6"
)
$script:Phase2ExpectedConfiguration = @{
    rates_per_second = "3,5"; repetitions = 2; duration_seconds = 60
    caller_mode = "platform"; max_outstanding = 32; deadline_millis = 2000
    cpu_threads = 4; max_windows_per_batch = 4
    masking_queue_capacity = 64; memory_sample_interval_millis = 5000
    random_seed = 20261026; organization_rules_enabled = $true
    pre_queue_routing_enabled = $true; scan_reuse_baseline = $false
    scan_reuse_optimized = $true
}

function Test-Phase2DirtyPathAllowed {
    [CmdletBinding()]
    param([Parameter(Mandatory)][string]$Path)
    $norm = ($Path -replace '\\', '/').Trim()
    if ($norm.StartsWith('./')) { $norm = $norm.Substring(2) }
    if ([string]::IsNullOrWhiteSpace($norm)) { return $false }
    if ($norm -ceq '.idea' -or $norm.StartsWith('.idea/')) { return $true }
    if ($norm -match '(^|/)target/') { return $true }
    if ($norm.StartsWith('reports/')) { return $true }
    if ($norm -in @(
        'onnx-model/ml-v1.3/bert-base-cased/model.onnx',
        'onnx-model/ml-v1.3/bert-base-cased/tokenizer.json',
        'onnx-model/ml-v1.3/bert-base-cased/java-parity-fixture.json'
    )) { return $true }
    if ($norm.StartsWith('logs/')) { return $true }
    return $false
}

function Get-Phase2CanonicalTextSha256 {
    param([Parameter(Mandatory)][string]$Path)
    $text = [System.IO.File]::ReadAllText($Path)
    $canonical = $text.Replace("`r`n", "`n").Replace("`r", "`n")
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($canonical)
    $hasher = [System.Security.Cryptography.SHA256]::Create()
    try {
        return ([System.BitConverter]::ToString($hasher.ComputeHash($bytes))).Replace("-", "")
    } finally {
        $hasher.Dispose()
    }
}

function Test-Phase2BenchmarkInputs {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$RepoRoot,
        [string]$ManifestPath = "scripts/phase2-benchmark-inputs.json",
        [string]$TrustedManifestSha256 = $script:Phase2TrustedManifestSha256,
        [string]$EvidenceSnapshot = $script:Phase2ExpectedEvidenceSnapshot,
        [string[]]$ExpectedInputs = $script:Phase2ExpectedInputs,
        [System.Collections.IDictionary]$ExpectedConfiguration = $script:Phase2ExpectedConfiguration
    )

    $root = [System.IO.Path]::GetFullPath($RepoRoot)
    $manifestFullPath = [System.IO.Path]::GetFullPath((Join-Path $root $ManifestPath))
    if (-not (Test-Path -LiteralPath $manifestFullPath -PathType Leaf)) {
        throw "Trusted benchmark manifest missing: $ManifestPath"
    }
    $manifestHash = (Get-Phase2CanonicalTextSha256 -Path $manifestFullPath).ToUpperInvariant()
    if ($manifestHash -cne $TrustedManifestSha256.ToUpperInvariant()) {
        throw "Trusted benchmark manifest mismatch: $ManifestPath; expected=$($TrustedManifestSha256.ToUpperInvariant()) actual=$manifestHash"
    }

    try {
        $manifest = Get-Content -LiteralPath $manifestFullPath -Raw | ConvertFrom-Json -ErrorAction Stop
    } catch {
        throw "Trusted benchmark manifest is invalid: $ManifestPath"
    }
    if ($manifest.kind -cne "securelogx-phase2-benchmark-inputs" -or
        [int]$manifest.schema_version -ne 1 -or
        $manifest.evidence_snapshot -cne $EvidenceSnapshot) {
        throw "Trusted benchmark manifest schema or evidence snapshot mismatch: $ManifestPath"
    }

    $actualInputs = @($manifest.inputs | ForEach-Object {
        "$($_.name)|$($_.path)|$($_.sha256.ToUpperInvariant())"
    })
    if (($actualInputs -join "`n") -cne ($ExpectedInputs -join "`n")) {
        throw "Trusted benchmark manifest input set mismatch: $ManifestPath"
    }
    foreach ($key in $ExpectedConfiguration.Keys) {
        $actual = $manifest.phase2.$key
        if ($key -eq "rates_per_second") { $actual = (@($actual) -join ",") }
        if ($actual -cne $ExpectedConfiguration[$key]) {
            throw "Trusted benchmark manifest configuration mismatch: phase2.$key"
        }
    }

    foreach ($input in $manifest.inputs) {
        $relative = ([string]$input.path).Replace('\', '/')
        if ([System.IO.Path]::IsPathRooted($relative) -or $relative -match '(^|/)\.\.(?:/|$)') {
            throw "Trusted benchmark input path is unsafe: $($input.name)"
        }
        $file = Join-Path $root ($relative.Replace('/', [System.IO.Path]::DirectorySeparatorChar))
        if (-not (Test-Path -LiteralPath $file -PathType Leaf)) {
            throw "Benchmark input missing: $($input.name); expected=$($input.sha256.ToUpperInvariant()) actual=MISSING"
        }
        # Get-FileHash opens the final file through a directory junction, so the
        # digest applies to the bytes ONNX Runtime will read at this repo-relative path.
        $actual = (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToUpperInvariant()
        $expected = ([string]$input.sha256).ToUpperInvariant()
        if ($actual -cne $expected) {
            throw "Benchmark input hash mismatch: $($input.name); expected=$expected actual=$actual"
        }
    }
    return $true
}
