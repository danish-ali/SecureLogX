package com.securelogx.api;

/**
 * Framework-neutral masking outcome.
 *
 * The masked text is always safe to forward according to the configured
 * SecureLogX policy. A fail-closed result never contains the original text.
 */
public record MaskedResult(
        String maskedText,
        boolean mlInvoked,
        boolean failClosed,
        MaskReasonCode reasonCode,
        long sequence,
        String instanceId
) {
    public MaskedResult {
        if (maskedText == null) {
            throw new IllegalArgumentException("maskedText must not be null");
        }
        if (reasonCode == null) {
            throw new IllegalArgumentException("reasonCode must not be null");
        }
        if (instanceId == null || instanceId.isBlank()) {
            throw new IllegalArgumentException("instanceId must not be blank");
        }
    }
}
