param(
    [string]$NerRoot = "..\SecureLogX-NER"
)

$ErrorActionPreference = "Stop"

$root = (Resolve-Path ".").Path
$ner = (Resolve-Path $NerRoot).Path
$resultPath = Join-Path $root "reports\constrained-decoding\result.json"

Write-Host "Active Maven/JDK:"
& mvn -version
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host ""
Write-Host "[1/2] Clean compiling constrained decoding experiment..."
& mvn "-pl" "securelogx-core" "-DskipTests" "clean" "compile"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host ""
Write-Host "[2/2] Running full-corpus M0 argmax vs C1 BIO-Viterbi comparison..."
$benchmarkArgs = '"' + $ner + '" "' + $resultPath + '"'
& mvn "-pl" "securelogx-core" "-Dexec.mainClass=com.securelogx.validation.ConstrainedDecodingBenchmark" "-Dexec.args=$benchmarkArgs" "org.codehaus.mojo:exec-maven-plugin:3.5.0:java"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host ""
Write-Host "Constrained decoding experiment complete."
Write-Host "Result: $resultPath"
