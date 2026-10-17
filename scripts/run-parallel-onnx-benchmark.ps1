param(
    # A comma-separated STRING is intentional: Windows PowerShell
    # powershell.exe -File does not reliably bind array-valued arguments.
    [ValidatePattern('^[0-9]{1,3}(,[0-9]{1,3})*$')]
    [string]$Callers = "10,50,100",
    [ValidateRange(12, 5000)][int]$Events = 200,
    [ValidateSet("platform", "virtual")][string]$CallerMode = "platform",
    [ValidateRange(1, 60000)][int]$MaskingDeadlineMillis = 2000,
    [ValidateRange(1, 64)][int]$CpuThreads = 4,
    [ValidateRange(1, 32)][int]$WindowBatchLimit = 4,
    [ValidateRange(1, 10000)][int]$MaskingQueueCapacity = 64,
    [switch]$CollectMlRuleProposals,
    [switch]$UseExampleOrganizationRules,
    [switch]$CommitResults,
    [switch]$PushResults
)

$ErrorActionPreference = "Stop"
if ($PushResults -and -not $CommitResults) {
    throw "-PushResults requires -CommitResults."
}
$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$reportRoot = Join-Path $root "reports\parallel-onnx\history"
$model = Join-Path $root "onnx-model\ml-v1.3\bert-base-cased\model.onnx"
$tokenizer = Join-Path $root "onnx-model\ml-v1.3\bert-base-cased\tokenizer.json"

$callerCounts = @(
    foreach ($rawCount in $Callers.Split(',')) {
        [int]$count = 0
        if (-not [int]::TryParse($rawCount, [ref]$count) -or
                $count -lt 1 -or $count -gt 200 -or $count -gt $Events) {
            throw "Each caller count must be 1..min(200,Events): $rawCount"
        }
        $count
    }
)
if (@($callerCounts | Select-Object -Unique).Count -ne $callerCounts.Count) {
    throw "Caller counts must not repeat in one run: $Callers"
}
foreach ($artifact in @($model, $tokenizer)) {
    if (-not (Test-Path -LiteralPath $artifact -PathType Leaf)) {
        throw "Missing validated real ONNX artifact: $artifact"
    }
}
if ($CommitResults) {
    $currentBranch = (& git -C $root branch --show-current).Trim()
    if ($LASTEXITCODE -ne 0 -or
            $currentBranch -ne "chatgpt/hybrid-detection-pipeline") {
        throw "Result commits require chatgpt/hybrid-detection-pipeline, got: $currentBranch"
    }
}

