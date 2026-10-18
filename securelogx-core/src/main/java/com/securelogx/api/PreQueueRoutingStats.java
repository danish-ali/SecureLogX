package com.securelogx.api;

/**
 * Non-sensitive, masker-local counts for the opt-in pre-queue experiment.
 * No raw field values, decision spans or organization policy data.
 */
public record PreQueueRoutingStats(
        boolean enabled,
        long deterministicFieldsResolved,
        long escalatedFields,
        long wholeEventFailClosed
) {
}
