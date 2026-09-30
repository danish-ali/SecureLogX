package com.securelogx.ner.impl;

public enum MlDecoderMode {
    ARGMAX_LEGACY,
    BIO_VITERBI_SAFETY_SUPPLEMENT;

    public static MlDecoderMode fromConfig(String raw) {
        if (raw == null || raw.isBlank()) {
            return ARGMAX_LEGACY;
        }

        String normalized = raw.trim()
                .replace('-', '_')
                .toUpperCase();

        return switch (normalized) {
            case "ARGMAX", "ARGMAX_LEGACY", "LEGACY" ->
                    ARGMAX_LEGACY;
            case "C1S",
                 "BIO_VITERBI_SAFETY_SUPPLEMENT",
                 "VITERBI_SAFETY_SUPPLEMENT" ->
                    BIO_VITERBI_SAFETY_SUPPLEMENT;
            default -> throw new IllegalArgumentException(
                    "Unsupported SecureLogX ML decoder mode: " + raw
            );
        };
    }
}
