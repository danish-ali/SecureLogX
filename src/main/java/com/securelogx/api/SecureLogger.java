package com.securelogx.api;

import com.securelogx.engine.SecureLogX;
import com.securelogx.model.LogLevel;

/**
 * Public logging API for external use.
 * Includes trace-aware logging and secure masking options.
 */
public class SecureLogger {

    private static volatile SecureLogX logxInstance;

    private static SecureLogX getEngineInstance() {
        if (logxInstance == null) {
            synchronized (SecureLogger.class) {
                if (logxInstance == null) {
                    try {
                        logxInstance = new SecureLogX();
                    } catch (Exception e) {
                        System.err.println("[SecureLogger] Failed to initialize SecureLogX: " + e.getMessage());
                        e.printStackTrace();
                        return null;
                    }
                }
            }
        }
        return logxInstance;
    }

    /** Initializes a new trace context with the given traceId */
    public static void initRequest(String traceId) {
        SecureLogX engine = getEngineInstance();
        if (engine != null) {
            engine.initRequest(traceId);
        }
    }

    public static void log(LogLevel level, String message) {
        log(level, message, false);
    }

    public static void log(LogLevel level, String message, boolean showLastFour) {
        SecureLogX engine = getEngineInstance();
        if (engine != null) {
            engine.process(message, level, showLastFour);
        } else {
            System.err.println("[SecureLogger] Skipping log (engine not initialized): " + message);
        }
    }

    public static SecureLogX getEngine() {
        return getEngineInstance();
    }
}
