package com.securelogx.validation;

import com.securelogx.api.MaskedResult;
import com.securelogx.api.MaskReasonCode;
import com.securelogx.api.MaskingRuntimeStats;
import com.securelogx.api.SecureMasker;
import com.securelogx.config.SecureLogXConfig;
import com.securelogx.model.LogEvent;
import com.securelogx.model.LogLevel;
import com.securelogx.ner.impl.MaskingExecutionControl;
import com.securelogx.ner.impl.ONNXDynamicInferenceEngine;
import com.securelogx.ner.impl.ParallelTokenizer;
import com.securelogx.util.ArtifactIntegrityVerifier;
import org.json.JSONObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

/**
 * Real-model validation for the bounded masking runtime.
 *
 * Phase A starts an actual ONNX session.run(...), waits until request-specific
 * RunOptions are attached, then signals deadline cancellation and requires the
 * worker to terminate before continuing.
 *
 * Phase B uses the public SecureMasker with one active worker and queue
 * capacity 1. It forces one active request, one queued request, and verifies a
 * third request is rejected fail-closed without forwarding raw payload.
 */
public final class BoundedRuntimeOnnxCheck {

    private static final String RAW_SSN = "123-45-6789";
    private static final long CANCEL_JOIN_TIMEOUT_MILLIS = 10_000L;
    private static final long SATURATION_DEADLINE_MILLIS = 60_000L;

    private BoundedRuntimeOnnxCheck() {
    }

    public static void main(String[] args) throws Exception {
        Path resultPath = args.length > 0
                ? Path.of(args[0])
                : Path.of(
                        "reports/bounded-runtime-onnx/result.json"
                );

        SecureLogXConfig config = new SecureLogXConfig("dev");
        verifyArtifacts(config);

        RuntimeMemorySnapshot processStart =
                settledSnapshot("process-start");

        JSONObject cancellation = runNativeCancellationPhase(config);
        RuntimeMemorySnapshot afterCancellation =
                settledSnapshot("after-native-cancellation");

        JSONObject saturation = runSaturationPhase();
        RuntimeMemorySnapshot afterSaturation =
                settledSnapshot("after-saturation-close");

        JSONObject result = new JSONObject();
        result.put(
                "status",
                "BOUNDED RUNTIME REAL ONNX CHECK PASSED"
        );
        result.put("passed", true);
        result.put("native_cancellation", cancellation);
        result.put("real_model_saturation", saturation);
        result.put("process_start", processStart.toJson());
        result.put(
                "after_native_cancellation",
                afterCancellation.toJson()
        );
        result.put(
                "after_saturation_close",
                afterSaturation.toJson()
        );
        result.put(
                "model_sha256",
                config.getModelSha256()
        );
        result.put(
                "tokenizer_sha256",
                config.getTokenizerSha256()
        );
        result.put(
                "ml_decoder_mode",
                config.getMlDecoderMode().name()
        );
        result.put("sealed_challenge_inference", false);

        Path parent = resultPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(
                resultPath,
                result.toString(2) + System.lineSeparator()
        );

        System.out.println();
        System.out.println(
                "BOUNDED RUNTIME REAL ONNX CHECK PASSED"
        );
        System.out.println(
                "Native ONNX termination signalled: "
                        + cancellation.getBoolean(
                                "native_termination_signalled"
                        )
        );
        System.out.println(
                "Cancellation worker terminated before return: "
                        + cancellation.getBoolean(
                                "worker_terminated"
                        )
        );
        System.out.println(
                "Cancellation latency after signal: "
                        + cancellation.getLong(
                                "cancel_to_worker_exit_millis"
                        )
                        + " ms"
        );
        System.out.println(
                "Overload rejected requests: "
                        + saturation.getLong(
                                "overload_rejected_requests"
                        )
        );
        System.out.println(
                "Overload reject latency: "
                        + saturation.getLong(
                                "overload_reject_latency_millis"
                        )
                        + " ms"
        );
        System.out.println("Result: " + resultPath);
    }

