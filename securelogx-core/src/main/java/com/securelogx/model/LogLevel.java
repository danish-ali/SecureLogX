package com.securelogx.model;

/**
 * Source logging severity.
 *
 * SECURE is retained for compatibility with the original SecureLogX API.
 * Protection is no longer gated by this value; appender-routed events keep
 * their original TRACE/DEBUG/INFO/WARN/ERROR severity and are still inspected.
 */
public enum LogLevel {
    TRACE,
    DEBUG,
    INFO,

    /**
     * Legacy explicit SecureLogX level. Prefer preserving the source severity
     * and using an explicit protection marker/policy hint in appender-based
     * integrations.
     */
    @Deprecated(forRemoval = false)
    SECURE,

    WARN,
    ERROR
}
