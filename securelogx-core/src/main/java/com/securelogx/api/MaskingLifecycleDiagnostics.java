package com.securelogx.api;

/**
 * Monotonic, payload-free lifecycle counters for one bounded masking worker.
 * A deadline removal counts as before-start only when removal from the queue
 * succeeds; racing worker claims are intentionally not guessed.
 */
public record MaskingLifecycleDiagnostics(
        long workerStarted,
        long workerFinished,
        long deadlineRemovedBeforeStart,
        long interruptedRemovedBeforeStart
) {
    public MaskingLifecycleDiagnostics minus(MaskingLifecycleDiagnostics before) {
        return new MaskingLifecycleDiagnostics(
                workerStarted - before.workerStarted,
                workerFinished - before.workerFinished,
                deadlineRemovedBeforeStart - before.deadlineRemovedBeforeStart,
                interruptedRemovedBeforeStart - before.interruptedRemovedBeforeStart
        );
    }
}
