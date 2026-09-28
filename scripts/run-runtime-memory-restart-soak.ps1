param(
    [int]$Cycles = 4,
    [int]$SamplePerScenario = 80
)

$ErrorActionPreference = "Stop"

$root = (Resolve-Path ".").Path
$resultPath = Join-Path $root "reports\runtime-memory-restart-soak\result.json"

Write-Host "Active Maven/JDK:"
& mvn -version
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host ""
Write-Host "[1/2] Clean compiling restart memory soak benchmark..."
& mvn "-pl" "securelogx-core" "-DskipTests" "clean" "compile"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host ""
Write-Host "[2/2] Running ONNX restart/native-memory soak benchmark..."
$benchmarkArgs = '"' + $resultPath + '" ' + $Cycles + ' ' + $SamplePerScenario
& mvn "-pl" "securelogx-core" "-Dexec.mainClass=com.securelogx.validation.RuntimeMemoryRestartSoakBenchmark" "-Dexec.args=$benchmarkArgs" "org.codehaus.mojo:exec-maven-plugin:3.5.0:java"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host ""
Write-Host "Runtime memory restart soak complete."
Write-Host "Result: $resultPath"
