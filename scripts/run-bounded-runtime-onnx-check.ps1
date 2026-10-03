param()

$ErrorActionPreference = "Stop"

$root = (Resolve-Path ".").Path
$resultPath = Join-Path $root "reports\bounded-runtime-onnx\result.json"
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
Write-Host "[1/2] Clean compiling bounded runtime validation..."
& mvn "-pl" "securelogx-core" "-DskipTests" "clean" "compile"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host ""
Write-Host "[2/2] Running real ONNX cancellation + saturation check..."
$execArgs = '"' + $resultPath + '"'
& mvn "-pl" "securelogx-core" "-Dexec.mainClass=com.securelogx.validation.BoundedRuntimeOnnxCheck" "-Dexec.args=$execArgs" "org.codehaus.mojo:exec-maven-plugin:3.5.0:java"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host ""
Write-Host "Bounded runtime real ONNX check complete."
Write-Host "Result: $resultPath"
