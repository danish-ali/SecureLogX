package com.securelogx.log4j2;

import com.securelogx.api.MaskingQueueWaitStats;
import com.securelogx.api.MaskingRuntimeStats;
import com.securelogx.config.SecureLogXConfig;
import com.securelogx.validation.RuntimeMemorySnapshot;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.ThreadContext;
import org.apache.logging.log4j.core.LoggerContext;
import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/**
 * Open-loop, fixed-rate synthetic application logging through real ONNX.
 * Only compact aggregate statistics are intended for source control;
 * locally retained protected fixtures are not automatically published.
 *
 * Caller concurrency remains bounded by a separate semaphore, without
 * changing the single native masking worker or the security policy.
 */
public final class SecureLogXSteadyArrivalOnnxBenchmark {

    private SecureLogXSteadyArrivalOnnxBenchmark() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 6) {
            throw new IllegalArgumentException(
                    "Usage: <result.json> <rate-per-second> "
                    + "<duration-seconds> <platform|virtual> "
                    + "<max-outstanding> <memory-sample-millis>"
            );
        }
        Path resultPath = Path.of(args[0]).toAbsolutePath();
        int rate = Integer.parseInt(args[1]);
        int durationSeconds = Integer.parseInt(args[2]);
        String mode = args[3];
        int maxOutstanding = Integer.parseInt(args[4]);
        int sampleMillis = Integer.parseInt(args[5]);
        if (rate < 1 || rate > 30 || durationSeconds < 20
                || durationSeconds > 600 || maxOutstanding < 1
                || maxOutstanding > 128 || sampleMillis < 1000
                || sampleMillis > 30000
                || (!mode.equals("platform") && !mode.equals("virtual"))) {
            throw new IllegalArgumentException(
                    "Invalid rate, duration, caller mode, outstanding "
                            + "limit or memory sampling interval"
            );
        }
        int plannedEvents = Math.multiplyExact(rate, durationSeconds);
        if (plannedEvents < 12 || plannedEvents > 18000) {
            throw new IllegalArgumentException(
                    "Require 12..18000 planned synthetic events"
            );
        }
        Files.createDirectories(resultPath.getParent());
        Path protectedFile = resultPath.getParent().resolve(
                "protected-application.log"
        );
        if (Files.exists(resultPath) || Files.exists(protectedFile)) {
            throw new IllegalStateException(
                    "Refusing to overwrite existing study files"
            );
        }

        SecureMaskerRegistry.closeAll();
        RuntimeMemorySnapshot processStart =
                SecureLogXApplicationLogBenchmark.settledSnapshot(
                        "steady-process-start"
                );
        LoggerContext context = new LoggerContext(
                "securelogx-real-onnx-steady-arrival"
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
                "com.securelogx.benchmark.SteadyArrivalApplication"
        );

        try {
            for (int i = 0;
                    i < SecureLogXApplicationLogBenchmark.WARMUP_EVENTS;
                    i++) {
                SecureLogXApplicationLogBenchmark.emit(
                        logger, "warmup-" + i, i
                );
            }
            RuntimeMemorySnapshot warmed =
                    SecureLogXApplicationLogBenchmark.settledSnapshot(
                            "steady-after-warmup"
                    );
            SecureLogXInferenceDiagnostics.Snapshot before =
                    SecureLogXInferenceDiagnostics.snapshot(
                            context, "dev"
                    ).orElseThrow();
            MaskingRuntimeStats runtimeBefore =
                    SecureLogXInferenceDiagnostics.runtimeStats(
                            context, "dev"
                    ).orElseThrow();
            MaskingQueueWaitStats waitsBefore =
                    SecureLogXInferenceDiagnostics.queueWaitStats(
                            context, "dev"
                    ).orElseThrow();
            com.securelogx.api.PreQueueRoutingStats preQueueBefore =
                    SecureLogXInferenceDiagnostics.preQueueRoutingStats(
                            context, "dev"
                    ).orElseThrow();
            if (!SecureLogXInferenceDiagnostics.beginQueueWaitWindow(
                    context, "dev")) {
                throw new IllegalStateException(
                        "Cannot mark real ONNX post-warmup queue window"
                );
            }
            long gcMillisBefore = totalGcMillis();
            long gcCountBefore = totalGcCount();

            Semaphore outstanding = new Semaphore(maxOutstanding);
            AtomicInteger inflight = new AtomicInteger();
            AtomicInteger peakInflight = new AtomicInteger();
            AtomicInteger completedCallers = new AtomicInteger();
            AtomicInteger generatorRejected = new AtomicInteger();
            AtomicInteger memorySampleErrors = new AtomicInteger();
            long[] durations = new long[plannedEvents];
            long[] startLateness = new long[plannedEvents];
            long[] dispatchLateness = new long[plannedEvents];
            long firstDispatchAt = 0L;
            long lastDispatchAt = 0L;
            List<Future<?>> futures = new ArrayList<>(plannedEvents);
            List<JSONObject> memorySamples =
                    Collections.synchronizedList(new ArrayList<>());
            ScheduledExecutorService sampler =
                    Executors.newSingleThreadScheduledExecutor(r -> {
                        Thread t = new Thread(
                                r, "SecureLogX-Steady-MemorySampler"
                        );
                        t.setDaemon(true);
                        return t;
                    });
            ExecutorService callers = mode.equals("virtual")
                    ? Executors.newVirtualThreadPerTaskExecutor()
                    : Executors.newFixedThreadPool(maxOutstanding);

            long wallStart = System.nanoTime();
            long wallNanos;
            try {
                // Sampling may invoke an OS process probe on Windows.
                // Every 5s default avoids distorting native service time.
                sampler.scheduleAtFixedRate(() -> {
                    try {
                        RuntimeMemorySnapshot sample =
                                RuntimeMemorySnapshot.capture(
                                        "steady-observed-memory"
                                );
                        JSONObject point = new JSONObject()
                                .put("elapsed_seconds",
                                        (System.nanoTime() - wallStart)
                                                / 1_000_000_000.0)
                                .put("outstanding_callers",
                                        inflight.get())
                                .put("completed_callers",
                                        completedCallers.get())
                                .put("heap_used_bytes",
                                        sample.heapUsedBytes())
                                .put("process_working_set_bytes",
                                        sample.processWorkingSetBytes())
                                .put("process_private_bytes",
                                        sample.processPrivateBytes());
                        memorySamples.add(point);
                    } catch (Exception e) {
                        memorySampleErrors.incrementAndGet();
                    }
                }, 0, sampleMillis, TimeUnit.MILLISECONDS);

                for (int i = 0; i < plannedEvents; i++) {
                    final int ordinal = i;
                    final long scheduledAt = scheduledAtNanos(
                            wallStart, ordinal, rate
                    );
                    waitUntilNanos(scheduledAt);
                    if (!outstanding.tryAcquire()) {
                        // Explicit generator-level throttling, not a valid
                        // protected logging event. This prevents unbounded
                        // pending threads and fails functional acceptance.
                        generatorRejected.incrementAndGet();
                        continue;
                    }
                    long dispatchedAt = System.nanoTime();
                    dispatchLateness[ordinal] = Math.max(
                            0L, dispatchedAt - scheduledAt
                    );
                    if (firstDispatchAt == 0L) {
                        firstDispatchAt = dispatchedAt;
                    }
                    lastDispatchAt = dispatchedAt;
                    final int outstandingNow = inflight.incrementAndGet();
                    peakInflight.accumulateAndGet(
                            outstandingNow, Math::max
                    );
                    try {
                        futures.add(callers.submit(() -> {
                            try {
                                long started = System.nanoTime();
                                startLateness[ordinal] = Math.max(
                                        0, started - scheduledAt
                                );
                                SecureLogXApplicationLogBenchmark.emit(
                                        logger, "bench-" + ordinal, ordinal
                                );
                                durations[ordinal] =
                                        System.nanoTime() - started;
                                completedCallers.incrementAndGet();
                            } finally {
                                inflight.decrementAndGet();
                                outstanding.release();
                            }
                        }));
                    } catch (RuntimeException e) {
                        inflight.decrementAndGet();
                        outstanding.release();
                        throw e;
                    }
                }
                for (Future<?> future : futures) {
                    future.get(90, TimeUnit.SECONDS);
                }
                wallNanos = System.nanoTime() - wallStart;
            } finally {
                callers.shutdown();
                if (!callers.awaitTermination(30, TimeUnit.SECONDS)) {
                    callers.shutdownNow();
                    throw new IllegalStateException(
                            "Outstanding benchmark caller did not terminate"
                    );
                }
                sampler.shutdown();
                if (!sampler.awaitTermination(30, TimeUnit.SECONDS)) {
                    sampler.shutdownNow();
                    throw new IllegalStateException(
                            "Memory sampling thread did not terminate"
                    );
                }
            }

            RuntimeMemorySnapshot afterWork =
                    SecureLogXApplicationLogBenchmark.settledSnapshot(
                            "steady-after-work"
                    );
            MaskingRuntimeStats runtimeAfter =
                    SecureLogXInferenceDiagnostics.runtimeStats(
                            context, "dev"
                    ).orElseThrow();
            MaskingQueueWaitStats waitsAfter =
                    SecureLogXInferenceDiagnostics.queueWaitStats(
                            context, "dev"
                    ).orElseThrow();
            com.securelogx.api.PreQueueRoutingStats preQueueAfter =
                    SecureLogXInferenceDiagnostics.preQueueRoutingStats(
                            context, "dev"
                    ).orElseThrow();
            double measuredMaxQueueWaitMillis =
                    SecureLogXInferenceDiagnostics.maxMeasuredQueueWaitMillis(
                            context, "dev"
                    ).orElseThrow();
            SecureLogXInferenceDiagnostics.Snapshot after =
                    SecureLogXInferenceDiagnostics.snapshot(
                            context, "dev"
                    ).orElseThrow();
            long gcMillis = totalGcMillis() - gcMillisBefore;
            long gcCount = totalGcCount() - gcCountBefore;

            context.stop();
            RuntimeMemorySnapshot afterStop =
                    SecureLogXApplicationLogBenchmark.settledSnapshot(
                            "steady-after-stop"
                    );
            if (SecureMaskerRegistry.ownedContextCount() != 0
                    || SecureMaskerRegistry.ownedMaskerCount() != 0) {
                throw new IllegalStateException(
                        "Masker/context leak after real ONNX steady load"
                );
            }
            int droppedAtGenerator = generatorRejected.get();
            // An overload in the test generator is distinct from masking
            // overload. It is not safe to pretend the planned arrivals
            // were delivered; the JSON is still written as failed.
            int deliveredCount = plannedEvents - droppedAtGenerator;
            String protectedOutput = Files.readString(
                    protectedFile, StandardCharsets.UTF_8
            );
            // Existing validator checks raw known fixtures, all protected
            // ingress identity uniqueness and numeric sequence ordering.
            // It also requires all expected 200+ synthetic event ordinals.
            // With any generator drops this exact check cannot pass:
            // record a diagnostic, never publish as a verified success.
            if (droppedAtGenerator != 0) {
                throw new IllegalStateException(
                        "Load generator hit MaxOutstanding: "
                                + droppedAtGenerator
                                + " planned arrivals were not submitted. "
                                + "Run invalid; local synthetic files retained."
                );
            }
            SecureLogXParallelOnnxBenchmark.Validation validation =
                    SecureLogXParallelOnnxBenchmark
                            .validateProtectedOutput(
                                    protectedOutput, plannedEvents
                            );

            List<Long> completedDurations =
                    new ArrayList<>(plannedEvents);
            List<Long> schedulerDelays =
                    new ArrayList<>(plannedEvents);
            List<Long> dispatchDelays =
                    new ArrayList<>(plannedEvents);
            for (int i = 0; i < plannedEvents; i++) {
                if (durations[i] <= 0) {
                    throw new IllegalStateException(
                            "Missing completed caller: " + i
                    );
                }
                completedDurations.add(durations[i]);
                schedulerDelays.add(startLateness[i]);
                dispatchDelays.add(dispatchLateness[i]);
            }
            SecureLogXConfig effective = new SecureLogXConfig("dev");
            long deadlineNanos = TimeUnit.MILLISECONDS.toNanos(
                    effective.getMaskingDeadlineMillis()
            );
            long lateCallers = completedDurations.stream()
                    .filter(n -> n > deadlineNanos).count();
            JSONObject dispatchLag =
                    SecureLogXApplicationLogBenchmark.latencyJson(
                            dispatchDelays
                    );
            double dispatchRate = (plannedEvents - 1.0)
                    / ((lastDispatchAt - firstDispatchAt)
                            / 1_000_000_000.0);
            // An experiment whose generator delivers late bursts is not a
            // valid sustained-rate pass, even if masking returns normally.
            boolean rateFidelity = meetsRateFidelity(
                    rate, dispatchRate, dispatchLag.getDouble("p95")
            );
            boolean functional = validation.failClosedCount() == 0
                    && lateCallers == 0
                    && completedCallers.get() == plannedEvents
                    && validation.benignVersionPreserved()
                    && rateFidelity;
            long peakRssBytes = 0;
            long peakPrivateBytes = 0;
            for (JSONObject point : memorySamples) {
                peakRssBytes = Math.max(
                        peakRssBytes,
                        point.optLong("process_working_set_bytes", 0)
                );
                peakPrivateBytes = Math.max(
                        peakPrivateBytes,
                        point.optLong("process_private_bytes", 0)
                );
            }

            JSONObject result = new JSONObject()
                    .put("schema_version", 1)
                    .put("benchmark_kind",
                            "real-onnx-steady-arrival-log4j2")
                    .put("security_passed", true)
                    .put("functional_passed", functional)
                    .put("rate_fidelity_passed", rateFidelity)
                    .put("passed", functional)
                    .put("raw_payload_forwarded", false)
                    .put("offered_rate_per_second", rate)
                    .put("duration_seconds", durationSeconds)
                    .put("planned_events", plannedEvents)
                    .put("offered_attempts", plannedEvents)
                    .put("generator_rejections", droppedAtGenerator)
                    .put("admitted_by_generator", deliveredCount)
                    .put("completed_callers", completedCallers.get())
                    .put("max_outstanding_callers", maxOutstanding)
                    .put("observed_peak_outstanding",
                            peakInflight.get())
                    .put("caller_mode", mode)
                    .put("warmup_events",
                            SecureLogXApplicationLogBenchmark.WARMUP_EVENTS)
                    .put("total_protected_records",
                            validation.sequenceAudit().records())
                    .put("successful_masked_events",
                            validation.successCount())
                    .put("fail_closed_events",
                            validation.failClosedCount())
                    .put("benign_version_preserved",
                            validation.benignVersionPreserved())
                    .put("benign_control_success_count",
                            validation.benignControlSuccessCount())
                    .put("fail_closed_reason_counts",
                            new JSONObject(
                                    validation.failClosedReasonCounts()
                            ))
                    .put("caller_events_over_masking_deadline",
                            lateCallers)
                    .put("masking_deadline_millis",
                            effective.getMaskingDeadlineMillis())
                    .put("configured_model_sha256",
                            effective.getModelSha256())
                    .put("configured_tokenizer_sha256",
                            effective.getTokenizerSha256())
                    .put("max_configured_cpu_threads",
                            effective.getMaxCpuThreads())
                    .put("max_inference_windows_per_batch",
                            effective.getMaxInferenceWindowsPerBatch())
                    .put("masking_queue_capacity",
                            effective.getMaskingQueueCapacity())
                    .put("elapsed_millis",
                            TimeUnit.NANOSECONDS.toMillis(wallNanos))
                    .put("observed_dispatched_rate_per_second",
                            dispatchRate)
                    .put("protected_completions_per_second",
                            plannedEvents
                                    / (wallNanos / 1_000_000_000.0))
                    .put("normal_masked_events_per_second",
                            validation.successCount()
                                    / (wallNanos / 1_000_000_000.0))
                    .put("caller_latency_millis",
                            SecureLogXApplicationLogBenchmark.latencyJson(
                                    completedDurations
                            ))
                    .put("caller_start_lag_millis",
                            SecureLogXApplicationLogBenchmark.latencyJson(
                                    schedulerDelays
                            ))
                    .put("producer_dispatch_lag_millis",
                            dispatchLag)
                    .put("bounded_executor_measured_only",
                            SecureLogXParallelOnnxBenchmark.executorStatsJson(
                                    runtimeBefore, runtimeAfter
                            ))
                    .put("pre_queue_routing_measured_only",
                            new JSONObject()
                                    .put("enabled", preQueueAfter.enabled())
                                    .put("deterministic_fields_resolved",
                                            preQueueAfter.deterministicFieldsResolved()
                                                    - preQueueBefore.deterministicFieldsResolved())
                                    .put("escalated_fields",
                                            preQueueAfter.escalatedFields()
                                                    - preQueueBefore.escalatedFields())
                                    .put("whole_event_fail_closed",
                                            preQueueAfter.wholeEventFailClosed()
                                                    - preQueueBefore.wholeEventFailClosed()))
                    .put("queue_wait",
                            queueWaitDelta(
                                    waitsBefore, waitsAfter,
                                    measuredMaxQueueWaitMillis
                            ))
                    .put("real_inference_measured_only",
                            SecureLogXApplicationLogBenchmark.inferenceMetricsJson(
                                    before, after
                            ))
                    .put("protected_sequence_audit",
                            new JSONObject()
                                    .put("records_including_warmup",
                                            validation.sequenceAudit().records())
                                    .put("unique_ingress_ids", true)
                                    .put("instance_count",
                                            validation.sequenceAudit().instances())
                                    .put("physical_order_inversions",
                                            validation.sequenceAudit()
                                                    .physicalOrderInversions())
                                    .put("numeric_reconstruction", true))
                    .put("memory_sampling_interval_millis",
                            sampleMillis)
                    .put("memory_sample_count",
                            memorySamples.size())
                    .put("memory_sample_errors",
                            memorySampleErrors.get())
                    .put("sampled_peak_working_set_bytes",
                            peakRssBytes == 0
                                    ? JSONObject.NULL : peakRssBytes)
                    .put("sampled_peak_private_bytes",
                            peakPrivateBytes == 0
                                    ? JSONObject.NULL : peakPrivateBytes)
                    .put("sampled_memory_timeline",
                            new JSONArray(memorySamples))
                    .put("jvm_gc_collection_count_delta", gcCount)
                    .put("jvm_gc_collection_millis_delta", gcMillis)
                    .put("available_processors",
                            Runtime.getRuntime().availableProcessors())
                    .put("java_version",
                            System.getProperty("java.version", "unknown"))
                    .put("process_start", processStart.toJson())
                    .put("after_warmup", warmed.toJson())
                    .put("after_work", afterWork.toJson())
                    .put("after_context_stop", afterStop.toJson())
                    .put("final_owned_contexts",
                            SecureMaskerRegistry.ownedContextCount())
                    .put("final_owned_maskers",
                            SecureMaskerRegistry.ownedMaskerCount());

            Files.writeString(resultPath,
                    result.toString(2) + System.lineSeparator(),
                    StandardCharsets.UTF_8);
            System.out.printf(
                    "STEADY ONNX rate=%d/s duration=%ds mode=%s, "
                            + "normal=%d failClosed=%d, p95=%.2fms, "
                            + "p99=%.2fms, queueAvg=%.2fms, "
                            + "observedRSSPeak=%d bytes, "
                            + "security=true functional=%s%n",
                    rate, durationSeconds, mode,
                    validation.successCount(),
                    validation.failClosedCount(),
                    result.getJSONObject("caller_latency_millis")
                            .getDouble("p95"),
                    result.getJSONObject("caller_latency_millis")
                            .getDouble("p99"),
                    result.getJSONObject("queue_wait")
                            .getDouble("average_millis"),
                    peakRssBytes,
                    functional
            );
        } finally {
            ThreadContext.clearAll();
            if (!context.isStopped()) {
                context.stop();
            }
        }
    }

    static boolean meetsRateFidelity(
            int targetRate, double dispatchRate, double p95DispatchLagMillis
    ) {
        if (targetRate <= 0 || !Double.isFinite(dispatchRate)
                || !Double.isFinite(p95DispatchLagMillis)) {
            return false;
        }
        return dispatchRate >= targetRate * 0.95
                && p95DispatchLagMillis <= 1000.0 / targetRate;
    }

    static long scheduledAtNanos(long originNanos, int index, int rate) {
        if (index < 0 || rate <= 0) {
            throw new IllegalArgumentException("Invalid fixed-rate schedule");
        }
        return originNanos
                + ((long) index * 1_000_000_000L) / rate;
    }

    private static void waitUntilNanos(long scheduledAt) {
        while (true) {
            long remaining = scheduledAt - System.nanoTime();
            if (remaining <= 0) {
                return;
            }
            if (Thread.currentThread().isInterrupted()) {
                throw new IllegalStateException(
                        "Fixed-rate arrival scheduler interrupted"
                );
            }
            LockSupport.parkNanos(Math.min(
                    remaining, TimeUnit.MILLISECONDS.toNanos(5)
            ));
        }
    }

    static JSONObject queueWaitDelta(
            MaskingQueueWaitStats before,
            MaskingQueueWaitStats after
    ) {
        return queueWaitDelta(before, after, Double.NaN);
    }

    static JSONObject queueWaitDelta(
            MaskingQueueWaitStats before,
            MaskingQueueWaitStats after,
            double measuredOnlyMaxMillis
    ) {
        if (!before.upperBoundsMillis().equals(after.upperBoundsMillis())) {
            throw new IllegalArgumentException(
                    "Queue wait histograms use different boundaries"
            );
        }
        long started = after.observedStarts() - before.observedStarts();
        long nanos = after.totalWaitNanos() - before.totalWaitNanos();
        JSONArray buckets = new JSONArray();
        for (int i = 0; i < after.bucketCounts().size(); i++) {
            buckets.put(after.bucketCounts().get(i)
                    - before.bucketCounts().get(i));
        }
        JSONObject summary = new JSONObject()
                .put("worker_started_requests", started)
                .put("total_wait_nanos", nanos)
                .put("average_millis", started == 0
                        ? 0.0 : nanos / (started * 1_000_000.0))
                .put("max_millis_including_warmup",
                        after.maxWaitMillis())
                .put("upper_bounds_millis",
                        new JSONArray(after.upperBoundsMillis()))
                .put("mutually_exclusive_bucket_counts", buckets);
        if (Double.isFinite(measuredOnlyMaxMillis)
                && measuredOnlyMaxMillis >= 0) {
            summary.put("max_millis_measured_only", measuredOnlyMaxMillis);
        }
        return summary;
    }

    private static long totalGcMillis() {
        long total = 0;
        for (GarbageCollectorMXBean gc :
                ManagementFactory.getGarbageCollectorMXBeans()) {
            total += Math.max(0, gc.getCollectionTime());
        }
        return total;
    }

    private static long totalGcCount() {
        long total = 0;
        for (GarbageCollectorMXBean gc :
                ManagementFactory.getGarbageCollectorMXBeans()) {
            total += Math.max(0, gc.getCollectionCount());
        }
        return total;
    }
}
