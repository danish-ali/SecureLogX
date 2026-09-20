$ErrorActionPreference = "Stop"

$root = (Resolve-Path ".").Path
$fixture = Join-Path $root "onnx-model\\ml-v1.3\\bert-base-cased\\java-parity-fixture.json"
$result = Join-Path $root "reports\\ml-v1.3-runtime-smoke\\result.json"

if (-not (Test-Path $fixture)) {
    throw "Java parity fixture is missing. Run scripts\\run-ml-v1.3-java-parity.ps1 first."
}

Write-Host "[0/3] Active Maven/JDK:"
& mvn -version
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host "[1/3] Cleaning stale bytecode and compiling SecureLogX for Java 21..."
& mvn "-pl" "securelogx-core" "-DskipTests" "clean" "compile"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host "[2/3] Verifying Java 21 class-file version..."
$javapOutput = & javap "-verbose" "-classpath" "securelogx-core\\target\\classes" "com.securelogx.validation.MlV13RuntimeSmokeCheck" 2>&1
if ($LASTEXITCODE -ne 0) { $javapOutput | Write-Host; exit $LASTEXITCODE }
$majorLine = $javapOutput | Select-String "major version:" | Select-Object -First 1
if ($null -eq $majorLine -or $majorLine.Line -notmatch "major version:\\s+65") {
    $javapOutput | Write-Host
    throw "Smoke-check class was not compiled as Java 21 bytecode (expected major version 65)."
}
Write-Host $majorLine.Line.Trim()

Write-Host "[3/3] Running ML-v1.3 production-path smoke check..."
$execArgs = '"' + $fixture + '" "' + $result + '"'
& mvn "-pl" "securelogx-core" "-Dexec.mainClass=com.securelogx.validation.MlV13RuntimeSmokeCheck" "-Dexec.args=$execArgs" "org.codehaus.mojo:exec-maven-plugin:3.5.0:java"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host ""
Write-Host "Runtime smoke report: $result"
