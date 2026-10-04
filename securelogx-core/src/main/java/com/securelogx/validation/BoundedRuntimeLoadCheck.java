package com.securelogx.validation;

import com.securelogx.api.MaskedResult;
import com.securelogx.api.MaskReasonCode;
import com.securelogx.api.MaskingRuntimeStats;
import com.securelogx.api.SecureMasker;
import com.securelogx.config.SecureLogXConfig;
import com.securelogx.util.ArtifactIntegrityVerifier;
import org.json.JSONObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Sustained-load characterization for the bounded SecureMasker runtime.
 *
 * This is deliberately not a microbenchmark. It characterizes the current
 * single-worker safety architecture under sequential baseline and concurrent
 * admission pressure using the real H1+C1-S ONNX path.
 */
public final class BoundedRuntimeLoadCheck {

    private static final String RAW_SSN = "123-45-6789";
    private static final int QUEUE_CAPACITY = 4;
    private static final long DEADLINE_MILLIS = 30_000L;
    private static final long SHUTDOWN_WAIT_MILLIS = 10_000L;
    private static final int DEFAULT_BASELINE_REQUESTS = 8;
    private static final int DEFAULT_CONCURRENT_CALLERS = 12;
    private static final int DEFAULT_ATTEMPTS_PER_CALLER = 4;

    private BoundedRuntimeLoadCheck() {
    }

    public static void main(String[] args) throws Exception {
        Path resultPath = args.length > 0
                ? Path.of(args[0])
                : Path.of(
                        "reports/bounded-runtime-load/result.json"
                );
        int baselineRequests = args.length > 1
                ? Integer.parseInt(args[1])
                : DEFAULT_BASELINE_REQUESTS;
        int concurrentCallers = args.length > 2
                ? Integer.parseInt(args[2])
                : DEFAULT_CONCURRENT_CALLERS;
        int attemptsPerCaller = args.length > 3
                ? Integer.parseInt(args[3])
                : DEFAULT_ATTEMPTS_PER_CALLER;

        requirePositive("baselineRequests", baselineRequests);
        requirePositive("concurrentCallers", concurrentCallers);
        requirePositive("attemptsPerCaller", attemptsPerCaller);

        SecureLogXConfig config = new SecureLogXConfig("dev");
        verifyArtifacts(config);

        RuntimeMemorySnapshot processStart =
                settledSnapshot("process-start");

        PropertyOverride queueOverride = PropertyOverride.set(
                "securelogx.runtime.maskingQueueCapacity",
                Integer.toString(QUEUE_CAPACITY)
        );
        PropertyOverride deadlineOverride = PropertyOverride.set(
                "securelogx.runtime.maskingDeadlineMillis",
                Long.toString(DEADLINE_MILLIS)
        );
        PropertyOverride shutdownOverride = PropertyOverride.set(
                "securelogx.runtime.shutdownWaitMillis",
                Long.toString(SHUTDOWN_WAIT_MILLIS)
        );

        JSONObject baselineJson;
        JSONObject saturationJson;
        RuntimeMemorySnapshot afterInit;
        RuntimeMemorySnapshot afterBaseline;
        RuntimeMemorySnapshot afterSaturation;
        RuntimeMemorySnapshot afterClose;

        try (
                queueOverride;
                deadlineOverride;
                shutdownOverride
        ) {
            try (SecureMasker masker = new SecureMasker("dev")) {
                afterInit = settledSnapshot("after-masker-init");

                warmup(masker);

                List<Attempt> baseline = runBaseline(
                        masker,
                        baselineRequests
                );
                afterBaseline =
                        settledSnapshot("after-baseline");

                baselineJson = summarizeAttempts(
                        "sequential-baseline",
                        baseline,
                        -1L
                );

                long saturationStart = System.nanoTime();
                List<Attempt> saturation = runSaturation(
                        masker,
                        concurrentCallers,
                        attemptsPerCaller
                );
                long saturationElapsedNanos =
                        System.nanoTime() - saturationStart;

                afterSaturation =
                        settledSnapshot("after-saturation");

                saturationJson = summarizeAttempts(
                        "concurrent-saturation",
                        saturation,
                        saturationElapsedNanos
                );

                MaskingRuntimeStats stats =
                        masker.getRuntimeStats();

                saturationJson.put(
                        "runtime_stats",
                        runtimeStatsJson(stats)
                );

                validateSaturationEvidence(
                        saturation,
                        stats
                );
            }

            afterClose = settledSnapshot("after-masker-close");
        }

        JSONObject result = new JSONObject();
        result.put(
                "status",
                "BOUNDED RUNTIME LOAD CHARACTERIZATION PASSED"
        );
        result.put("passed", true);
        result.put("baseline", baselineJson);
        result.put("saturation", saturationJson);
        result.put("process_start", processStart.toJson());
        result.put("after_masker_init", afterInit.toJson());
        result.put("after_baseline", afterBaseline.toJson());
        result.put("after_saturation", afterSaturation.toJson());
        result.put("after_masker_close", afterClose.toJson());
        result.put(
                "saturation_memory_delta",
                memoryDelta(afterBaseline, afterSaturation)
        );
        result.put(
                "post_close_memory_delta",
                memoryDelta(processStart, afterClose)
        );
        result.put("queue_capacity", QUEUE_CAPACITY);
        result.put("deadline_millis", DEADLINE_MILLIS);
        result.put("active_masking_workers", 1);
        result.put("baseline_requests", baselineRequests);
        result.put("concurrent_callers", concurrentCallers);
        result.put(
                "attempts_per_caller",
                attemptsPerCaller
        );
        result.put("model_sha256", config.getModelSha256());
        result.put(
                "tokenizer_sha256",
                config.getTokenizerSha256()
        );
        result.put(
                "ml_decoder_mode",
                config.getMlDecoderMode().name()
        );
        result.put("sealed_challenge_inference", false);
        result.put("raw_payload_forwarded", false);

        Path parent = resultPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(
                resultPath,
                result.toString(2) + System.lineSeparator()
        );

        printSummary(baselineJson, saturationJson, resultPath);
    }

