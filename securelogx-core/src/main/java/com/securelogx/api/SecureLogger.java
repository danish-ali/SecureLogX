package com.securelogx.api;

import com.securelogx.engine.SecureLogX;
import com.securelogx.model.LogLevel;

/**
 * Legacy SecureLogX logging facade.
 *
 * @deprecated Production integrations should use {@link SecureMasker} or a
 * framework adapter such as securelogx-log4j2. This class is retained only for
 * compatibility and research/runtime harnesses.
 */
@Deprecated(forRemoval = false)
public class SecureLogger {

    private static volatile SecureLogX logxInstance;

    private static SecureLogX getEngineInstance() {
        if (logxInstance == null) {
            synchronized (SecureLogger.class) {
                if (logxInstance == null) {
                    try {
                        logxInstance = new SecureLogX();
                    } catch (Exception e) {
                        System.err.println(
                                "[SecureLogger] SecureLogX initialization "
                                        + "failed closed: "
                                        + e.getClass().getSimpleName()
                        );
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
            System.err.println(
                    "[SecureLogger] Log suppressed because SecureLogX "
                            + "is unavailable (fail-closed)."
            );
        }
    }

    /**
     * Appender/framework ingress preserving source severity, trace ID, and
     * original event timestamp. SecureLogX assigns the process-wide sequence.
     */
    public static void log(
            LogLevel level,
            String message,
            boolean showLastFour,
            String traceId,
            long eventTimestamp
    ) {
        SecureLogX engine = getEngineInstance();
        if (engine != null) {
            engine.process(
                    message,
                    level,
                    showLastFour,
                    traceId,
                    eventTimestamp
            );
        } else {
            System.err.println(
                    "[SecureLogger] Log suppressed because SecureLogX "
                            + "is unavailable (fail-closed)."
            );
        }
    }

    public static SecureLogX getEngine() {
        return getEngineInstance();
    }

    public static void shutdownExecutor() {
        SecureLogX engine = getEngineInstance();
        if (engine != null) {
            try { engine.shutdownExecutor();
            System.out.println("shutdown executor completed");
            }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    public static void shutdownAppender() {


        SecureLogX engine = getEngineInstance();
        if (engine != null) {
            try { engine.shutdownAppender();
                System.out.println("shutdown appender completed");
            }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

}
