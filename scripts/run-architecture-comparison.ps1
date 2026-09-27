param(
    [string]$NerRoot = "..\\SecureLogX-NER"
)

$ErrorActionPreference = "Stop"

$nerRootResolved = (Resolve-Path $NerRoot).Path
$resultPath = (Join-Path (Resolve-Path ".").Path "reports\\architecture-comparison\\result.json")

mvn -pl securelogx-core -DskipTests clean compile
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$argsText = '"' + $nerRootResolved + '" "' + $resultPath + '"'
mvn -pl securelogx-core "-Dexec.mainClass=com.securelogx.validation.ArchitectureComparisonBenchmark" "-Dexec.args=$argsText" org.codehaus.mojo:exec-maven-plugin:3.5.0:java
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host "Architecture comparison complete."
Write-Host "Result: $resultPath"
