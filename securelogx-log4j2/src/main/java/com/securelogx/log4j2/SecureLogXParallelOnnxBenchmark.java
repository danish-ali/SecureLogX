package com.securelogx.log4j2;

import com.securelogx.api.MaskingRuntimeStats;
import com.securelogx.config.SecureLogXConfig;
import com.securelogx.validation.RuntimeMemorySnapshot;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.ThreadContext;
import org.apache.logging.log4j.core.LoggerContext;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Real ONNX application-Logger concurrency characterization, NOT a JMH
 * microbenchmark nor proof of a production SLO.
 *
 * The native ONNX worker stays bounded and platform-thread backed.
 * Only the application caller threads may be platform or virtual.
 *
 * A protected fail-closed log record is counted as DELIVERED and SECURE,
 * but NOT successfully masked. Functionality and confidentiality are
 * evaluated independently.
 */
public final class SecureLogXParallelOnnxBenchmark {

    private SecureLogXParallelOnnxBenchmark() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            throw new IllegalArgumentException(
                    "Usage: <result.json> <events> <concurrentCallers> "
                            + "<platform|virtual>"
            );
        }
        Path reportPath = Path.of(args[0]);
        int eventCount = Integer.parseInt(args[1]);
        int callers = Integer.parseInt(args[2]);
        String callerMode = args[3];
        if (eventCount < 12 || callers < 1
                || callers > 200 || callers > eventCount) {
            throw new IllegalArgumentException(
                    "Require events>=12 and 1<=callers<=min(200,events)"
            );
        }
        if (!callerMode.equals("platform") && !callerMode.equals("virtual")) {
            throw new IllegalArgumentException(
                    "Caller mode must be platform or virtual"
            );
        }

        Path directory = reportPath.toAbsolutePath().getParent();
        if (directory == null) {
            throw new IllegalArgumentException("Result path needs a parent");
        }
        Files.createDirectories(directory);
        Path protectedFile = directory.resolve("protected-application.log");
        if (Files.exists(reportPath) || Files.exists(protectedFile)) {
            throw new IllegalStateException(
                    "Refusing to overwrite an existing concurrency run"
            );
        }

        SecureMaskerRegistry.closeAll();
        RuntimeMemorySnapshot processStart =
                SecureLogXApplicationLogBenchmark.settledSnapshot(
                        "parallel-process-start"
                );
        LoggerContext context = new LoggerContext(
                "securelogx-real-onnx-parallel-benchmark"
        );
        SecureLogXApplicationLogBenchmark.BenchmarkConfiguration config =
                new SecureLogXApplicationLogBenchmark.BenchmarkConfiguration(
                        context
                );
        SecureLogXApplicationLogBenchmark.configurePipeline(
                config, protectedFile
        );
        context.start(config);
        SecureLogXLog4j2ConfigurationValidator.validateOrThrow(
                config, "SecureLogXRewrite", false
        );
        Logger logger = context.getLogger(
                "com.securelogx.benchmark.ParallelApplication"
        );

        try {
            RuntimeMemorySnapshot afterInit =
                    SecureLogXApplicationLogBenchmark.settledSnapshot(
                            "parallel-after-init"
                    );
            for (int i = 0;
                    i < SecureLogXApplicationLogBenchmark.WARMUP_EVENTS;
                    i++) {
                SecureLogXApplicationLogBenchmark.emit(
                        logger, "warmup-" + i, i
                );
            }
            RuntimeMemorySnapshot afterWarmup =
                    SecureLogXApplicationLogBenchmark.settledSnapshot(
                            "parallel-after-warmup"
                    );
            SecureLogXInferenceDiagnostics.Snapshot before =
                    SecureLogXInferenceDiagnostics.snapshot(
                            context, "dev"
                    ).orElseThrow(() -> new IllegalStateException(
                            "Active real ONNX inference statistics missing"
                    ));
            MaskingRuntimeStats executorBefore =
                    SecureLogXInferenceDiagnostics.runtimeStats(
                            context, "dev"
                    ).orElseThrow(() -> new IllegalStateException(
                            "Active bounded executor statistics missing"
                    ));

            long[] callerDurations = new long[eventCount];
            long wallStart = System.nanoTime();
            // Executor is intentionally caller-only; no change to the native
            // worker concurrency, queuing or cancellation semantics.
            ExecutorService callerExecutor =
                    callerMode.equals("virtual")
                            ? Executors.newVirtualThreadPerTaskExecutor()
                            : Executors.newFixedThreadPool(callers);
            try {
                // Submit a synchronized wave of <=callers. Do not create
                // 10000 pending futures merely to characterize 100 callers.
                for (int offset = 0; offset < eventCount; offset += callers) {
                    int end = Math.min(eventCount, offset + callers);
                    CountDownLatch startGate = new CountDownLatch(1);
                    List<Future<?>> wave = new ArrayList<>(end - offset);
                    for (int i = offset; i < end; i++) {
                        final int ordinal = i;
                        wave.add(callerExecutor.submit(() -> {
                            try {
                                startGate.await();
                            } catch (InterruptedException ex) {
                                Thread.currentThread().interrupt();
                                throw new IllegalStateException(
                                        "Caller interrupted before ingress",
                                        ex
                                );
                            }
                            long started = System.nanoTime();
                            SecureLogXApplicationLogBenchmark.emit(
                                    logger, "bench-" + ordinal, ordinal
                            );
                            callerDurations[ordinal] =
                                    System.nanoTime() - started;
                        }));
                    }
                    startGate.countDown();
                    for (Future<?> future : wave) {
                        // A hung caller is not a successful benchmark. It
                        // must never be silently recorded as a dropped log.
                        future.get(90, TimeUnit.SECONDS);
                    }
                }
            } finally {
                callerExecutor.shutdown();
                if (!callerExecutor.awaitTermination(
                        20, TimeUnit.SECONDS
                )) {
                    callerExecutor.shutdownNow();
                    throw new IllegalStateException(
                            "Caller threads failed to terminate"
                    );
                }
            }
            long wallNanos = System.nanoTime() - wallStart;

            RuntimeMemorySnapshot afterWorkload =
                    SecureLogXApplicationLogBenchmark.settledSnapshot(
                            "parallel-after-workload"
                    );
            SecureLogXInferenceDiagnostics.Snapshot after =
                    SecureLogXInferenceDiagnostics.snapshot(
                            context, "dev"
                    ).orElseThrow();
            MaskingRuntimeStats executorAfter =
                    SecureLogXInferenceDiagnostics.runtimeStats(
                            context, "dev"
                    ).orElseThrow();

            context.stop();
            RuntimeMemorySnapshot afterStop =
                    SecureLogXApplicationLogBenchmark.settledSnapshot(
                            "parallel-after-stop"
                    );
            if (SecureMaskerRegistry.ownedContextCount() != 0
                    || SecureMaskerRegistry.ownedMaskerCount() != 0) {
                throw new IllegalStateException(
                        "Native masking registry ownership remains after stop"
                );
            }

            String protectedOutput = Files.readString(
                    protectedFile, StandardCharsets.UTF_8
            );
            Validation validation = validateProtectedOutput(
                    protectedOutput, eventCount
            );

            List<Long> durations = new ArrayList<>(eventCount);
            List<Long> longWindowDurations = new ArrayList<>();
            for (int i = 0; i < eventCount; i++) {
                if (callerDurations[i] <= 0) {
                    throw new IllegalStateException(
                            "A caller returned without a latency value"
                    );
                }
                durations.add(callerDurations[i]);
                if (i % 10 == 5) {
                    longWindowDurations.add(callerDurations[i]);
                }
            }
            SecureLogXConfig effective = new SecureLogXConfig("dev");
            long timeoutNanos = TimeUnit.MILLISECONDS.toNanos(
                    effective.getMaskingDeadlineMillis()
            );
            long callsOverDeadline = durations.stream()
                    .filter(value -> value > timeoutNanos).count();
            long callsOver2s = durations.stream()
                    .filter(value -> value > TimeUnit.MILLISECONDS.toNanos(2000))
                    .count();
            boolean functionalPass = validation.failClosedCount() == 0
                    && callsOverDeadline == 0
                    && validation.benignVersionPreserved();

            JSONObject result = new JSONObject()
                    .put("status", functionalPass
                            ? "PARALLEL ONNX FUNCTIONAL PASS"
                            : "PARALLEL ONNX SECURITY PASS, FUNCTIONAL DEGRADATION")
                    .put("passed", functionalPass)
                    .put("security_passed", true)
                    .put("functional_passed", functionalPass)
                    .put("benchmark_kind",
                            "real-onnx-application-logger-parallel-load")
                    .put("event_count", eventCount)
                    .put("events", eventCount)
                    .put("warmup_events",
                            SecureLogXApplicationLogBenchmark.WARMUP_EVENTS)
                    .put("parallel_callers", callers)
                    .put("caller_thread_mode", callerMode)
                    .put("masking_deadline_millis",
                            effective.getMaskingDeadlineMillis())
                    .put("max_configured_cpu_threads",
                            effective.getMaxCpuThreads())
                    .put("max_inference_windows_per_batch",
                            effective.getMaxInferenceWindowsPerBatch())
                    .put("masking_queue_capacity",
                            effective.getMaskingQueueCapacity())
                    .put("total_protected_records",
                            validation.sequenceAudit().records())
                    .put("successful_masked_events",
                            validation.successCount())
                    .put("fail_closed_events",
                            validation.failClosedCount())
                    .put("fail_closed_reason_counts",
                            new JSONObject(validation.failClosedReasonCounts()))
                    .put("duplicate_successful_event_ids", 0)
                    .put("undelivered_or_duplicate_ingress_ids", 0)
                    .put("original_event_ids_omitted_on_fail_closed",
                            validation.failClosedCount())
                    .put("raw_payload_forwarded", false)
                    .put("benign_control_success_count",
                            validation.benignControlSuccessCount())
                    .put("benign_version_preserved",
                            validation.benignVersionPreserved())
                    .put("caller_events_over_2000ms", callsOver2s)
                    .put("caller_events_over_masking_deadline",
                            callsOverDeadline)
                    .put("caller_latency_millis",
                            SecureLogXApplicationLogBenchmark.latencyJson(
                                    durations))
                    .put("long_windowed_caller_latency_millis",
                            SecureLogXApplicationLogBenchmark.latencyJson(
                                    longWindowDurations))
                    .put("elapsed_millis",
                            TimeUnit.NANOSECONDS.toMillis(wallNanos))
                    .put("events_per_second",
                            eventCount / (wallNanos / 1_000_000_000.0))
                    .put("inference_metrics_measured_only",
                            SecureLogXApplicationLogBenchmark.inferenceMetricsJson(
                                    before, after))
                    .put("bounded_executor_measured_only",
                            executorStatsJson(executorBefore, executorAfter))
                    .put("protected_sequence_audit",
                            new JSONObject()
                                    .put("records_including_warmup",
                                            validation.sequenceAudit().records())
                                    .put("instance_count",
                                            validation.sequenceAudit().instances())
                                    .put("physical_order_inversions",
                                            validation.sequenceAudit()
                                                    .physicalOrderInversions())
                                    .put("unique_ingress_ids", true)
                                    .put("reconstruction_scope",
                                            "per-instance-numeric-sequence"))
                    .put("process_start", processStart.toJson())
                    .put("after_init", afterInit.toJson())
                    .put("after_warmup", afterWarmup.toJson())
                    .put("after_workload", afterWorkload.toJson())
                    .put("after_context_stop", afterStop.toJson())
                    .put("workload_memory_delta",
                            SecureLogXApplicationLogBenchmark.memoryDelta(
                                    afterWarmup, afterWorkload))
                    .put("post_stop_memory_delta",
                            SecureLogXApplicationLogBenchmark.memoryDelta(
                                    processStart, afterStop))
                    .put("final_owned_contexts",
                            SecureMaskerRegistry.ownedContextCount())
                    .put("final_owned_maskers",
                            SecureMaskerRegistry.ownedMaskerCount())
                    .put("sealed_challenge_inference", false);

            Files.writeString(reportPath,
                    result.toString(2) + System.lineSeparator(),
                    StandardCharsets.UTF_8);
            System.out.println("REAL ONNX PARALLEL APPLICATION BENCHMARK");
            System.out.println("Callers / mode: "
                    + callers + " / " + callerMode);
            System.out.println("Protected records: "
                    + validation.sequenceAudit().records());
            System.out.println("Successful masked / fail-closed: "
                    + validation.successCount() + " / "
                    + validation.failClosedCount());
            System.out.println("Caller p95/p99 ms: "
                    + result.getJSONObject("caller_latency_millis")
                            .get("p95") + " / "
                    + result.getJSONObject("caller_latency_millis")
                            .get("p99"));
            System.out.println("Physical ordering inversions: "
                    + validation.sequenceAudit().physicalOrderInversions());
            System.out.println("Security passed: true");
            System.out.println("Functional passed: " + functionalPass);
            System.out.println("Result: " + reportPath);
        } finally {
            ThreadContext.clearAll();
            if (!context.isStopped()) {
                context.stop();
            }
        }
    }

    static JSONObject executorStatsJson(
            MaskingRuntimeStats before, MaskingRuntimeStats after
    ) {
        return new JSONObject()
                .put("accepted_requests",
                        after.acceptedRequests() - before.acceptedRequests())
                .put("completed_requests",
                        after.completedRequests() - before.completedRequests())
                .put("overload_rejected_requests",
                        after.overloadRejectedRequests()
                                - before.overloadRejectedRequests())
                .put("deadline_exceeded_requests",
                        after.deadlineExceededRequests()
                                - before.deadlineExceededRequests())
                .put("execution_failure_requests",
                        after.executionFailureRequests()
                                - before.executionFailureRequests())
                .put("native_termination_signals",
                        after.nativeTerminationSignals()
                                - before.nativeTerminationSignals())
                .put("peak_active_requests_including_warmup",
                        after.peakActiveRequests())
                .put("peak_queued_requests_including_warmup",
                        after.peakQueuedRequests())
                .put("queue_capacity", after.queueCapacity())
                .put("active_requests_after", after.activeRequests())
                .put("queued_requests_after", after.queuedRequests());
    }

    static Validation validateProtectedOutput(String output, int events) {
        for (String fixture
                : SecureLogXApplicationLogBenchmark.RAW_SENSITIVE_VALUES) {
            if (output.contains(fixture)) {
                throw new IllegalStateException(
                        "Raw synthetic sensitive fixture reached protected output"
                );
            }
        }
        SecureLogXSequenceAudit.Audit audit =
                SecureLogXSequenceAudit.inspect(output);

        List<String> lines = output.lines()
                .filter(line -> line.startsWith("eventId="))
                .toList();
        int expected = events
                + SecureLogXApplicationLogBenchmark.WARMUP_EVENTS;
        if (lines.size() != expected || audit.records() != expected) {
            throw new IllegalStateException(
                    "Missing/extra protected records: expected=" + expected
                            + ", actual=" + lines.size()
            );
        }

        Set<Integer> successfulIds = new HashSet<>();
        Set<Integer> warmupIds = new HashSet<>();
        Map<String, Integer> reasons = new TreeMap<>();
        int failed = 0;
        int benignControlSuccess = 0;

        for (String line : lines) {
            int separator = line.indexOf('|');
            if (separator < 0) {
                throw new IllegalStateException(
                        "Missing protected metadata delimiter"
                );
            }
            String id = line.substring("eventId=".length(), separator);
            boolean failClosed = line.contains("|failClosed=true|");
            boolean passed = line.contains("|failClosed=false|");
            if (!failClosed && !passed) {
                throw new IllegalStateException(
                        "Missing valid failClosed marker"
                );
            }

            if (id.startsWith("warmup-")) {
                if (failClosed) {
                    throw new IllegalStateException(
                            "Warmup unexpectedly failed closed"
                    );
                }
                int warmupOrdinal = parseOrdinal(id.substring(7),
                        SecureLogXApplicationLogBenchmark.WARMUP_EVENTS);
                if (!warmupIds.add(warmupOrdinal)) {
                    throw new IllegalStateException("Duplicate warmup ID");
                }
            } else if (failClosed) {
                // Fail-closed policy intentionally discards original MDC,
                // including the synthetic eventId; identity is ingressId.
                if (!id.isEmpty()) {
                    throw new IllegalStateException(
                            "Fail-closed event retained upstream eventId"
                    );
                }
                int reasonStart = line.indexOf("|reason=");
                int reasonEnd = line.indexOf('|', reasonStart + 8);
                if (reasonStart < 0 || reasonEnd < 0) {
                    throw new IllegalStateException(
                            "Fail-closed event missing reason"
                    );
                }
                reasons.merge(line.substring(reasonStart + 8, reasonEnd),
                        1, Integer::sum);
                failed++;
            } else if (id.startsWith("bench-")) {
                int ordinal = parseOrdinal(id.substring(6), events);
                if (!successfulIds.add(ordinal)) {
                    throw new IllegalStateException(
                            "Duplicate successful benchmark eventId"
                    );
                }
                if (ordinal % 10 == 6
                        && line.contains(
                                "version="
                                        + SecureLogXApplicationLogBenchmark.VERSION_CONTROL
                        )) {
                    benignControlSuccess++;
                }
            } else {
                throw new IllegalStateException(
                        "Unrecognized protected event identifier"
                );
            }
        }
        if (warmupIds.size()
                != SecureLogXApplicationLogBenchmark.WARMUP_EVENTS) {
            throw new IllegalStateException("Missing warmup event");
        }
        if (successfulIds.size() + failed != events) {
            throw new IllegalStateException(
                    "Missing benchmark events after distinguishing failures"
            );
        }
        if (audit.instances() != 1) {
            throw new IllegalStateException(
                    "One benchmark LoggerContext must use one ingress "
                            + "instanceId, found " + audit.instances()
            );
        }
        int expectedBenign = 0;
        for (int ordinal = 0; ordinal < events; ordinal++) {
            if (ordinal % 10 == 6) {
                expectedBenign++;
            }
        }
        return new Validation(
                successfulIds.size(), failed, reasons, audit,
                benignControlSuccess,
                benignControlSuccess == expectedBenign
        );
    }

    private static int parseOrdinal(String numeric, int count) {
        final int ordinal;
        try {
            ordinal = Integer.parseInt(numeric);
        } catch (NumberFormatException ex) {
            throw new IllegalStateException(
                    "Invalid synthetic event ordinal", ex
            );
        }
        if (ordinal < 0 || ordinal >= count) {
            throw new IllegalStateException(
                    "Synthetic event ordinal is out of range"
            );
        }
        return ordinal;
    }

    record Validation(
            int successCount,
            int failClosedCount,
            Map<String, Integer> failClosedReasonCounts,
            SecureLogXSequenceAudit.Audit sequenceAudit,
            int benignControlSuccessCount,
            boolean benignVersionPreserved
    ) {
    }
}
