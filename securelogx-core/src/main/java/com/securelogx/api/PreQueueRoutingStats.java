package com.securelogx.api;

/**
 * Monotonic, payload-free pre-queue counters belonging to one masker.
 * Field candidates are counted before whole-event merge; they are not
 * successful emitted fields and do not imply that the queue was avoided.
 */
public record PreQueueRoutingStats(
        boolean enabled,
        long deterministicFieldsResolved,
        long escalatedFields,
        long wholeEventFailClosed,
        long completeEventsBypassedWorker,
        long mixedEventsSubmittedToWorker,
        long allEscalatedEventsSubmittedToWorker,
        long callerRoutingNanos
) {
    /** Backwards-compatible constructor used by existing consumers. */
    public PreQueueRoutingStats(
            boolean enabled,
            long deterministicFieldsResolved,
            long escalatedFields,
            long wholeEventFailClosed
    ) {
        this(enabled, deterministicFieldsResolved, escalatedFields,
                wholeEventFailClosed, 0L, 0L, 0L, 0L);
    }
}
