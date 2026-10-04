param(
    [int]$BaselineRequests = 8,
    [int]$ConcurrentCallers = 12,
    [int]$AttemptsPerCaller = 4
)

$ErrorActionPreference = "Stop"

$root = (Resolve-Path ".").Path
$resultPath = Join-Path $root "reports\bounded-runtime-load\result.json"
$model = Join-Path $root "onnx-model\ml-v1.3\bert-base-cased\model.onnx"
$tokenizer = Join-Path $root "onnx-model\ml-v1.3\bert-base-cased\tokenizer.json"

Write-Host "Active Maven/JDK:"
& mvn -version
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

foreach ($path in @($model, $tokenizer)) {
    if (-not (Test-Path $path)) {
        throw "Required validated ML-v1.3 artifact is missing: $path"
    }
}

Write-Host ""
Write-Host "[1/2] Clean compiling bounded-runtime load characterization..."
& mvn "-pl" "securelogx-core" "-DskipTests" "clean" "compile"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host ""
Write-Host "[2/2] Running real-model bounded-runtime load characterization..."
$execArgs = '"' + $resultPath + '" ' + $BaselineRequests + ' ' + $ConcurrentCallers + ' ' + $AttemptsPerCaller
& mvn "-pl" "securelogx-core" "-Dexec.mainClass=com.securelogx.validation.BoundedRuntimeLoadCheck" "-Dexec.args=$execArgs" "org.codehaus.mojo:exec-maven-plugin:3.5.0:java"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host ""
Write-Host "Bounded-runtime load characterization complete."
Write-Host "Result: $resultPath"
