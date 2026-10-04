param(
    [int]$Cycles = 4
)

$ErrorActionPreference = "Stop"

if ($Cycles -lt 2) {
    throw "Cycles must be at least 2."
}

$root = (Resolve-Path ".").Path
$resultPath = Join-Path $root "reports\log4j2-context-lifecycle\result.json"
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
Write-Host "[1/2] Clean installing Log4j2 lifecycle validation reactor..."
& mvn "-pl" "securelogx-log4j2" "-am" "-DskipTests" "clean" "install"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host ""
Write-Host "[2/2] Running repeated real-model LoggerContext lifecycle check..."
$execArgs = '"' + $resultPath + '" ' + $Cycles
& mvn "-pl" "securelogx-log4j2" "-Dexec.mainClass=com.securelogx.log4j2.SecureLogXContextLifecycleCheck" "-Dexec.args=$execArgs" "org.codehaus.mojo:exec-maven-plugin:3.5.0:java"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host ""
Write-Host "Log4j2 real context lifecycle check complete."
Write-Host "Result: $resultPath"
