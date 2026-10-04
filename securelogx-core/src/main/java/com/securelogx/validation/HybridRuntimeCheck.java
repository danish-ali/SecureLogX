package com.securelogx.validation;

import com.securelogx.config.SecureLogXConfig;
import com.securelogx.detection.HybridRuntimeStats;
import com.securelogx.model.LogEvent;
import com.securelogx.model.LogLevel;
import com.securelogx.ner.impl.ONNXDynamicInferenceEngine;
import com.securelogx.ner.impl.ParallelTokenizer;
import com.securelogx.util.ArtifactIntegrityVerifier;
import org.json.JSONObject;

import java.nio.file.Files;
import java.time.Instant;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * End-to-end check for deterministic bypass, ML escalation, negative-context
 * preservation, and fail-closed production routing.
 */
public final class HybridRuntimeCheck {

    private HybridRuntimeCheck() {
    }

    public static void main(String[] args) throws Exception {
        Path resultPath = args.length > 0
                ? Path.of(args[0])
                : Path.of("reports/hybrid-runtime-check/result.json");

        SecureLogXConfig config = new SecureLogXConfig("dev");
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

        ParallelTokenizer tokenizer = new ParallelTokenizer(
                config.getTokenizerPath(),
                config.getMaxSequenceLength()
        );

        List<String> messages = new ArrayList<>(List.of(
                "email=jane.doe@example.com status=ok",
                "ssn=123-45-6789 status=verified",
                "card=4111 1111 1111 1111 status=declined",
                "routing=021000021 status=pending",
                "iban=GB82WEST12345698765432 status=pending",
                "remoteIp=10.20.30.40 status=blocked",
                "releaseVersion=10.20.30.40 deployment=canary",
                "{\"@timestamp\":\"2026-09-21T10:00:00Z\",\"log.level\":\"INFO\",\"service.name\":\"payments\",\"message\":\"email=json.user@example.com status=ok\"}",
                "customerId=CUST-938271 lifecycle=active",
                "name=Jane Doe action=login"
        ));

        StringBuilder longMessage = new StringBuilder("name=Jane Doe ");
        for (int i = 0; i < 450; i++) {
            longMessage.append("segment")
                    .append(i)
                    .append("=operational ");
        }
        String tailSensitiveValue = "123-45-6789";
        longMessage.append("ssn=").append(tailSensitiveValue);
        messages.add(longMessage.toString());
        int windowedCaseIndex = messages.size() - 1;

        List<LogLevel> levels = List.of(
                LogLevel.INFO,
                LogLevel.WARN,
                LogLevel.ERROR,
                LogLevel.DEBUG,
                LogLevel.INFO,
                LogLevel.WARN,
                LogLevel.DEBUG,
                LogLevel.INFO,
                LogLevel.WARN,
                LogLevel.ERROR,
                LogLevel.INFO
        );

        long baseEventTimestamp =
                Instant.parse("2026-10-01T10:00:00Z").toEpochMilli();

        List<LogEvent> events = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            events.add(
                    new LogEvent(
                            messages.get(i),
                            levels.get(i),
                            false,
                            "hybrid-runtime-check",
                            i + 1L,
                            baseEventTimestamp + i
                    )
            );
        }

        List<String> outputs;
        HybridRuntimeStats stats;
        try (RuntimeEngine engine = new RuntimeEngine(config)) {
            outputs = engine.run(tokenizer, events);
            stats = engine.stats();
        }

        if (outputs.size() != messages.size()) {
            throw new IllegalStateException(
                    "Output count mismatch. expected="
                            + messages.size()
                            + " actual="
                            + outputs.size()
            );
        }

        for (int i = 0; i < outputs.size(); i++) {
            String output = outputs.get(i);
            if (output.contains("[SECURELOGX_REDACTED_PROCESSING_FAILURE]")) {
                throw new IllegalStateException(
                        "Hybrid runtime entered fail-closed fallback unexpectedly"
                );
            }

            String expectedLevel = "level=" + levels.get(i).name();
            if (!output.contains(expectedLevel)) {
                throw new IllegalStateException(
                        "Source log severity was not preserved. expected="
                                + expectedLevel
                                + " output="
                                + output
                );
            }

            String expectedSequence = "seq=" + (i + 1L);
            if (!output.contains(expectedSequence)) {
                throw new IllegalStateException(
                        "Log sequence was not preserved. expected="
                                + expectedSequence
                                + " output="
                                + output
                );
            }

            String expectedTimestamp =
                    "timestamp="
                            + Instant.ofEpochMilli(
                                    baseEventTimestamp + i
                            );
            if (!output.contains(expectedTimestamp)) {
                throw new IllegalStateException(
                        "Original event timestamp was not preserved. expected="
                                + expectedTimestamp
                                + " output="
                                + output
                );
            }

            if (!output.contains("instanceId=")) {
                throw new IllegalStateException(
                        "SecureLogX instanceId missing from output envelope"
                );
            }
        }

