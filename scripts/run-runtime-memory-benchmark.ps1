param(
    [int]$SamplePerScenario = 160,
    [int]$Iterations = 3
)

$ErrorActionPreference = "Stop"

$root = (Resolve-Path ".").Path
$resultPath = Join-Path $root "reports\runtime-memory\result.json"

Write-Host "Active Maven/JDK:"
& mvn -version
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host ""
Write-Host "[1/2] Clean compiling runtime memory benchmark..."
& mvn "-pl" "securelogx-core" "-DskipTests" "clean" "compile"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host ""
Write-Host "[2/2] Running native-aware H1 runtime memory benchmark..."
$benchmarkArgs = '"' + $resultPath + '" ' + $SamplePerScenario + ' ' + $Iterations
& mvn "-pl" "securelogx-core" "-Dexec.mainClass=com.securelogx.validation.RuntimeMemoryBenchmark" "-Dexec.args=$benchmarkArgs" "org.codehaus.mojo:exec-maven-plugin:3.5.0:java"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host ""
Write-Host "Runtime memory benchmark complete."
Write-Host "Result: $resultPath"
