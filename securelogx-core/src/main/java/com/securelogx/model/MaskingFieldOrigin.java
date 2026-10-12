package com.securelogx.model;

/**
 * Trusted category assigned by the logging adapter, never by the log payload.
 * This is operational metadata only and must not change masking decisions.
 */
public enum MaskingFieldOrigin {
    MESSAGE,
    FORMAT,
    PARAMETER,
    MAP,
    MDC,
    STACK,
    THROWABLE,
    OTHER
}
