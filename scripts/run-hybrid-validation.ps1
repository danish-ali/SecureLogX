param(
    [string]$NerRoot = "..\\SecureLogX-NER"
)

$ErrorActionPreference = "Stop"

$root = (Resolve-Path ".").Path
$nerRootResolved = (Resolve-Path $NerRoot).Path
$logicResult = Join-Path $root "reports\\hybrid-detection-check\\result.json"
$gateResult = Join-Path $root "reports\\hybrid-gate-dataset-audit\\result.json"
$runtimeResult = Join-Path $root "reports\\hybrid-runtime-check\\result.json"
$goldFixture = Join-Path $nerRootResolved "reports\\ml_v1_3_hybrid_gate\\gold_fixture.json"
$model = Join-Path $root "onnx-model\\ml-v1.3\\bert-base-cased\\model.onnx"
$tokenizer = Join-Path $root "onnx-model\\ml-v1.3\\bert-base-cased\\tokenizer.json"

Write-Host "Active Maven/JDK:"
& mvn -version
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

foreach ($path in @($model, $tokenizer)) {
    if (-not (Test-Path $path)) {
        throw "Required validated ML-v1.3 artifact is missing: $path"
    }
}

Write-Host ""
Write-Host "[1/5] Generating non-sealed gold fixture from SecureLogX-NER..."
Push-Location $nerRootResolved
try {
    python scripts\\export\\generate_securelogx_ml_v1_3_hybrid_gate_fixture.py --root .
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}
finally {
    Pop-Location
}

if (-not (Test-Path $goldFixture)) {
    throw "Hybrid gate gold fixture was not generated: $goldFixture"
}

Write-Host ""
Write-Host "[2/5] Clean compiling hybrid SecureLogX runtime..."
& mvn "-pl" "securelogx-core" "-DskipTests" "clean" "compile"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host ""
Write-Host "[3/5] Running deterministic detector/resolver checks..."
$logicArgs = '"' + $logicResult + '"'
& mvn "-pl" "securelogx-core" "-Dexec.mainClass=com.securelogx.validation.HybridDetectionCheck" "-Dexec.args=$logicArgs" "org.codehaus.mojo:exec-maven-plugin:3.5.0:java"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host ""
Write-Host "[4/5] Running dataset-wide hybrid bypass security audit..."
$gateArgs = '"' + $goldFixture + '" "' + $gateResult + '"'
& mvn "-pl" "securelogx-core" "-Dexec.mainClass=com.securelogx.validation.HybridGateDatasetAudit" "-Dexec.args=$gateArgs" "org.codehaus.mojo:exec-maven-plugin:3.5.0:java"
if ($LASTEXITCODE -ne 0) {
    Write-Host ""
    Write-Host "Hybrid gate audit failed. Review: $gateResult"
    exit $LASTEXITCODE
}

Write-Host ""
Write-Host "[5/5] Running end-to-end hybrid ONNX routing check..."
$runtimeArgs = '"' + $runtimeResult + '"'
& mvn "-pl" "securelogx-core" "-Dexec.mainClass=com.securelogx.validation.HybridRuntimeCheck" "-Dexec.args=$runtimeArgs" "org.codehaus.mojo:exec-maven-plugin:3.5.0:java"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host ""
Write-Host "Hybrid validation complete."
Write-Host "Logic report: $logicResult"
Write-Host "Gate audit report: $gateResult"
Write-Host "Runtime report: $runtimeResult"
