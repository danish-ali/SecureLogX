package com.securelogx.log4j2;

import com.securelogx.api.MaskedResult;
import com.securelogx.api.MaskReasonCode;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.ThreadContext;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.ConsoleAppender;
import org.apache.logging.log4j.core.appender.FileAppender;
import org.apache.logging.log4j.core.appender.rewrite.RewriteAppender;
import org.apache.logging.log4j.core.config.AppenderRef;
import org.apache.logging.log4j.core.config.DefaultConfiguration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.apache.logging.log4j.message.ParameterizedMessage;
import org.apache.logging.log4j.util.SortedArrayStringMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SecureLogXRewriteTopologyIntegrationTest {

    private static final String RAW = "123-45-6789";
    private static final String MASKED = "[MASKED]";
    private static final String REWRITE_NAME = "SecureLogXRewrite";

    @TempDir
    Path tempDir;

    @Test
    void rootRewriteMasksRealFileAndConsoleDestinations()
            throws Exception {
        PrintStream originalOut = System.out;
        ByteArrayOutputStream consoleBytes =
                new ByteArrayOutputStream();

        DefaultConfiguration configuration =
                new DefaultConfiguration();

        FileAppender fileAppender = null;
        ConsoleAppender consoleAppender = null;
        RewriteAppender rewriteAppender = null;

        try (PrintStream capturedOut = new PrintStream(
                consoleBytes,
                true,
                StandardCharsets.UTF_8
        )) {
            System.setOut(capturedOut);

            Path logFile = tempDir.resolve("protected.log");

            PatternLayout layout = PatternLayout.newBuilder()
                    .withConfiguration(configuration)
                    .withPattern(
                            "%level|%m|patient=%X{patient}|%throwable%n"
                    )
                    .build();

            fileAppender = FileAppender.newBuilder()
                    .setName("ProtectedFile")
                    .setConfiguration(configuration)
                    .setFileName(logFile.toString())
                    .setAppend(false)
                    .setImmediateFlush(true)
                    .setLayout(layout)
                    .build();

            consoleAppender = ConsoleAppender.newBuilder()
                    .setName("ProtectedConsole")
                    .setConfiguration(configuration)
                    .setTarget(ConsoleAppender.Target.SYSTEM_OUT)
                    .setFollow(true)
                    .setLayout(layout)
                    .build();

            assertNotNull(fileAppender);
            assertNotNull(consoleAppender);

            configuration.addAppender(fileAppender);
            configuration.addAppender(consoleAppender);

            fileAppender.start();
            consoleAppender.start();

            SecureMaskingService service =
                    maskingServiceReplacingRawValue();

            SecureLogXRewritePolicy policy =
                    new SecureLogXRewritePolicy(
                            "test",
                            REWRITE_NAME,
                            true,
                            false,
                            configuration,
                            service
                    );

            AppenderRef[] refs = new AppenderRef[]{
                    AppenderRef.createAppenderRef(
                            "ProtectedFile",
                            null,
                            null
                    ),
                    AppenderRef.createAppenderRef(
                            "ProtectedConsole",
                            null,
                            null
                    )
            };

            rewriteAppender = RewriteAppender.createAppender(
                    REWRITE_NAME,
                    "false",
                    refs,
                    configuration,
                    policy,
                    null
            );
            assertNotNull(rewriteAppender);

            configuration.addAppender(rewriteAppender);

            LoggerConfig root = configuration.getRootLogger();
            root.setLevel(Level.ALL);
            for (String existing :
                    List.copyOf(root.getAppenders().keySet())) {
                root.removeAppender(existing);
            }
            root.addAppender(
                    rewriteAppender,
                    Level.ALL,
                    null
            );

            rewriteAppender.start();

            SecureLogXLog4j2ConfigurationValidator
                    .validateOrThrow(
                            configuration,
                            REWRITE_NAME,
                            false
                    );

            SortedArrayStringMap context =
                    new SortedArrayStringMap();
            context.putValue("patient", RAW);
            context.freeze();

            LogEvent source = new Log4jLogEvent.Builder()
                    .setLoggerName("integration")
                    .setLevel(Level.INFO)
                    .setMessage(
                            new ParameterizedMessage(
                                    "patient ssn={}",
                                    RAW
                            )
                    )
                    .setContextData(context)
                    .setContextStack(ThreadContext.EMPTY_STACK)
                    .setThrown(
                            new IllegalStateException(
                                    "patient=" + RAW
                            )
                    )
                    .build();

            root.log(source);

            capturedOut.flush();

            String fileText = Files.readString(
                    logFile,
                    StandardCharsets.UTF_8
            );
            String consoleText = consoleBytes.toString(
                    StandardCharsets.UTF_8
            );

            assertProtectedOutput(fileText);
            assertProtectedOutput(consoleText);
        } finally {
            System.setOut(originalOut);

            if (rewriteAppender != null) {
                rewriteAppender.stop();
            }
            if (consoleAppender != null) {
                consoleAppender.stop();
            }
            if (fileAppender != null) {
                fileAppender.stop();
            }
        }
    }

    @Test
    void startupValidationRejectsSiblingDestinationBypass() {
        DefaultConfiguration configuration =
                new DefaultConfiguration();

        PatternLayout layout = PatternLayout.newBuilder()
                .withConfiguration(configuration)
                .withPattern("%m%n")
                .build();

        ConsoleAppender protectedConsole =
                ConsoleAppender.newBuilder()
                        .setName("ProtectedConsole")
                        .setConfiguration(configuration)
                        .setTarget(
                                ConsoleAppender.Target.SYSTEM_OUT
                        )
                        .setFollow(true)
                        .setLayout(layout)
                        .build();

        ConsoleAppender rawSibling =
                ConsoleAppender.newBuilder()
                        .setName("RawSiblingConsole")
                        .setConfiguration(configuration)
                        .setTarget(
                                ConsoleAppender.Target.SYSTEM_OUT
                        )
                        .setFollow(true)
                        .setLayout(layout)
                        .build();

        assertNotNull(protectedConsole);
        assertNotNull(rawSibling);

        configuration.addAppender(protectedConsole);
        configuration.addAppender(rawSibling);

        SecureLogXRewritePolicy policy =
                new SecureLogXRewritePolicy(
                        "test",
                        REWRITE_NAME,
                        false,
                        false,
                        configuration,
                        maskingServiceReplacingRawValue()
                );

        RewriteAppender rewriteAppender =
                RewriteAppender.createAppender(
                        REWRITE_NAME,
                        "false",
                        new AppenderRef[]{
                                AppenderRef.createAppenderRef(
                                        "ProtectedConsole",
                                        null,
                                        null
                                )
                        },
                        configuration,
                        policy,
                        null
                );

        assertNotNull(rewriteAppender);
        configuration.addAppender(rewriteAppender);

        LoggerConfig root = configuration.getRootLogger();
        for (String existing :
                List.copyOf(root.getAppenders().keySet())) {
            root.removeAppender(existing);
        }
        root.addAppender(rewriteAppender, Level.ALL, null);
        root.addAppender(rawSibling, Level.ALL, null);

        List<String> violations =
                SecureLogXLog4j2ConfigurationValidator
                        .findViolations(
                                configuration,
                                REWRITE_NAME,
                                false
                        );

        assertTrue(
                violations.stream().anyMatch(
                        item -> item.contains(
                                "RawSiblingConsole"
                        ) && item.contains(
                                "bypassing SecureLogX rewrite"
                        )
                )
        );

        assertThrows(
                IllegalStateException.class,
                () -> SecureLogXLog4j2ConfigurationValidator
                        .validateOrThrow(
                                configuration,
                                REWRITE_NAME,
                                false
                        )
        );
    }

    private static SecureMaskingService
            maskingServiceReplacingRawValue() {
        return texts -> {
            List<MaskedResult> results =
                    new ArrayList<>(texts.size());
            long sequence = 0;

            for (String text : texts) {
                results.add(
                        new MaskedResult(
                                text.replace(RAW, MASKED),
                                false,
                                false,
                                MaskReasonCode
                                        .DETERMINISTIC_RESOLVED,
                                ++sequence,
                                "topology-test"
                        )
                );
            }
            return List.copyOf(results);
        };
    }

    private static void assertProtectedOutput(String output) {
        assertFalse(
                output.contains(RAW),
                "Raw sensitive value reached destination: "
                        + output
        );
        assertTrue(
                output.contains(MASKED),
                "Masked value was not written: " + output
        );
        assertTrue(
                output.contains("INFO"),
                "Original severity was not preserved: "
                        + output
        );
    }
}
