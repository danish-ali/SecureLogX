package com.securelogx.detection;

/**
 * Payload-free diagnostic counters from the single native ONNX worker.
 * Attempted counts occur before Session.run, whereas completed counts are
 * captured when Session.run returns successfully, before the next checkpoint.
 * Window distributions count ML-routed fields after tokenization, not events.
 */
public record NativeInferenceDiagnostics(
        long workerDeterministicScans,
        long workerDeterministicScanNanos,
        long nativeRunAttempted,
        long nativeRunCompleted,
        long nativeRunFailedOrCanceled,
        long nativeWindowsAttempted,
        long singleWindowFields,
        long twoToFourWindowFields,
        long fivePlusWindowFields,
        long maxWindowsPerFieldObserved,
        long workerDeterministicScansReused
) {
    /** Backward-compatible constructor for legacy diagnostic snapshots. */
    public NativeInferenceDiagnostics(
            long workerDeterministicScans,
            long workerDeterministicScanNanos,
            long nativeRunAttempted,
            long nativeRunCompleted,
            long nativeRunFailedOrCanceled,
            long nativeWindowsAttempted,
            long singleWindowFields,
            long twoToFourWindowFields,
            long fivePlusWindowFields,
            long maxWindowsPerFieldObserved
    ) {
        this(workerDeterministicScans, workerDeterministicScanNanos,
                nativeRunAttempted, nativeRunCompleted,
                nativeRunFailedOrCanceled, nativeWindowsAttempted,
                singleWindowFields, twoToFourWindowFields,
                fivePlusWindowFields, maxWindowsPerFieldObserved, 0L);
    }

    public NativeInferenceDiagnostics minus(NativeInferenceDiagnostics before) {
        return new NativeInferenceDiagnostics(
                workerDeterministicScans - before.workerDeterministicScans,
                workerDeterministicScanNanos - before.workerDeterministicScanNanos,
                nativeRunAttempted - before.nativeRunAttempted,
                nativeRunCompleted - before.nativeRunCompleted,
                nativeRunFailedOrCanceled - before.nativeRunFailedOrCanceled,
                nativeWindowsAttempted - before.nativeWindowsAttempted,
                singleWindowFields - before.singleWindowFields,
                twoToFourWindowFields - before.twoToFourWindowFields,
                fivePlusWindowFields - before.fivePlusWindowFields,
                maxWindowsPerFieldObserved,
                workerDeterministicScansReused
                        - before.workerDeterministicScansReused
        );
    }
}