    private static JSONObject runNativeCancellationPhase(
            SecureLogXConfig config
    ) throws Exception {
        ParallelTokenizer tokenizer = new ParallelTokenizer(
                config.getTokenizerPath(),
                config.getMaxSequenceLength()
        );

        ONNXDynamicInferenceEngine engine =
                new ONNXDynamicInferenceEngine(
                        config.getModelPath(),
                        config
                );

        String message = longMlMessage(650);
        LogEvent event = new LogEvent(
                message,
                LogLevel.INFO,
                false,
                "bounded-runtime-native-cancel",
                1L
        );

        MaskingExecutionControl control =
                MaskingExecutionControl.unbounded();

        AtomicReference<List<MaskedResult>> resultRef =
                new AtomicReference<>();
        AtomicReference<Throwable> errorRef =
                new AtomicReference<>();

        Thread worker = new Thread(
                () -> {
                    try {
                        resultRef.set(
                                engine.maskBatch(
                                        tokenizer,
                                        List.of(event),
                                        control
                                )
                        );
                    } catch (Throwable t) {
                        errorRef.set(t);
                    }
                },
                "SecureLogX-Real-ONNX-Cancel-Check"
        );

        long cancelStartNanos;
        try {
            worker.start();

            boolean nativeRunStarted =
                    control.awaitNativeRunStarted(
                            10,
                            TimeUnit.SECONDS
                    );
            if (!nativeRunStarted) {
                throw new IllegalStateException(
                        "Real ONNX run did not start within validation window"
                );
            }

            cancelStartNanos = System.nanoTime();
            control.cancel(
                    MaskingExecutionControl.CancelReason
                            .DEADLINE_EXCEEDED
            );
            worker.interrupt();

            worker.join(CANCEL_JOIN_TIMEOUT_MILLIS);
            if (worker.isAlive()) {
                throw new IllegalStateException(
                        "ONNX worker remained active after termination signal"
                );
            }

            if (errorRef.get() != null) {
                throw new IllegalStateException(
                        "Real ONNX cancellation worker failed",
                        errorRef.get()
                );
            }

            if (!control.nativeTerminationSignalled()) {
                throw new IllegalStateException(
                        "Deadline did not signal active ONNX RunOptions"
                );
            }

            List<MaskedResult> results = resultRef.get();
            if (results == null || results.size() != 1) {
                throw new IllegalStateException(
                        "Unexpected cancellation result count"
                );
            }

            MaskedResult result = results.get(0);
            if (!result.failClosed()
                    || result.reasonCode()
                    != MaskReasonCode.DEADLINE_EXCEEDED) {
                throw new IllegalStateException(
                        "Real ONNX cancellation did not fail closed "
                                + "with DEADLINE_EXCEEDED"
                );
            }

            assertNoRaw(
                    result.maskedText(),
                    RAW_SSN,
                    "native cancellation"
            );

            long cancelToExitMillis = TimeUnit.NANOSECONDS.toMillis(
                    System.nanoTime() - cancelStartNanos
            );

            JSONObject json = new JSONObject();
            json.put("passed", true);
            json.put("native_run_started", true);
            json.put(
                    "native_termination_signalled",
                    control.nativeTerminationSignalled()
            );
            json.put("worker_terminated", true);
            json.put(
                    "cancel_to_worker_exit_millis",
                    cancelToExitMillis
            );
            json.put(
                    "reason_code",
                    result.reasonCode().name()
            );
            json.put("raw_payload_forwarded", false);
            return json;
        } finally {
            if (worker.isAlive()) {
                control.cancel(
                        MaskingExecutionControl.CancelReason.SHUTDOWN
                );
                worker.interrupt();
                worker.join(CANCEL_JOIN_TIMEOUT_MILLIS);
            }
            engine.shutdown();
        }
    }

