package com.securelogx.api;

/**
 * Stable public reason codes for SecureLogX masking outcomes.
 */
public enum MaskReasonCode {
    DETERMINISTIC_RESOLVED,
    ML_RESOLVED,
    PROCESSING_FAILURE,
    MASKING_DISABLED,

    /**
     * The bounded masking executor rejected admission because its queue was
     * full or the runtime was shutting down. Raw payload is never forwarded.
     */
    OVERLOAD_REJECTED,

    /**
     * The end-to-end masking deadline expired. Raw payload is never forwarded.
     */
    DEADLINE_EXCEEDED
}
