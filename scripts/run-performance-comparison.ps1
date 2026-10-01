param(
    [int]$SamplePerScenario = 160,
    [int]$Iterations = 6
)

$ErrorActionPreference = "Stop"

if ($Iterations -lt 2 -or ($Iterations % 2) -ne 0) {
    throw "Iterations must be an even integer >= 2 because M0/H1 execution order is balanced across rounds."
}

$root = (Resolve-Path ".").Path
$resultPath = Join-Path $root "reports\performance-comparison\result.json"

Write-Host "Active Maven/JDK:"
& mvn -version
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host ""
Write-Host "[1/2] Clean compiling performance comparison..."
& mvn "-pl" "securelogx-core" "-DskipTests" "clean" "compile"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host ""
Write-Host "[2/2] Running M0 vs H1 performance comparison..."
$benchmarkArgs = '"' + $resultPath + '" ' + $SamplePerScenario + ' ' + $Iterations
& mvn "-pl" "securelogx-core" "-Dexec.mainClass=com.securelogx.validation.PerformanceComparisonBenchmark" "-Dexec.args=$benchmarkArgs" "org.codehaus.mojo:exec-maven-plugin:3.5.0:java"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host ""
Write-Host "Performance comparison complete."
Write-Host "Result: $resultPath"
