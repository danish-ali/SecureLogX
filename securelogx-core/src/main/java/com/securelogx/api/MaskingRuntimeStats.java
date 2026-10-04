package com.securelogx.api;

/**
 * Request-level operational metrics for the bounded masking runtime.
 *
 * Counters are monotonic for the lifetime of a SecureMasker instance.
 */
public record MaskingRuntimeStats(
        long acceptedRequests,
        long completedRequests,
        long overloadRejectedRequests,
        long deadlineExceededRequests,
        long nativeTerminationSignals,
        long executionFailureRequests,
        int activeRequests,
        int queuedRequests,
        int peakActiveRequests,
        int peakQueuedRequests,
        int queueCapacity,
        long deadlineMillis
) {
    public long failClosedRequests() {
        return overloadRejectedRequests
                + deadlineExceededRequests
                + executionFailureRequests;
    }
}
