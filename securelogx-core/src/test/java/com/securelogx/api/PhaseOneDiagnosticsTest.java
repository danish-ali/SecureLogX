package com.securelogx.api;

import com.securelogx.detection.NativeInferenceDiagnostics;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PhaseOneDiagnosticsTest {

    @Test
    void legacyPreQueueSnapshotConstructorDefaultsNewCountersToZero() {
        PreQueueRoutingStats stats =
                new PreQueueRoutingStats(false, 0, 0, 0);
        assertFalse(stats.enabled());
        assertEquals(0, stats.completeEventsBypassedWorker());
        assertEquals(0, stats.mixedEventsSubmittedToWorker());
        assertEquals(0, stats.allEscalatedEventsSubmittedToWorker());
        assertEquals(0, stats.callerRoutingNanos());
    }

    @Test
    void workerAndNativeDeltasDoNotConflateAttemptsWithCompletions() {
        var first = new MaskingLifecycleDiagnostics(10, 9, 2, 1);
        var last = new MaskingLifecycleDiagnostics(20, 18, 4, 1);
        assertEquals(new MaskingLifecycleDiagnostics(10, 9, 2, 0),
                last.minus(first));

        var before = new NativeInferenceDiagnostics(
                5, 100, 4, 3, 1, 7, 2, 1, 1, 5);
        var after = new NativeInferenceDiagnostics(
                8, 180, 7, 5, 2, 12, 3, 3, 1, 6);
        var delta = after.minus(before);
        assertEquals(3, delta.nativeRunAttempted());
        assertEquals(2, delta.nativeRunCompleted());
        assertEquals(1, delta.nativeRunFailedOrCanceled());
        assertEquals(5, delta.nativeWindowsAttempted());
        assertEquals(3, delta.workerDeterministicScans());
        assertEquals(80, delta.workerDeterministicScanNanos());
        assertEquals(6, delta.maxWindowsPerFieldObserved(),
                "Maximum is a lifetime gauge including warmup, not a subtractable counter");
    }
}
