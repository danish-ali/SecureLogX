package com.securelogx.log4j2;

import com.securelogx.api.MaskingQueueWaitStats;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SecureLogXSteadyArrivalOnnxBenchmarkTest {

    @Test
    void schedulesRateAsMonotonicAbsoluteIntervalsWithoutDrift() {
        long origin = 4_000_000_000L;
        assertEquals(origin,
                SecureLogXSteadyArrivalOnnxBenchmark.scheduledAtNanos(
                        origin, 0, 7));
        assertEquals(origin + 142_857_142L,
                SecureLogXSteadyArrivalOnnxBenchmark.scheduledAtNanos(
                        origin, 1, 7));
        assertEquals(origin + 1_000_000_000L,
                SecureLogXSteadyArrivalOnnxBenchmark.scheduledAtNanos(
                        origin, 7, 7));
        assertEquals(origin + 60_000_000_000L,
                SecureLogXSteadyArrivalOnnxBenchmark.scheduledAtNanos(
                        origin, 420, 7));
        assertThrows(IllegalArgumentException.class,
                () -> SecureLogXSteadyArrivalOnnxBenchmark
                        .scheduledAtNanos(origin, -1, 7));
        assertThrows(IllegalArgumentException.class,
                () -> SecureLogXSteadyArrivalOnnxBenchmark
                        .scheduledAtNanos(origin, 1, 0));
    }

    @Test
    void rejectsLateLoadGenerationEvenIfMaskingMightFinish() {
        assertTrue(SecureLogXSteadyArrivalOnnxBenchmark
                .meetsRateFidelity(5, 4.9, 50.0));
        assertFalse(SecureLogXSteadyArrivalOnnxBenchmark
                .meetsRateFidelity(5, 4.7, 50.0));
        assertFalse(SecureLogXSteadyArrivalOnnxBenchmark
                .meetsRateFidelity(5, 5.1, 205.0));
        assertFalse(SecureLogXSteadyArrivalOnnxBenchmark
                .meetsRateFidelity(0, 1, 0));
    }

    @Test
    void queueWaitHistogramIsMeasuredOnlyAndExcludesWarmupDeltas() {
        MaskingQueueWaitStats before = new MaskingQueueWaitStats(
                4, 10_000_000L, 5_000_000L,
                List.of(1L, 5L, 10L), List.of(0L, 2L, 2L, 0L)
        );
        MaskingQueueWaitStats after = new MaskingQueueWaitStats(
                7, 31_000_000L, 12_000_000L,
                List.of(1L, 5L, 10L), List.of(1L, 3L, 2L, 1L)
        );
        JSONObject d = SecureLogXSteadyArrivalOnnxBenchmark
                .queueWaitDelta(before, after);
        assertEquals(3L, d.getLong("worker_started_requests"));
        assertEquals(21_000_000L, d.getLong("total_wait_nanos"));
        assertEquals(7.0, d.getDouble("average_millis"), 0.001);
        assertEquals(12.0,
                d.getDouble("max_millis_including_warmup"), 0.001);
        assertEquals(4,
                d.getJSONArray("mutually_exclusive_bucket_counts").length());
        assertEquals(1L,
                d.getJSONArray("mutually_exclusive_bucket_counts")
                        .getLong(0));
        assertEquals(0L,
                d.getJSONArray("mutually_exclusive_bucket_counts")
                        .getLong(2));
    }

    @Test
    void queueWaitHistogramRejectsDifferingBucketDefinitions() {
        MaskingQueueWaitStats a = new MaskingQueueWaitStats(
                0, 0, 0, List.of(1L), List.of(0L, 0L)
        );
        MaskingQueueWaitStats b = new MaskingQueueWaitStats(
                0, 0, 0, List.of(5L), List.of(0L, 0L)
        );
        assertThrows(IllegalArgumentException.class,
                () -> SecureLogXSteadyArrivalOnnxBenchmark
                        .queueWaitDelta(a, b));
        assertThrows(IllegalArgumentException.class,
                () -> new MaskingQueueWaitStats(
                        1, 1, 1, List.of(1L), List.of(1L)
                ));
    }
}
