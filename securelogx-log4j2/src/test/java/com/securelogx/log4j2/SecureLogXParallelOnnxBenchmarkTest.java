package com.securelogx.log4j2;

import com.securelogx.api.MaskingRuntimeStats;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SecureLogXParallelOnnxBenchmarkTest {

    private static final String INSTANCE =
            "11111111-1111-4111-8111-111111111111";

    @Test
    void treatsFailClosedAsDeliveredButNotSuccessfullyMasked() {
        StringBuilder output = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            output.append(line("warmup-" + i, i + 1, false,
                    "DETERMINISTIC_RESOLVED", "safe warmup"));
        }
        for (int i = 0; i < 12; i++) {
            boolean failed = i == 2 || i == 9;
            output.append(line(
                    failed ? "" : "bench-" + i,
                    i + 5, failed,
                    i == 2 ? "OVERLOAD_REJECTED"
                            : i == 9 ? "DEADLINE_EXCEEDED"
                                    : "DETERMINISTIC_RESOLVED",
                    failed ? "[SECURELOGX_REDACTED_PROCESSING_FAILURE]"
                            : i == 6 ? "service version=1.2.3.4"
                            : "masked protected result"
            ));
        }

        var validation = SecureLogXParallelOnnxBenchmark
                .validateProtectedOutput(output.toString(), 12);
        assertEquals(10, validation.successCount());
        assertEquals(2, validation.failClosedCount());
        assertEquals(1, validation.failClosedReasonCounts()
                .get("OVERLOAD_REJECTED").intValue());
        assertEquals(1, validation.failClosedReasonCounts()
                .get("DEADLINE_EXCEEDED").intValue());
        assertEquals(16, validation.sequenceAudit().records());
        assertEquals(1, validation.benignControlSuccessCount());
        assertTrue(validation.benignVersionPreserved());
    }

    @Test
    void rejectsDuplicateMissingAndLeakingProtectedRecords() {
        String valid = validAllSuccessOutput();
        assertThrows(IllegalStateException.class,
                () -> SecureLogXParallelOnnxBenchmark
                        .validateProtectedOutput(
                                valid + line("bench-0", 99, false,
                                        "DETERMINISTIC_RESOLVED", "masked"), 12));
        assertThrows(IllegalStateException.class,
                () -> SecureLogXParallelOnnxBenchmark
                        .validateProtectedOutput(
                                valid.replace("eventId=bench-1|",
                                        "eventId=bench-0|"), 12));
        assertThrows(IllegalStateException.class,
                () -> SecureLogXParallelOnnxBenchmark
                        .validateProtectedOutput(
                                valid.replace("eventId=bench-11|",
                                        "eventId=bench-12|"), 12));
        assertThrows(IllegalStateException.class,
                () -> SecureLogXParallelOnnxBenchmark
                        .validateProtectedOutput(
                                valid.replace("masked protected result",
                                        "123-45-6789"), 12));
    }

    @Test
    void rejectsFailClosedThatKeepsUpstreamEventId() {
        String ordinary = line(
                "bench-2", 7, false,
                "DETERMINISTIC_RESOLVED", "masked protected result"
        );
        String invalid = line(
                "bench-2", 7, true,
                "OVERLOAD_REJECTED",
                "[SECURELOGX_REDACTED_PROCESSING_FAILURE]"
        );
        String output = validAllSuccessOutput().replace(
                ordinary, invalid
        );
        assertThrows(IllegalStateException.class,
                () -> SecureLogXParallelOnnxBenchmark
                        .validateProtectedOutput(output, 12));
    }

    @Test
    void reportsMeasuredBoundedQueueDeltasSeparatelyFromWarmupPeaks() {
        MaskingRuntimeStats before = new MaskingRuntimeStats(
                4, 4, 0, 0, 0, 0, 0, 0, 1, 0, 64, 2000
        );
        MaskingRuntimeStats after = new MaskingRuntimeStats(
                15, 12, 2, 1, 1, 0, 0, 0, 1, 8, 64, 2000
        );
        JSONObject metrics = SecureLogXParallelOnnxBenchmark
                .executorStatsJson(before, after);
        assertEquals(11L, metrics.getLong("accepted_requests"));
        assertEquals(8L, metrics.getLong("completed_requests"));
        assertEquals(2L, metrics.getLong("overload_rejected_requests"));
        assertEquals(1L, metrics.getLong("deadline_exceeded_requests"));
        assertEquals(8, metrics.getInt(
                "peak_queued_requests_including_warmup"));
    }

    private static String validAllSuccessOutput() {
        StringBuilder lines = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            lines.append(line("warmup-" + i, i + 1, false,
                    "DETERMINISTIC_RESOLVED", "safe warmup"));
        }
        for (int i = 0; i < 12; i++) {
            lines.append(line("bench-" + i, i + 5, false,
                    "DETERMINISTIC_RESOLVED",
                    i == 6 ? "service version=1.2.3.4"
                            : "masked protected result"));
        }
        return lines.toString();
    }

    private static String line(
            String id, long seq, boolean failed, String reason, String msg
    ) {
        return "eventId=" + id + "|level=INFO"
                + "|instanceId=" + INSTANCE
                + "|seq=" + seq
                + "|ingressId=" + INSTANCE + "-" + seq
                + "|ml=false|failClosed=" + failed
                + "|reason=" + reason
                + "|customer=|stack=|msg=" + msg + "|throwable=\n";
    }
}
