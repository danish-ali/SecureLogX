param(
    [int]$Events = 48
)

$ErrorActionPreference = "Stop"

if ($Events -lt 12) {
    throw "Events must be at least 12."
}

$root = (Resolve-Path ".").Path
$resultPath = Join-Path $root "reports\application-log-benchmark\result.json"
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
Write-Host "[1/2] Clean installing representative application benchmark reactor..."
& mvn "-pl" "securelogx-log4j2" "-am" "-DskipTests" "clean" "install"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host ""
Write-Host "[2/2] Running real Log4j application benchmark..."
$execArgs = '"' + $resultPath + '" ' + $Events
& mvn "-pl" "securelogx-log4j2" "-Dexec.mainClass=com.securelogx.log4j2.SecureLogXApplicationLogBenchmark" "-Dexec.args=$execArgs" "org.codehaus.mojo:exec-maven-plugin:3.5.0:java"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host ""
Write-Host "Representative application-log benchmark complete."
Write-Host "Result: $resultPath"