    private static void warmup(SecureMasker masker) {
        for (int i = 0; i < 2; i++) {
            MaskedResult result = masker.mask(
                    workloadMessage("warmup", i)
            );
            requireSafeResult(result, "warmup-" + i);
            if (result.failClosed()) {
                throw new IllegalStateException(
                        "Warmup unexpectedly failed closed: "
                                + result.reasonCode()
                );
            }
        }
    }

    private static List<Attempt> runBaseline(
            SecureMasker masker,
            int requests
    ) {
        List<Attempt> attempts =
                new ArrayList<>(requests);

        for (int i = 0; i < requests; i++) {
            attempts.add(
                    invoke(
                            masker,
                            workloadMessage("baseline", i)
                    )
            );
        }

        for (Attempt attempt : attempts) {
            if (attempt.failClosed()) {
                throw new IllegalStateException(
                        "Sequential baseline failed closed: "
                                + attempt.reasonCode()
                );
            }
            if (!attempt.mlInvoked()) {
                throw new IllegalStateException(
                        "Sequential baseline did not exercise ML"
                );
            }
        }

        return List.copyOf(attempts);
    }

    private static List<Attempt> runSaturation(
            SecureMasker masker,
            int concurrentCallers,
            int attemptsPerCaller
    ) throws Exception {
        ExecutorService callers =
                Executors.newFixedThreadPool(concurrentCallers);
        ConcurrentLinkedQueue<Attempt> results =
                new ConcurrentLinkedQueue<>();
        CountDownLatch ready =
                new CountDownLatch(concurrentCallers);
        CountDownLatch start = new CountDownLatch(1);

        try {
            for (int caller = 0;
                 caller < concurrentCallers;
                 caller++) {
                int callerId = caller;
                callers.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        for (int attempt = 0;
                             attempt < attemptsPerCaller;
                             attempt++) {
                            results.add(
                                    invoke(
                                            masker,
                                            workloadMessage(
                                                    "sat-"
                                                            + callerId,
                                                    attempt
                                            )
                                    )
                            );
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(
                                "Load caller interrupted",
                                e
                        );
                    }
                });
            }

            if (!ready.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException(
                        "Concurrent callers did not become ready"
                );
            }

