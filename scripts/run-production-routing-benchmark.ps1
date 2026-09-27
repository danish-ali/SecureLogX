$ErrorActionPreference = "Stop"

$root = (Resolve-Path ".").Path
$result = Join-Path $root "reports\production-routing-benchmark\result.json"

Write-Host "Active Maven/JDK:"
& mvn -version
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host ""
Write-Host "[1/2] Clean compiling SecureLogX routing benchmark..."
& mvn "-pl" "securelogx-core" "-DskipTests" "clean" "compile"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host ""
Write-Host "[2/2] Running production routing benchmark..."
$benchmarkArgs = '"' + $result + '"'
& mvn "-pl" "securelogx-core" "-Dexec.mainClass=com.securelogx.validation.ProductionRoutingBenchmark" "-Dexec.args=$benchmarkArgs" "org.codehaus.mojo:exec-maven-plugin:3.5.0:java"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host ""
Write-Host "Production routing benchmark complete."
Write-Host "Result: $result"
