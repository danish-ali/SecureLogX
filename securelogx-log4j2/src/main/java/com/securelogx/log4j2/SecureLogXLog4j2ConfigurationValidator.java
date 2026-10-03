package com.securelogx.log4j2;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.rewrite.RewriteAppender;
import org.apache.logging.log4j.core.async.AsyncLoggerConfig;
import org.apache.logging.log4j.core.async.AsyncLoggerContextSelector;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Detects Log4j2 configurations that can bypass SecureLogX protection.
 */
public final class SecureLogXLog4j2ConfigurationValidator {

    private SecureLogXLog4j2ConfigurationValidator() {
    }

    public static List<String> findViolations(
            Configuration configuration,
            String expectedRewriteAppenderName,
            boolean requirePreQueueSanitization
    ) {
        List<String> violations = new ArrayList<>();

        if (configuration == null) {
            violations.add("Log4j2 Configuration is unavailable");
            return violations;
        }

        Appender expected =
                configuration.getAppender(expectedRewriteAppenderName);
        if (!(expected instanceof RewriteAppender)) {
            violations.add(
                    "Expected RewriteAppender '"
                            + expectedRewriteAppenderName
                            + "' is missing"
            );
        }

        validateRootLogger(
                configuration.getRootLogger(),
                expectedRewriteAppenderName,
                violations
        );

        for (Map.Entry<String, LoggerConfig> entry
                : configuration.getLoggers().entrySet()) {
            validateLoggerConfig(
                    entry.getKey(),
                    entry.getValue(),
                    expectedRewriteAppenderName,
                    violations
            );
        }

        if (requirePreQueueSanitization) {
            String selector = System.getProperty(
                    "log4j2.contextSelector",
                    System.getProperty("Log4jContextSelector", "")
            );
            boolean asyncSelectorConfigured =
                    AsyncLoggerContextSelector.isSelected()
                            || selector.contains(
                                    "AsyncLoggerContextSelector"
                            )
                            || selector.contains(
                                    "BasicAsyncLoggerContextSelector"
                            );

            if (asyncSelectorConfigured) {
                violations.add(
                        "AsyncLogger context selector is enabled; raw events "
                                + "can enter the Disruptor before rewrite"
                );
            }

            if (configuration.getRootLogger()
                    instanceof AsyncLoggerConfig) {
                violations.add(
                        "AsyncRoot is configured upstream of SecureLogX rewrite"
                );
            }

            for (Map.Entry<String, LoggerConfig> entry
                    : configuration.getLoggers().entrySet()) {
                if (entry.getValue() instanceof AsyncLoggerConfig) {
                    violations.add(
                            "AsyncLogger '"
                                    + entry.getKey()
                                    + "' is configured upstream of rewrite"
                    );
                }
            }
        }

        return List.copyOf(violations);
    }

    /**
     * Startup/bootstrap validation for the fully initialized Log4j2 context.
     *
     * Call this after Log4j2 configuration is loaded but before the
     * application reports readiness or begins accepting traffic.
     */
    public static void validateCurrentContextOrThrow(
            String expectedRewriteAppenderName,
            boolean requirePreQueueSanitization
    ) {
        Object context = LogManager.getContext(false);
        if (!(context instanceof LoggerContext loggerContext)) {
            throw new IllegalStateException(
                    "SecureLogX requires a Log4j2 LoggerContext for "
                            + "startup topology validation"
            );
        }

        validateOrThrow(
                loggerContext.getConfiguration(),
                expectedRewriteAppenderName,
                requirePreQueueSanitization
        );
    }

    public static void validateOrThrow(
            Configuration configuration,
            String expectedRewriteAppenderName,
            boolean requirePreQueueSanitization
    ) {
        List<String> violations = findViolations(
                configuration,
                expectedRewriteAppenderName,
                requirePreQueueSanitization
        );

        if (!violations.isEmpty()) {
            throw new IllegalStateException(
                    "Unsafe SecureLogX Log4j2 configuration: "
                            + String.join("; ", violations)
            );
        }
    }

    private static void validateRootLogger(
            LoggerConfig rootLogger,
            String expectedRewriteAppenderName,
            List<String> violations
    ) {
        if (rootLogger == null) {
            violations.add("Root logger configuration is missing");
            return;
        }

        Map<String, Appender> appenders = rootLogger.getAppenders();
        Appender secureAppender =
                appenders.get(expectedRewriteAppenderName);

        if (secureAppender == null) {
            violations.add(
                    "Root logger does not reference SecureLogX RewriteAppender '"
                            + expectedRewriteAppenderName
                            + "'"
            );
        } else if (!(secureAppender instanceof RewriteAppender)) {
            violations.add(
                    "Root logger appender '"
                            + expectedRewriteAppenderName
                            + "' is not a RewriteAppender"
            );
        }

        validateLoggerConfig(
                "root",
                rootLogger,
                expectedRewriteAppenderName,
                violations
        );
    }

    private static void validateLoggerConfig(
            String loggerName,
            LoggerConfig loggerConfig,
            String expectedRewriteAppenderName,
            List<String> violations
    ) {
        if (loggerConfig == null) {
            return;
        }

        Map<String, Appender> appenders =
                loggerConfig.getAppenders();

        for (Map.Entry<String, Appender> entry : appenders.entrySet()) {
            if (!expectedRewriteAppenderName.equals(entry.getKey())) {
                violations.add(
                        "Logger '"
                                + loggerName
                                + "' directly references appender '"
                                + entry.getKey()
                                + "', bypassing SecureLogX rewrite"
                );
                continue;
            }

            if (!(entry.getValue() instanceof RewriteAppender)) {
                violations.add(
                        "Logger '"
                                + loggerName
                                + "' appender '"
                                + entry.getKey()
                                + "' is not a RewriteAppender"
                );
            }
        }
    }
}