            start.countDown();

            callers.shutdown();
            if (!callers.awaitTermination(
                    120,
                    TimeUnit.SECONDS
            )) {
                callers.shutdownNow();
                throw new IllegalStateException(
                        "Concurrent saturation callers did not terminate"
                );
            }
        } finally {
            callers.shutdownNow();
        }

        int expected =
                concurrentCallers * attemptsPerCaller;
        if (results.size() != expected) {
            throw new IllegalStateException(
                    "Saturation result count mismatch. expected="
                            + expected
                            + " actual="
                            + results.size()
            );
        }

        return List.copyOf(results);
    }

    private static Attempt invoke(
            SecureMasker masker,
            String message
    ) {
        long start = System.nanoTime();
        MaskedResult result = masker.mask(message);
        long elapsedNanos = System.nanoTime() - start;

        requireSafeResult(result, "load request");

        return new Attempt(
                elapsedNanos,
                result.failClosed(),
                result.mlInvoked(),
                result.reasonCode()
        );
    }

    private static void requireSafeResult(
            MaskedResult result,
            String label
    ) {
        if (result == null) {
            throw new IllegalStateException(
                    label + " produced no result"
            );
        }
        if (result.maskedText().contains(RAW_SSN)) {
            throw new IllegalStateException(
                    "Raw SSN leaked during " + label
            );
        }
    }

    private static void validateSaturationEvidence(
            List<Attempt> attempts,
            MaskingRuntimeStats stats
    ) {
        long overloads = attempts.stream()
                .filter(
                        item -> item.reasonCode()
                                == MaskReasonCode
                                        .OVERLOAD_REJECTED
                )
                .count();

        if (overloads < 1
                || stats.overloadRejectedRequests() < 1) {
            throw new IllegalStateException(
                    "Saturation load did not produce a measured "
                            + "overload rejection"
            );
        }

        long successfulMl = attempts.stream()
                .filter(item -> !item.failClosed())
                .filter(Attempt::mlInvoked)
                .count();

        if (successfulMl < 1) {
            throw new IllegalStateException(
                    "Saturation load produced no successful ML request"
            );
        }

        if (stats.activeRequests() != 0
                || stats.queuedRequests() != 0) {
            throw new IllegalStateException(
                    "Bounded runtime not idle after load"
            );
        }

        if (stats.peakQueuedRequests() > stats.queueCapacity()) {
            throw new IllegalStateException(
                    "Observed queue depth exceeded configured capacity"
            );
        }
    }

    private static JSONObject summarizeAttempts(
            String name,
            List<Attempt> attempts,
            long elapsedNanos
    ) {
        List<Long> allLatencies = attempts.stream()
                .map(Attempt::elapsedNanos)
                .sorted()
                .toList();
        List<Long> successLatencies = attempts.stream()
                .filter(item -> !item.failClosed())
                .map(Attempt::elapsedNanos)
                .sorted()
                .toList();
        List<Long> rejectLatencies = attempts.stream()
                .filter(
                        item -> item.reasonCode()
                                == MaskReasonCode
                                        .OVERLOAD_REJECTED
                )
                .map(Attempt::elapsedNanos)
                .sorted()
                .toList();

        long successes = attempts.stream()
                .filter(item -> !item.failClosed())
                .count();
        long overloads = attempts.stream()
                .filter(
                        item -> item.reasonCode()
                                == MaskReasonCode
                                        .OVERLOAD_REJECTED
                )
                .count();
        long deadlines = attempts.stream()
                .filter(
                        item -> item.reasonCode()
                                == MaskReasonCode
                                        .DEADLINE_EXCEEDED
                )
                .count();
        long failures = attempts.stream()
                .filter(Attempt::failClosed)
                .filter(
                        item -> item.reasonCode()
                                != MaskReasonCode
                                        .OVERLOAD_REJECTED
                )
                .filter(
                        item -> item.reasonCode()
                                != MaskReasonCode
                                        .DEADLINE_EXCEEDED
                )
                .count();
        long mlInvoked = attempts.stream()
                .filter(Attempt::mlInvoked)
                .count();

        JSONObject json = new JSONObject();
        json.put("name", name);
        json.put("attempts", attempts.size());
        json.put("successful", successes);
        json.put("ml_invoked", mlInvoked);
        json.put("overload_rejected", overloads);
        json.put("deadline_exceeded", deadlines);
        json.put("other_fail_closed", failures);
        json.put(
                "overload_reject_rate",
                attempts.isEmpty()
                        ? 0.0
                        : (double) overloads / attempts.size()
        );
        json.put(
                "all_latency_millis",
                latencyJson(allLatencies)
        );
        json.put(
                "successful_latency_millis",
                latencyJson(successLatencies)
        );
        json.put(
                "overload_reject_latency_millis",
                latencyJson(rejectLatencies)
        );

        if (elapsedNanos > 0) {
            double seconds =
                    elapsedNanos / 1_000_000_000.0;
            json.put(
                    "attempts_per_second",
                    attempts.size() / seconds
            );
            json.put(
                    "successful_per_second",
                    successes / seconds
            );
            json.put(
                    "elapsed_millis",
                    TimeUnit.NANOSECONDS.toMillis(
                            elapsedNanos
                    )
            );
        }

        return json;
    }

    private static JSONObject latencyJson(
            List<Long> sortedNanos
    ) {
        JSONObject json = new JSONObject();
        json.put("count", sortedNanos.size());

        if (sortedNanos.isEmpty()) {
            json.put("p50", JSONObject.NULL);
            json.put("p95", JSONObject.NULL);
            json.put("p99", JSONObject.NULL);
            json.put("max", JSONObject.NULL);
            return json;
        }

        json.put(
                "p50",
                millis(percentile(sortedNanos, 0.50))
        );
        json.put(
                "p95",
                millis(percentile(sortedNanos, 0.95))
        );
        json.put(
                "p99",
                millis(percentile(sortedNanos, 0.99))
        );
        json.put(
                "max",
                millis(sortedNanos.get(
                        sortedNanos.size() - 1
                ))
        );
        return json;
    }

    private static long percentile(
            List<Long> sorted,
            double percentile
    ) {
        int index = (int) Math.ceil(
                percentile * sorted.size()
        ) - 1;
        index = Math.max(
                0,
                Math.min(index, sorted.size() - 1)
        );
        return sorted.get(index);
    }

    private static double millis(long nanos) {
        return nanos / 1_000_000.0;
    }

    private static JSONObject runtimeStatsJson(
            MaskingRuntimeStats stats
    ) {
        JSONObject json = new JSONObject();
        json.put(
                "accepted_requests",
                stats.acceptedRequests()
        );
        json.put(
                "completed_requests",
                stats.completedRequests()
        );
        json.put(
                "overload_rejected_requests",
                stats.overloadRejectedRequests()
        );
        json.put(
                "deadline_exceeded_requests",
                stats.deadlineExceededRequests()
        );
        json.put(
                "native_termination_signals",
                stats.nativeTerminationSignals()
        );
        json.put(
                "execution_failure_requests",
                stats.executionFailureRequests()
        );
        json.put(
                "active_requests",
                stats.activeRequests()
        );
        json.put(
                "queued_requests",
                stats.queuedRequests()
        );
        json.put(
                "peak_active_requests",
                stats.peakActiveRequests()
        );
        json.put(
                "peak_queued_requests",
                stats.peakQueuedRequests()
        );
        json.put(
                "queue_capacity",
                stats.queueCapacity()
        );
        json.put(
                "deadline_millis",
                stats.deadlineMillis()
        );
        return json;
    }

    private static JSONObject memoryDelta(
            RuntimeMemorySnapshot before,
            RuntimeMemorySnapshot after
    ) {
        JSONObject json = new JSONObject();
        json.put(
                "heap_used_bytes",
                after.heapUsedBytes()
                        - before.heapUsedBytes()
        );
        json.put(
                "non_heap_used_bytes",
                after.nonHeapUsedBytes()
                        - before.nonHeapUsedBytes()
        );
        json.put(
                "direct_used_bytes",
                after.directUsedBytes()
                        - before.directUsedBytes()
        );
        putOptionalDelta(
                json,
                "process_working_set_bytes",
                before.processWorkingSetBytes(),
                after.processWorkingSetBytes()
        );
        putOptionalDelta(
                json,
                "process_private_bytes",
                before.processPrivateBytes(),
                after.processPrivateBytes()
        );
        putOptionalDelta(
                json,
                "gpu_process_memory_bytes",
                before.gpuProcessMemoryBytes(),
                after.gpuProcessMemoryBytes()
        );
        return json;
    }

    private static void putOptionalDelta(
            JSONObject json,
            String key,
            long before,
            long after
    ) {
        if (before < 0 || after < 0) {
            json.put(key, JSONObject.NULL);
        } else {
            json.put(key, after - before);
        }
    }

    private static String workloadMessage(
            String prefix,
            int id
    ) {
        return "name=Jane Doe action=login "
                + "ssn="
                + RAW_SSN
                + " request="
                + prefix
                + "-"
                + id;
    }

    private static void verifyArtifacts(
            SecureLogXConfig config
    ) throws Exception {
        ArtifactIntegrityVerifier.verifySha256(
                "ML-v1.3 ONNX model",
                config.getModelPath(),
                config.getModelSha256()
        );
        ArtifactIntegrityVerifier.verifySha256(
                "ML-v1.3 tokenizer",
                config.getTokenizerPath(),
                config.getTokenizerSha256()
        );
    }

    private static RuntimeMemorySnapshot settledSnapshot(
            String label
    ) throws InterruptedException {
        System.gc();
        Thread.sleep(400);
        System.gc();
        Thread.sleep(400);
        return RuntimeMemorySnapshot.capture(label);
    }

    private static void requirePositive(
            String name,
            int value
    ) {
        if (value < 1) {
            throw new IllegalArgumentException(
                    name + " must be at least 1"
            );
        }
    }

    private static void printSummary(
            JSONObject baseline,
            JSONObject saturation,
            Path resultPath
    ) {
        JSONObject baselineLatency =
                baseline.getJSONObject(
                        "successful_latency_millis"
                );
        JSONObject saturationLatency =
                saturation.getJSONObject(
                        "successful_latency_millis"
                );
        JSONObject rejectLatency =
                saturation.getJSONObject(
                        "overload_reject_latency_millis"
                );

        System.out.println();
        System.out.println(
                "BOUNDED RUNTIME LOAD CHARACTERIZATION PASSED"
        );
        System.out.println(
                "Baseline successful p50/p95/p99: "
                        + baselineLatency.get("p50")
                        + " / "
                        + baselineLatency.get("p95")
                        + " / "
                        + baselineLatency.get("p99")
                        + " ms"
        );
        System.out.println(
                "Saturation successful p50/p95/p99: "
                        + saturationLatency.get("p50")
                        + " / "
                        + saturationLatency.get("p95")
                        + " / "
                        + saturationLatency.get("p99")
                        + " ms"
        );
        System.out.println(
                "Overload rejected: "
                        + saturation.getLong(
                                "overload_rejected"
                        )
                        + "/"
                        + saturation.getInt("attempts")
        );
        System.out.println(
                "Reject p50/p95/p99: "
                        + rejectLatency.get("p50")
                        + " / "
                        + rejectLatency.get("p95")
                        + " / "
                        + rejectLatency.get("p99")
                        + " ms"
        );
        System.out.println(
                "Successful throughput: "
                        + String.format(
                                "%.3f",
                                saturation.getDouble(
                                        "successful_per_second"
                                )
                        )
                        + " req/s"
        );
        System.out.println("Raw payload forwarded: false");
        System.out.println("Result: " + resultPath);
    }

    private record Attempt(
            long elapsedNanos,
            boolean failClosed,
            boolean mlInvoked,
            MaskReasonCode reasonCode
    ) {
    }

    private static final class PropertyOverride
            implements AutoCloseable {
        private final String key;
        private final String previous;

        private PropertyOverride(
                String key,
                String value
        ) {
            this.key = key;
            this.previous = System.getProperty(key);
            System.setProperty(key, value);
        }

        static PropertyOverride set(
                String key,
                String value
        ) {
            return new PropertyOverride(key, value);
        }

        @Override
        public void close() {
            if (previous == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, previous);
            }
        }
    }
}
