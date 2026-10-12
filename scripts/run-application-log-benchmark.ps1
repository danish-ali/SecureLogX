param(
    [int]$Events = 48,
    [int]$MaskingDeadlineMillis = 2000
)

$ErrorActionPreference = "Stop"

if ($Events -lt 12) {
    throw "Events must be at least 12."
}
if ($MaskingDeadlineMillis -lt 1) {
    throw "MaskingDeadlineMillis must be at least 1."
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
Write-Host "Masking request deadline for this benchmark run: $MaskingDeadlineMillis ms."
if ($MaskingDeadlineMillis -ne 2000) {
    Write-Warning "Characterization override: this run does NOT validate the 2000 ms pilot production deadline."
}
$execArgs = '"' + $resultPath + '" ' + $Events

# JAVA_TOOL_OPTIONS applies the property to this Maven JVM, including
# exec:java's in-process masking worker, without changing product defaults.
$previousJavaToolOptions = [Environment]::GetEnvironmentVariable("JAVA_TOOL_OPTIONS", "Process")
$deadlineOption = "-Dsecurelogx.runtime.maskingDeadlineMillis=$MaskingDeadlineMillis"
try {
    $env:JAVA_TOOL_OPTIONS = ((@($previousJavaToolOptions, $deadlineOption) |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) }) -join " ")
    & mvn "-pl" "securelogx-log4j2" "-Dexec.mainClass=com.securelogx.log4j2.SecureLogXApplicationLogBenchmark" "-Dexec.args=$execArgs" "org.codehaus.mojo:exec-maven-plugin:3.5.0:java"
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
} finally {
    if ($null -eq $previousJavaToolOptions) {
        Remove-Item Env:\JAVA_TOOL_OPTIONS -ErrorAction SilentlyContinue
    } else {
        $env:JAVA_TOOL_OPTIONS = $previousJavaToolOptions
    }
}

Write-Host ""
Write-Host "Representative application-log benchmark complete."
Write-Host "Result: $resultPath"
