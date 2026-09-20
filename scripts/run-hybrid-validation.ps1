$ErrorActionPreference = "Stop"

$root = (Resolve-Path ".").Path
$logicResult = Join-Path $root "reports\\hybrid-detection-check\\result.json"
$runtimeResult = Join-Path $root "reports\\hybrid-runtime-check\\result.json"
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
Write-Host "[1/3] Clean compiling hybrid SecureLogX runtime..."
& mvn "-pl" "securelogx-core" "-DskipTests" "clean" "compile"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host ""
Write-Host "[2/3] Running deterministic detector/resolver checks..."
$logicArgs = '"' + $logicResult + '"'
& mvn "-pl" "securelogx-core" "-Dexec.mainClass=com.securelogx.validation.HybridDetectionCheck" "-Dexec.args=$logicArgs" "org.codehaus.mojo:exec-maven-plugin:3.5.0:java"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host ""
Write-Host "[3/3] Running end-to-end hybrid ONNX routing check..."
$runtimeArgs = '"' + $runtimeResult + '"'
& mvn "-pl" "securelogx-core" "-Dexec.mainClass=com.securelogx.validation.HybridRuntimeCheck" "-Dexec.args=$runtimeArgs" "org.codehaus.mojo:exec-maven-plugin:3.5.0:java"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host ""
Write-Host "Hybrid validation complete."
Write-Host "Logic report: $logicResult"
Write-Host "Runtime report: $runtimeResult"
