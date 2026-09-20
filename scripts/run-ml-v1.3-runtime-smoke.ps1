$ErrorActionPreference = "Stop"

$root = (Resolve-Path ".").Path
$fixture = Join-Path $root "onnx-model\\ml-v1.3\\bert-base-cased\\java-parity-fixture.json"
$result = Join-Path $root "reports\\ml-v1.3-runtime-smoke\\result.json"

if (-not (Test-Path $fixture)) {
    throw "Java parity fixture is missing. Run scripts\\run-ml-v1.3-java-parity.ps1 first."
}

Write-Host "[1/2] Compiling SecureLogX Java 21 runtime..."
& mvn "-pl" "securelogx-core" "-DskipTests" "compile"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host "[2/2] Running ML-v1.3 production-path smoke check..."
$execArgs = '"' + $fixture + '" "' + $result + '"'
& mvn "-pl" "securelogx-core" "-Dexec.mainClass=com.securelogx.validation.MlV13RuntimeSmokeCheck" "-Dexec.args=$execArgs" "org.codehaus.mojo:exec-maven-plugin:3.5.0:java"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host ""
Write-Host "Runtime smoke report: $result"
