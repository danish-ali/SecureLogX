package com.securelogx.log4j2;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecureLogXApplicationLogBenchmarkTest {

    @Test
    void acceptsTwelveUniqueSafeEvents() {
        StringBuilder output = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            output.append(eventLine(i));
        }

        var result =
                SecureLogXApplicationLogBenchmark.validateOutput(
                        output.toString(), 12
                );
        assertEquals(12, result.measuredOutputEvents());
        assertEquals(1, result.mlInvokedEvents());
        assertEquals(0, result.failClosedEvents());
    }

    @Test
    void reportsFailClosedReasonBeforeCountMismatch() {
        StringBuilder output = new StringBuilder();
        for (int i = 0; i < 11; i++) {
            output.append(eventLine(i));
        }
        // RewritePolicy removes original eventId from fail-closed events.
        output.append(
                "eventId=|level=DEBUG|ml=false|failClosed=true"
                        + "|reason=DEADLINE_EXCEEDED|customer=|stack="
                        + "|msg=[SECURELOGX_REDACTED_PROCESSING_FAILURE]"
                        + "|throwable=\n"
        );

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> SecureLogXApplicationLogBenchmark.validateOutput(
                        output.toString(), 12
                )
        );
        assertTrue(exception.getMessage().contains("fail-closed 1"));
        assertTrue(exception.getMessage().contains("DEADLINE_EXCEEDED=1"));
    }

    @Test
    void reportsMissingEventIdentity() {
        StringBuilder output = new StringBuilder();
        for (int i = 0; i < 11; i++) {
            output.append(eventLine(i));
        }

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> SecureLogXApplicationLogBenchmark.validateOutput(
                        output.toString(), 12
                )
        );
        assertTrue(exception.getMessage().contains("missingBenchIds=[11]"));
    }

    @Test
    void rejectsDuplicateEventEvenWhenCountMatches() {
        StringBuilder output = new StringBuilder();
        for (int i = 0; i < 11; i++) {
            output.append(eventLine(i));
        }
        output.append(eventLine(10));

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> SecureLogXApplicationLogBenchmark.validateOutput(
                        output.toString(), 12
                )
        );
        assertTrue(exception.getMessage().contains("missingBenchIds=[11]"));
        assertTrue(exception.getMessage().contains("duplicateBenchIds=[10]"));
    }

    @Test
    void measuredOnnxCountsAndStagesExcludeWarmup() {
        var before = new SecureLogXInferenceDiagnostics.Snapshot(
                new com.securelogx.detection.HybridRuntimeStats(
                        2, 3, 1, 6, 4, 3, 2, 3, 0
                ),
                new com.securelogx.detection.InferenceStageStats(
                        1000000L, 2000000L, 3000000L, 4000000L,
                        5000000L, 3000000L, 6000000L, 7000000L
                )
        );
        var after = new SecureLogXInferenceDiagnostics.Snapshot(
                new com.securelogx.detection.HybridRuntimeStats(
                        5, 8, 3, 26, 12, 4, 5, 4, 0
                ),
                new com.securelogx.detection.InferenceStageStats(
                        3000000L, 5000000L, 3000000L, 7000000L,
                        15000000L, 9000000L, 8000000L, 9000000L
                )
        );

        org.json.JSONObject measured =
                SecureLogXApplicationLogBenchmark.inferenceMetricsJson(
                        before, after
                );
        assertEquals(8, measured.getInt("onnx_inference_calls"));
        assertEquals(20, measured.getInt("ml_inference_windows"));
        assertEquals(3, measured.getInt("windowed_onnx_inference_calls"));
        assertEquals(5, measured.getInt("ml_inference_items"));
        assertEquals(10.0, measured.getJSONObject("stage_total_millis")
                .getDouble("onnx_native_run"), 0.00001);
        assertEquals(6.0, measured.getJSONObject("stage_total_millis")
                .getDouble("onnx_windowed_native_run_subset"), 0.00001);
    }

    private static String eventLine(int number) {
        String level = switch (number % 4) {
            case 0 -> "INFO";
            case 1 -> "WARN";
            case 2 -> "ERROR";
            default -> "DEBUG";
        };
        String message = number == 6
                ? "version=1.2.3.4"
                : "safe-control";
        return "eventId=bench-" + number
                + "|level=" + level
                + "|ml=" + (number == 0)
                + "|failClosed=false|reason=DETERMINISTIC_RESOLVED"
                + "|customer=|stack=|msg=" + message
                + "|throwable=\n";
    }
}