        LogEvent roundTrip = LogEvent.fromRaw(outputs.get(0));
        if (roundTrip.getLevel() != levels.get(0)
                || roundTrip.getSequenceNumber() != 1L
                || roundTrip.getEventTimestamp() != baseEventTimestamp
                || !roundTrip.getInstanceId()
                        .equals(events.get(0).getInstanceId())) {
            throw new IllegalStateException(
                    "Formatted LogEvent metadata did not survive round-trip"
            );
        }

        String windowedOutput = outputs.get(windowedCaseIndex);
        if (windowedOutput.contains(tailSensitiveValue)) {
            throw new IllegalStateException(
                    "Windowed inference leaked tail SSN beyond the first "
                            + "model window"
            );
        }

        assertNotPresent(outputs.get(0), "jane.doe@example.com", "EMAIL");
        assertNotPresent(outputs.get(1), "123-45-6789", "SSN");
        assertNotPresent(outputs.get(2), "4111 1111 1111 1111", "CREDIT_CARD_NUMBER");
        assertNotPresent(outputs.get(3), "021000021", "ROUTING_NUMBER");
        assertNotPresent(outputs.get(4), "GB82WEST12345698765432", "IBAN");
        assertNotPresent(outputs.get(5), "10.20.30.40", "network IP");

        if (!outputs.get(6).contains("releaseVersion=10.20.30.40")) {
            throw new IllegalStateException(
                    "Negative technical IP reference was unexpectedly changed: "
                            + outputs.get(6)
            );
        }

        assertNotPresent(
                outputs.get(7),
                "json.user@example.com",
                "JSON-envelope EMAIL"
        );

        if (stats.deterministicOnlyItems() < 6) {
            throw new IllegalStateException(
                    "Expected at least 5 deterministic-only routes, got "
                            + stats.deterministicOnlyItems()
            );
        }

        if (stats.mlInferenceItems() < 4) {
            throw new IllegalStateException(
                    "Expected at least 4 ML-routed records after explicit "
                            + "SSN fields became deterministic protection "
                            + "floors, got "
                            + stats.mlInferenceItems()
            );
        }

        if (stats.windowedMlItems() != 1) {
            throw new IllegalStateException(
                    "Expected exactly one windowed ML record, got "
                            + stats.windowedMlItems()
            );
        }

        if (stats.mlInferenceWindows() <= stats.mlInferenceItems()) {
            throw new IllegalStateException(
                    "Expected more inference windows than ML records when "
                            + "the long-message case is windowed"
            );
        }

        if (stats.onnxInferenceCalls() < 1) {
            throw new IllegalStateException(
                    "Expected at least one ONNX inference call"
            );
        }

        if (stats.maxWindowedInferenceWindowsPerCallObserved()
                > config.getMaxInferenceWindowsPerBatch()) {
            throw new IllegalStateException(
                    "Expanded-window ONNX micro-batch cap exceeded. "
                            + "observed="
                            + stats.maxWindowedInferenceWindowsPerCallObserved()
                            + " configured="
                            + config.getMaxInferenceWindowsPerBatch()
            );
        }

        if (stats.truncatedFailClosedItems() != 0) {
            throw new IllegalStateException(
                    "Windowed tokenizer unexpectedly failed closed, count="
                            + stats.truncatedFailClosedItems()
            );
        }

        if (stats.totalRoutedItems() != messages.size()) {
            throw new IllegalStateException(
                    "Hybrid routing stats do not cover the whole batch. routed="
                            + stats.totalRoutedItems()
                            + " expected="
                            + messages.size()
            );
        }