    private static JSONObject runSaturationPhase()
            throws Exception {
        PropertyOverride queueOverride = PropertyOverride.set(
                "securelogx.runtime.maskingQueueCapacity",
                "1"
        );
        PropertyOverride deadlineOverride = PropertyOverride.set(
                "securelogx.runtime.maskingDeadlineMillis",
                Long.toString(SATURATION_DEADLINE_MILLIS)
        );
        PropertyOverride shutdownOverride = PropertyOverride.set(
                "securelogx.runtime.shutdownWaitMillis",
                "10000"
        );

        try (
                queueOverride;
                deadlineOverride;
                shutdownOverride;
                SecureMasker masker = new SecureMasker("dev")
        ) {
            String firstMessage = longMlMessage(900);
            String secondMessage = longMlMessage(850);
            String thirdMessage =
                    "ssn=" + RAW_SSN + " overload=third";

            AtomicReference<MaskedResult> firstResult =
                    new AtomicReference<>();
            AtomicReference<MaskedResult> secondResult =
                    new AtomicReference<>();
            AtomicReference<Throwable> firstError =
                    new AtomicReference<>();
            AtomicReference<Throwable> secondError =
                    new AtomicReference<>();

            Thread firstCaller = new Thread(
                    () -> invokeMask(
                            masker,
                            firstMessage,
                            firstResult,
                            firstError
                    ),
                    "SecureLogX-Saturation-Caller-1"
            );
            Thread secondCaller = new Thread(
                    () -> invokeMask(
                            masker,
                            secondMessage,
                            secondResult,
                            secondError
                    ),
                    "SecureLogX-Saturation-Caller-2"
            );

            firstCaller.start();
            waitForStats(
                    masker,
                    stats -> stats.activeRequests() == 1,
                    "first request did not become active"
            );

            secondCaller.start();
            waitForStats(
                    masker,
                    stats -> stats.activeRequests() == 1
                            && stats.queuedRequests() == 1,
                    "second request did not enter bounded queue"
            );

            long rejectStart = System.nanoTime();
            MaskedResult rejected = masker.mask(thirdMessage);
            long rejectLatencyMillis = TimeUnit.NANOSECONDS.toMillis(
                    System.nanoTime() - rejectStart
            );

            if (!rejected.failClosed()
                    || rejected.reasonCode()
                    != MaskReasonCode.OVERLOAD_REJECTED) {
                throw new IllegalStateException(
                        "Third real-model request was not rejected "
                                + "fail-closed as OVERLOAD_REJECTED"
                );
            }
            assertNoRaw(
                    rejected.maskedText(),
                    RAW_SSN,
                    "overload rejection"
            );

            firstCaller.join(SATURATION_DEADLINE_MILLIS + 10_000L);
            secondCaller.join(SATURATION_DEADLINE_MILLIS + 10_000L);

            if (firstCaller.isAlive() || secondCaller.isAlive()) {
                throw new IllegalStateException(
                        "Accepted saturation requests did not terminate"
                );
            }
            if (firstError.get() != null) {
                throw new IllegalStateException(
                        "First saturation request failed",
                        firstError.get()
                );
            }
            if (secondError.get() != null) {
                throw new IllegalStateException(
                        "Second saturation request failed",
                        secondError.get()
                );
            }

            requireSuccessfulMaskedResult(
                    firstResult.get(),
                    RAW_SSN,
                    "first accepted saturation request"
            );
            requireSuccessfulMaskedResult(
                    secondResult.get(),
                    RAW_SSN,
                    "second queued saturation request"
            );

            MaskingRuntimeStats stats = masker.getRuntimeStats();
            if (stats.overloadRejectedRequests() < 1) {
                throw new IllegalStateException(
                        "Overload rejection metric was not incremented"
                );
            }
            if (stats.activeRequests() != 0
                    || stats.queuedRequests() != 0) {
                throw new IllegalStateException(
                        "Masking runtime not idle after saturation check"
                );
            }

            JSONObject json = new JSONObject();
            json.put("passed", true);
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
                    "queue_capacity",
                    stats.queueCapacity()
            );
            json.put(
                    "deadline_millis",
                    stats.deadlineMillis()
            );
            json.put(
                    "overload_reject_latency_millis",
                    rejectLatencyMillis
            );
            json.put("raw_payload_forwarded", false);
            return json;
        }
    }

    private static void invokeMask(
            SecureMasker masker,
            String message,
            AtomicReference<MaskedResult> result,
            AtomicReference<Throwable> error
    ) {
        try {
            result.set(masker.mask(message));
        } catch (Throwable t) {
            error.set(t);
        }
    }

    private static void requireSuccessfulMaskedResult(
            MaskedResult result,
            String rawValue,
            String label
    ) {
        if (result == null) {
            throw new IllegalStateException(
                    label + " produced no result"
            );
        }
        if (result.failClosed()) {
            throw new IllegalStateException(
                    label
                            + " unexpectedly failed closed with "
                            + result.reasonCode()
            );
        }
        assertNoRaw(result.maskedText(), rawValue, label);
    }

    private static void waitForStats(
            SecureMasker masker,
            Predicate<MaskingRuntimeStats> predicate,
            String failureMessage
    ) throws InterruptedException {
        long deadline = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(10);

        while (System.nanoTime() < deadline) {
            if (predicate.test(masker.getRuntimeStats())) {
                return;
            }
            Thread.sleep(5);
        }

        throw new IllegalStateException(failureMessage);
    }

    private static String longMlMessage(int segments) {
        StringBuilder message =
                new StringBuilder("name=Jane Doe ");

        for (int i = 0; i < segments; i++) {
            message.append("segment")
                    .append(i)
                    .append("=operational ");
        }

        message.append("ssn=").append(RAW_SSN);
        return message.toString();
    }

    private static void assertNoRaw(
            String output,
            String rawValue,
            String label
    ) {
        if (output.contains(rawValue)) {
            throw new IllegalStateException(
                    "Raw payload leaked during "
                            + label
                            + ": "
                            + rawValue
            );
        }
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
