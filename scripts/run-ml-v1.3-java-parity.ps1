param(
    [string]$NerRoot = "..\\SecureLogX-NER"
)

$ErrorActionPreference = "Stop"

$secureLogXRoot = (Resolve-Path ".").Path
$nerRootResolved = (Resolve-Path $NerRoot).Path

Write-Host "[1/4] Generating Python reference fixture from SecureLogX-NER..."
Push-Location $nerRootResolved
try {
    python scripts\\export\\generate_securelogx_ml_v1_3_java_parity_fixture.py --root .
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}
finally {
    Pop-Location
}

$sourceModel = Join-Path $nerRootResolved "onnx-model\\ml-v1.3\\bert-base-cased\\model.onnx"
$sourceTokenizer = Join-Path $nerRootResolved "onnx-model\\ml-v1.3\\bert-base-cased\\tokenizer.json"
$sourceFixture = Join-Path $nerRootResolved "reports\\ml_v1_3_java_parity\\fixture.json"

foreach ($path in @($sourceModel, $sourceTokenizer, $sourceFixture)) {
    if (-not (Test-Path $path)) { throw "Required parity asset is missing: $path" }
}

$destDir = Join-Path $secureLogXRoot "onnx-model\\ml-v1.3\\bert-base-cased"
New-Item -ItemType Directory -Force -Path $destDir | Out-Null

$model = Join-Path $destDir "model.onnx"
$tokenizer = Join-Path $destDir "tokenizer.json"
$fixture = Join-Path $destDir "java-parity-fixture.json"
$result = Join-Path $secureLogXRoot "reports\\ml-v1.3-java-parity\\result.json"

Write-Host "[2/4] Syncing frozen ONNX/tokenizer/reference fixture..."
Copy-Item $sourceModel $model -Force
Copy-Item $sourceTokenizer $tokenizer -Force
Copy-Item $sourceFixture $fixture -Force

Write-Host "[3/4] Compiling SecureLogX Java 21 runtime..."
& mvn "-pl" "securelogx-core" "-DskipTests" "compile"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host "[4/4] Running Java cross-runtime parity..."
$execArgs = '"' + $model + '" "' + $tokenizer + '" "' + $fixture + '" "' + $result + '"'
& mvn "-pl" "securelogx-core" "-Dexec.mainClass=com.securelogx.validation.MlV13ParityCheck" "-Dexec.args=$execArgs" "org.codehaus.mojo:exec-maven-plugin:3.5.0:java"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host ""
Write-Host "Java parity report: $result"