Push-Location $root
try {
    Write-Host "Active Maven / JDK:"
    & mvn "-version"
    if ($LASTEXITCODE -ne 0) {
        throw "Maven is unavailable."
    }
    Write-Host "Compiling real-ONNX Log4j benchmark reactor (native session loads during each run)..."
    & mvn "-pl" "securelogx-log4j2" "-am" "-DskipTests" "install"
    if ($LASTEXITCODE -ne 0) {
        throw "Maven build failed: parallel ONNX benchmark not run."
    }

    $previousJavaOptions = [Environment]::GetEnvironmentVariable(
        "JAVA_TOOL_OPTIONS", "Process"
    )
    try {
        $orgOptions = @()
        if ($UseExampleOrganizationRules) {
            $samplePolicy = Join-Path $root "examples\organization-rules.example.json"
            if (-not (Test-Path -LiteralPath $samplePolicy -PathType Leaf)) {
                throw "Missing synthetic org rules: $samplePolicy"
            }
            $orgOptions = @(
                "-Dsecurelogx.adaptive.enabled=true",
                "-Dsecurelogx.organization.id=ORG42",
                "-Dsecurelogx.organization.approvedRulesFile=examples/organization-rules.example.json"
            )
        }
        $telemetry = if ($CollectMlRuleProposals) {
            "-Dsecurelogx.adaptive.mlRoutingTelemetryEnabled=true"
        } else {
            "-Dsecurelogx.adaptive.mlRoutingTelemetryEnabled=false"
        }

        $javaOptions = @(
            $previousJavaOptions,
            "-Dsecurelogx.maxCpuThreads=$CpuThreads",
            "-Dsecurelogx.model.maxInferenceWindowsPerBatch=$WindowBatchLimit",
            "-Dsecurelogx.runtime.maskingQueueCapacity=$MaskingQueueCapacity",
            "-Dsecurelogx.runtime.maskingDeadlineMillis=$MaskingDeadlineMillis",
            $telemetry
        ) + $orgOptions
        $env:JAVA_TOOL_OPTIONS = (
            $javaOptions |
                Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
        ) -join " "

        $commitSha = (& git -C $root rev-parse HEAD).Trim()
        if ($LASTEXITCODE -ne 0) {
            throw "Unable to record benchmark source revision."
        }
        foreach ($count in $callerCounts) {
            $time = [DateTime]::UtcNow.ToString(
                "yyyyMMddTHHmmssZ",
                [System.Globalization.CultureInfo]::InvariantCulture
            )
            $suffix = [guid]::NewGuid().ToString("N").Substring(0, 12)
            $runId = "$time-c$count-$CallerMode-cpu$CpuThreads-w$WindowBatchLimit-d$MaskingDeadlineMillis-$suffix"
            $relative = "reports/parallel-onnx/history/$runId"
            $runDirectory = Join-Path $reportRoot $runId
            if (Test-Path -LiteralPath $runDirectory) {
                throw "Refusing to overwrite an existing parallel ONNX run: $runDirectory"
            }
            New-Item -ItemType Directory -Path $runDirectory |
                Out-Null

            $resultPath = Join-Path $runDirectory "result-$runId.json"
            $protectedPath = Join-Path $runDirectory "protected-application.log"
            $execArgs = '"' + $resultPath + '" ' + $Events + ' ' + $count + ' ' + $CallerMode

            Write-Host ""
            Write-Host "Running real ONNX parallel workload: $count $CallerMode callers, $Events events"
            & mvn "-pl" "securelogx-log4j2" "-Dexec.mainClass=com.securelogx.log4j2.SecureLogXParallelOnnxBenchmark" "-Dexec.args=$execArgs" "org.codehaus.mojo:exec-maven-plugin:3.5.0:java"
            if ($LASTEXITCODE -ne 0) {
                throw "Real ONNX run failed for callers=$count. Unvalidated run files remain local at $runDirectory."
            }
            if (-not (Test-Path -LiteralPath $resultPath -PathType Leaf)) {
                throw "Real ONNX benchmark did not produce JSON: $resultPath"
            }
            $result = Get-Content -LiteralPath $resultPath -Raw |
                ConvertFrom-Json
            if ($result.security_passed -ne $true -or
                    $result.raw_payload_forwarded -ne $false -or
                    $result.benchmark_kind -ne
                    "real-onnx-application-logger-parallel-load" -or
                    [int]$result.events -ne $Events -or
                    [int]$result.parallel_callers -ne $count -or
                    ([int]$result.successful_masked_events +
                        [int]$result.fail_closed_events) -ne $Events -or
                    [int]$result.total_protected_records -ne
                    ($Events + [int]$result.warmup_events) -or
                    $result.protected_sequence_audit.unique_ingress_ids -ne $true) {
                throw "Parallel ONNX security/completeness verification failed. No result commit will occur."
            }
            if (-not (Test-Path -LiteralPath $protectedPath -PathType Leaf)) {
                throw "Protected benchmark log is missing."
            }
            $rawFixtures = @(
                "123-45-6789", "jane.doe@example.com",
                "4111111111111111", "TEST_API_KEY_1234567890",
                "eyJTEST0.TESTPAYLOAD.TESTSIGNATURE", "9876543210",
                "7045551212"
            )
            $protectedText = [System.IO.File]::ReadAllText($protectedPath)
            foreach ($raw in $rawFixtures) {
                if ($protectedText.Contains($raw)) {
                    throw "Raw synthetic fixture reached output. No result commit will occur."
                }
            }

            $reconstructionPath = Join-Path $runDirectory "reconstructed-application.log"
            & (Join-Path $PSScriptRoot "reconstruct-protected-application-log.ps1") -InputPath $protectedPath -OutputPath $reconstructionPath
            if (-not (Test-Path -LiteralPath $reconstructionPath -PathType Leaf)) {
                throw "Reconstruction failed. No result commit will occur."
            }
            $expectedLines = @(
                $protectedText -split '\r?\n' |
                    Where-Object { $_ -like 'eventId=*' }
            )
            if ($expectedLines.Count -ne $result.total_protected_records) {
                throw "Protected event count mismatches benchmark JSON. No result commit."
            }

            $protectedUnique = Join-Path $runDirectory "protected-application-$runId.log"
            $reconstructedUnique = Join-Path $runDirectory "reconstructed-application-$runId.log"
            Move-Item -LiteralPath $protectedPath -Destination $protectedUnique
            Move-Item -LiteralPath $reconstructionPath -Destination $reconstructedUnique

            $files = @($resultPath, $protectedUnique, $reconstructedUnique)
            $manifestFiles = @(
                foreach ($file in $files) {
                    [ordered]@{
                        filename = [System.IO.Path]::GetFileName($file)
                        bytes = (Get-Item -LiteralPath $file).Length
                        sha256 = (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash
                    }
                }
            )
            $manifest = [ordered]@{
                schema_version = 1
                run_id = $runId
                source_commit_sha = $commitSha
                created_at_utc = [DateTime]::UtcNow.ToString("o")
                evidence_type = "real_onnx_synthetic_concurrency_load"
                cpu_threads = $CpuThreads
                max_windows_per_batch = $WindowBatchLimit
                deadline_millis = $MaskingDeadlineMillis
                masking_queue_capacity = $MaskingQueueCapacity
                caller_threads = $count
                caller_mode = $CallerMode
                events = $Events
                security_passed = $result.security_passed
                functional_passed = $result.functional_passed
                synthetic_fixture_safety_check = "passed"
                raw_user_data_present = $false
                files = $manifestFiles
            }
            $manifestPath = Join-Path $runDirectory "manifest-$runId.json"
            $manifest | ConvertTo-Json -Depth 8 |
                Set-Content -LiteralPath $manifestPath -Encoding UTF8

            Write-Host "Unique real ONNX run archived: $relative"
            Write-Host "Security pass: $($result.security_passed)  Functional pass: $($result.functional_passed)"
            Write-Host "Successful / fail-closed: $($result.successful_masked_events) / $($result.fail_closed_events)"
            if ($CommitResults) {
                & git -C $root add -- $relative
                if ($LASTEXITCODE -ne 0) {
                    throw "git add failed; run is archived locally."
                }
                & git -C $root commit --only -m "Archive real ONNX parallel load $runId" -- $relative
                if ($LASTEXITCODE -ne 0) {
                    throw "git commit failed; run is archived locally."
                }
                Write-Host "Committed real ONNX concurrent evidence locally."
                if ($PushResults) {
                    & git -C $root push origin "HEAD:refs/heads/chatgpt/hybrid-detection-pipeline"
                    if ($LASTEXITCODE -ne 0) {
                        throw "git push failed; commit preserved locally. Retry git push without rerunning the benchmark."
                    }
                    Write-Host "Pushed real ONNX concurrent evidence to GitHub."
                }
            }
        }
    } finally {
        if ($null -eq $previousJavaOptions) {
            Remove-Item Env:\JAVA_TOOL_OPTIONS -ErrorAction SilentlyContinue
        } else {
            $env:JAVA_TOOL_OPTIONS = $previousJavaOptions
        }
    }
} finally {
    Pop-Location
}