        JSONObject result = new JSONObject();
        result.put("status", "HYBRID RUNTIME CHECK PASSED");
        result.put("passed", true);
        result.put("cases", messages.size());
        result.put(
                "deterministic_only_items",
                stats.deterministicOnlyItems()
        );
        result.put("ml_inference_items", stats.mlInferenceItems());
        result.put("ml_invocation_rate", stats.mlInvocationRate());
        result.put("negative_ip_reference_preserved", true);
        result.put("standard_log_levels_protected", true);
        result.put("source_severity_preserved", true);
        result.put("event_timestamp_preserved", true);
        result.put("sequence_preserved", true);
        result.put("instance_id_present", true);
        result.put("formatted_event_round_trip_preserved", true);
        result.put("processing_failures", 0);
        result.put(
                "windowed_ml_items",
                stats.windowedMlItems()
        );
        result.put(
                "ml_inference_windows",
                stats.mlInferenceWindows()
        );
        result.put(
                "onnx_inference_calls",
                stats.onnxInferenceCalls()
        );
        result.put(
                "max_inference_windows_per_call_observed",
                stats.maxInferenceWindowsPerCallObserved()
        );
        result.put(
                "windowed_onnx_inference_calls",
                stats.windowedOnnxInferenceCalls()
        );
        result.put(
                "max_windowed_inference_windows_per_call_observed",
                stats.maxWindowedInferenceWindowsPerCallObserved()
        );
        result.put(
                "configured_max_windowed_inference_windows_per_batch",
                config.getMaxInferenceWindowsPerBatch()
        );
        result.put(
                "average_windows_per_ml_item",
                stats.averageWindowsPerMlItem()
        );
        result.put(
                "average_windows_per_onnx_call",
                stats.averageWindowsPerOnnxCall()
        );
        result.put(
                "truncated_fail_closed_items",
                stats.truncatedFailClosedItems()
        );
        result.put("fail_closed_policy_enabled", true);
        result.put("model_sha256", config.getModelSha256());
        result.put("tokenizer_sha256", config.getTokenizerSha256());
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

        System.out.println("HYBRID RUNTIME CHECK PASSED");
        System.out.println(
                "Deterministic-only: "
                        + stats.deterministicOnlyItems()
                        + "/"
                        + stats.totalRoutedItems()
        );
        System.out.println(
                "ML inference: "
                        + stats.mlInferenceItems()
                        + "/"
                        + stats.totalRoutedItems()
        );
        System.out.println(
                "ML invocation rate: "
                        + String.format("%.2f%%", stats.mlInvocationRate() * 100.0)
        );
        System.out.println("Negative IP reference preserved: true");
        System.out.println("Standard INFO/WARN/ERROR/DEBUG protected: true");
        System.out.println("Source severity preserved: true");
        System.out.println("Event timestamp preserved: true");
        System.out.println("Sequence metadata preserved: true");
        System.out.println("Instance ID present: true");
        System.out.println("Formatted event round-trip preserved: true");
        System.out.println("Processing failures: 0");
        System.out.println(
                "ML decoder mode: "
                        + config.getMlDecoderMode().name()
        );
        System.out.println(
                "Windowed ML items: "
                        + stats.windowedMlItems()
        );
        System.out.println(
                "ML inference windows: "
                        + stats.mlInferenceWindows()
        );
        System.out.println(
                "ONNX inference calls: "
                        + stats.onnxInferenceCalls()
        );
        System.out.println(
                "Max windows per ONNX call: "
                        + stats.maxInferenceWindowsPerCallObserved()
        );
        System.out.println(
                "Expanded-window ONNX calls: "
                        + stats.windowedOnnxInferenceCalls()
        );
        System.out.println(
                "Max expanded windows per ONNX call: "
                        + stats.maxWindowedInferenceWindowsPerCallObserved()
                        + "/"
                        + config.getMaxInferenceWindowsPerBatch()
        );
        System.out.println(
                "Truncated fail-closed items: "
                        + stats.truncatedFailClosedItems()
        );
        System.out.println("Result: " + resultPath);
    }

    private static void assertNotPresent(
            String output,
            String rawValue,
            String label
    ) {
        if (output.contains(rawValue)) {
            throw new IllegalStateException(
                    "Raw "
                            + label
                            + " value was not masked: "
                            + rawValue
            );
        }
    }

    private static final class RuntimeEngine implements AutoCloseable {
        private final ONNXDynamicInferenceEngine delegate;

        private RuntimeEngine(SecureLogXConfig config) throws Exception {
            this.delegate = new ONNXDynamicInferenceEngine(
                    config.getModelPath(),
                    config
            );
        }

        private List<String> run(
                ParallelTokenizer tokenizer,
                List<LogEvent> events
        ) {
            return delegate.runBatch(tokenizer, events);
        }

        private HybridRuntimeStats stats() {
            return delegate.getHybridRuntimeStats();
        }

        @Override
        public void close() {
            delegate.shutdown();
        }
    }
}
