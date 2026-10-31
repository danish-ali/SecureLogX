package com.securelogx.api;

/**
 * Monotonic, payload-free observations of complete masking calls.
 * A call is usually one Log4j event; counting fields alone does not
 * establish that an event has bypassed the worker.
 */
public record PreQueuePhaseOneStats(
        long observedCalls,
        long fullyDeterministicCalls,
        long mixedCalls,
        long fullyEscalatedCalls,
        long callerRoutingNanos
) {
    public PreQueuePhaseOneStats minus(PreQueuePhaseOneStats before) {
        return new PreQueuePhaseOneStats(
                observedCalls - before.observedCalls,
                fullyDeterministicCalls - before.fullyDeterministicCalls,
                mixedCalls - before.mixedCalls,
                fullyEscalatedCalls - before.fullyEscalatedCalls,
                callerRoutingNanos - before.callerRoutingNanos
        );
    }
}
