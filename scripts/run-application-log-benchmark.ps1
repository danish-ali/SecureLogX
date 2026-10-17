param(
    [int]$Events = 48,
    [int]$MaskingDeadlineMillis = 2000,
    [switch]$CollectMlRuleProposals,
    [switch]$UseExampleOrganizationRules,
    [ValidateRange(0, 64)][int]$CpuThreads = 0,
    [ValidateRange(0, 32)][int]$WindowBatchLimit = 0,
    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9_-]{0,39}$')]
    [string]$RunLabel,
    [switch]$SkipArchive,
    [switch]$IncludeExistingReports,
    [switch]$CommitResults,
    [switch]$PushResults
)

$ErrorActionPreference = "Stop"

if ($PushResults -and -not $CommitResults) {
    throw "-PushResults requires -CommitResults."
}
if ($SkipArchive -and ($CommitResults -or $PushResults)) {
    throw "Cannot commit or push results when -SkipArchive is set."
}

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
Write-Host "Aggregate ML routing telemetry: $([bool]$CollectMlRuleProposals)"
Write-Host "Example organization rules enabled: $([bool]$UseExampleOrganizationRules)"
if ($CpuThreads -gt 0) {
    Write-Host "Benchmark CPU intra-op maximum: $CpuThreads threads"
}
if ($WindowBatchLimit -gt 0) {
    Write-Host "Benchmark ONNX micro-batch maximum: $WindowBatchLimit windows"
    if ($WindowBatchLimit -gt 4) {
        Write-Warning "Larger ONNX micro-batches require renewed native-memory and safety validation."
    }
}
if ($MaskingDeadlineMillis -ne 2000) {
    Write-Warning "Characterization override: this run does NOT validate the 2000 ms pilot production deadline."
}
$execArgs = '"' + $resultPath + '" ' + $Events

# JAVA_TOOL_OPTIONS applies the property to this Maven JVM, including
# exec:java's in-process masking worker, without changing product defaults.
$previousJavaToolOptions = [Environment]::GetEnvironmentVariable("JAVA_TOOL_OPTIONS", "Process")
$deadlineOption = "-Dsecurelogx.runtime.maskingDeadlineMillis=$MaskingDeadlineMillis"
$telemetryOption = if ($CollectMlRuleProposals) {
    "-Dsecurelogx.adaptive.mlRoutingTelemetryEnabled=true"
} else {
    "-Dsecurelogx.adaptive.mlRoutingTelemetryEnabled=false"
}
$organizationOptions = @()
if ($UseExampleOrganizationRules) {
    $examplePolicy = Join-Path $root "examples\organization-rules.example.json"
    if (-not (Test-Path $examplePolicy)) {
        throw "Example organization policy missing: $examplePolicy"
    }
    # The sample is repository-controlled synthetic test data. It is never
    # automatically enabled for production deployments.
    $organizationOptions = @(
        "-Dsecurelogx.adaptive.enabled=true",
        "-Dsecurelogx.organization.id=ORG42",
        "-Dsecurelogx.organization.approvedRulesFile=examples/organization-rules.example.json"
    )
}
$performanceOptions = @()
if ($CpuThreads -gt 0) {
    $performanceOptions += "-Dsecurelogx.maxCpuThreads=$CpuThreads"
}
if ($WindowBatchLimit -gt 0) {
    $performanceOptions += "-Dsecurelogx.model.maxInferenceWindowsPerBatch=$WindowBatchLimit"
}
$benchmarkExitCode = 0
try {
    $javaOptions = @($previousJavaToolOptions, $deadlineOption, $telemetryOption) + $organizationOptions + $performanceOptions
    $env:JAVA_TOOL_OPTIONS = (($javaOptions |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) }) -join " ")
    & mvn "-pl" "securelogx-log4j2" "-Dexec.mainClass=com.securelogx.log4j2.SecureLogXApplicationLogBenchmark" "-Dexec.args=$execArgs" "org.codehaus.mojo:exec-maven-plugin:3.5.0:java"
    $benchmarkExitCode = $LASTEXITCODE
} finally {
    if ($null -eq $previousJavaToolOptions) {
        Remove-Item Env:\JAVA_TOOL_OPTIONS -ErrorAction SilentlyContinue
    } else {
        $env:JAVA_TOOL_OPTIONS = $previousJavaToolOptions
    }
}
if ($benchmarkExitCode -ne 0) { exit $benchmarkExitCode }

Write-Host ""
Write-Host "Representative application-log benchmark complete."
Write-Host "Result: $resultPath"

if (-not $SkipArchive) {
    # Archive is on by default for every successful benchmark run.
    $cpuLabel = if ($CpuThreads -gt 0) { "c$CpuThreads" } else { "cdefault" }
    $windowLabel = if ($WindowBatchLimit -gt 0) {
        "w$WindowBatchLimit"
    } else {
        "wdefault"
    }
    $orgLabel = if ($UseExampleOrganizationRules) {
        "org-on"
    } else {
        "org-off"
    }
    $effectiveLabel = if ([string]::IsNullOrWhiteSpace($RunLabel)) {
        "$cpuLabel-$windowLabel-d$MaskingDeadlineMillis-$orgLabel"
    } else {
        $RunLabel
    }

    & (Join-Path $PSScriptRoot "archive-application-log-results.ps1") -RunLabel $effectiveLabel -IncludeExistingReports:$IncludeExistingReports -CommitResults:$CommitResults -PushResults:$PushResults
}

