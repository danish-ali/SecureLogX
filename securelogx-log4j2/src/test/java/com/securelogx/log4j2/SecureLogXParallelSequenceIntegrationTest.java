package com.securelogx.log4j2;

import com.securelogx.api.MaskedResult;
import com.securelogx.api.MaskReasonCode;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.ThreadContext;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.FileAppender;
import org.apache.logging.log4j.core.appender.rewrite.RewriteAppender;
import org.apache.logging.log4j.core.config.AppenderRef;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.DefaultConfiguration;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.apache.logging.log4j.message.SimpleMessage;
import org.apache.logging.log4j.util.SortedArrayStringMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Model-free concurrency checks. The protected FileAppender receives only
 * events already sanitized by SecureLogXRewritePolicy. Native ONNX remains
 * bound to the production single-worker masking executor in real deployments.
 */
class SecureLogXParallelSequenceIntegrationTest {

    private static final String RAW = "123-45-6789";

    @TempDir
    Path tempDir;

    @Test
    void reconstructsIngressOrderWhenProtectedFileWritesCompleteInReverse()
            throws Exception {
        CountDownLatch firstInMasker = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);

        SecureMaskingService service = texts -> {
            if (texts.stream().anyMatch(text ->
                    text.contains("first-event"))) {
                firstInMasker.countDown();
                try {
                    if (!releaseFirst.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException(
                                "Timed out awaiting delayed first event"
                        );
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
            return maskAllForTest(texts);
        };

        SecureLogXRewritePolicy policy = new SecureLogXRewritePolicy(
                "test", "SecureLogXRewrite",
                false, false, null, service
        );

        DefaultConfiguration configuration = new DefaultConfiguration();
        Path output = tempDir.resolve("parallel-protected.log");
        PatternLayout layout = PatternLayout.newBuilder()
                .setConfiguration(configuration)
                .setPattern(
                        "eventId=%X{eventId}|level=%p"
                                + "|instanceId=%X{securelogx.instanceId}"
                                + "|seq=%X{securelogx.seq}"
                                + "|ingressId=%X{securelogx.ingressId}"
                                + "|ml=%X{securelogx.mlInvoked}"
                                + "|failClosed=%X{securelogx.failClosed}"
                                + "|reason=%X{securelogx.reason}"
                                + "|customer=|stack=|msg=%m|throwable=%n"
                )
                .build();
        FileAppender file = FileAppender.newBuilder()
                .setName("ParallelProtectedFile")
                .setConfiguration(configuration)
                .setFileName(output.toString())
                .setAppend(false)
                .setImmediateFlush(true)
                .setLayout(layout)
                .build();
        assertNotNull(file);

        ExecutorService callers = Executors.newFixedThreadPool(2);
        file.start();
        try {
            Future<LogEvent> first = callers.submit(() ->
                    policy.rewrite(source(
                            "bench-0", "first-event ssn=" + RAW
                    ))
            );
            assertTrue(firstInMasker.await(5, TimeUnit.SECONDS));

            // This event obtains the larger sequence but completes first.
            Future<LogEvent> second = callers.submit(() ->
                    policy.rewrite(source(
                            "bench-1", "second-event ssn=" + RAW
                    ))
            );
            LogEvent secondResult = second.get(5, TimeUnit.SECONDS);
            file.append(secondResult);

            releaseFirst.countDown();
            LogEvent firstResult = first.get(5, TimeUnit.SECONDS);
            file.append(firstResult);

            long firstSequence = sequence(firstResult);
            long secondSequence = sequence(secondResult);
            assertTrue(firstSequence < secondSequence);
            assertEquals(
                    firstResult.getContextData()
                            .getValue("securelogx.instanceId"),
                    secondResult.getContextData()
                            .getValue("securelogx.instanceId")
            );

            String protectedFile = Files.readString(
                    output, StandardCharsets.UTF_8
            );
            assertFalse(protectedFile.contains(RAW));
            assertFalse(protectedFile.contains("attacker-instance"));
            SecureLogXSequenceAudit.Audit audit =
                    SecureLogXSequenceAudit.inspect(protectedFile);

            assertEquals(2, audit.records());
            assertEquals(1, audit.instances());
            assertEquals(1, audit.physicalOrderInversions());
            var reconstructed = audit.reconstructedByInstance()
                    .values().iterator().next();
            assertEquals(firstSequence,
                    reconstructed.get(0).sequence());
            assertEquals(secondSequence,
                    reconstructed.get(1).sequence());
            // After numeric reconstruction, the first-processed sequence
            // points back to physical line 1, not line 0.
            assertEquals(1, reconstructed.get(0).physicalLineIndex());
            assertEquals(0, reconstructed.get(1).physicalLineIndex());
        } finally {
            releaseFirst.countDown();
            callers.shutdownNow();
            file.stop();
        }
    }

    @Test
    void simultaneousRewriteCallsAlwaysAllocateUniqueProcessSequences()
            throws Exception {
        SecureMaskingService service =
                SecureLogXParallelSequenceIntegrationTest::maskAllForTest;
        SecureLogXRewritePolicy policy = new SecureLogXRewritePolicy(
                "test", "SecureLogXRewrite",
                false, false, null, service
        );

        int totalEvents = 64;
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService callers = Executors.newFixedThreadPool(8);
        List<Future<LogEvent>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < totalEvents; i++) {
                final int number = i;
                futures.add(callers.submit(() -> {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException(
                                "Concurrent callers did not start"
                        );
                    }
                    return policy.rewrite(source(
                            "bench-" + number,
                            "concurrent-event-" + number + " ssn=" + RAW
                    ));
                }));
            }
            release.countDown();

            Set<Long> sequences = new HashSet<>();
            Set<String> ingressIds = new HashSet<>();
            String instanceId = null;
            for (Future<LogEvent> future : futures) {
                LogEvent result = future.get(10, TimeUnit.SECONDS);
                long sequence = sequence(result);
                assertTrue(sequence > 0);
                assertTrue(sequences.add(sequence));
                assertEquals("false", result.getContextData()
                        .getValue("securelogx.failClosed"));
                assertFalse(result.getMessage()
                        .getFormattedMessage().contains(RAW));

                String instance = (String) result.getContextData()
                        .getValue("securelogx.instanceId");
                if (instanceId == null) {
                    instanceId = instance;
                }
                assertEquals(instanceId, instance);
                String ingressId = (String) result.getContextData()
                        .getValue("securelogx.ingressId");
                assertEquals(instance + "-" + sequence, ingressId);
                assertTrue(ingressIds.add(ingressId));
            }
            assertEquals(totalEvents, sequences.size());
            assertEquals(totalEvents, ingressIds.size());
        } finally {
            release.countDown();
            callers.shutdownNow();
        }
    }

    @Test
    void concurrentLog4jRewriteAppenderWritesKeepUniqueRecoverableIds()
            throws Exception {
        DefaultConfiguration configuration = new DefaultConfiguration();
        Path output = tempDir.resolve("concurrent-logger-protected.log");
        PatternLayout layout = PatternLayout.newBuilder()
                .setConfiguration(configuration)
                .setPattern(
                        "eventId=%X{eventId}|level=%p"
                                + "|instanceId=%X{securelogx.instanceId}"
                                + "|seq=%X{securelogx.seq}"
                                + "|ingressId=%X{securelogx.ingressId}"
                                + "|ml=%X{securelogx.mlInvoked}"
                                + "|failClosed=%X{securelogx.failClosed}"
                                + "|reason=%X{securelogx.reason}"
                                + "|customer=|stack=|msg=%m|throwable=%n"
                )
                .build();

        FileAppender file = FileAppender.newBuilder()
                .setName("ProtectedConcurrentFile")
                .setConfiguration(configuration)
                .setFileName(output.toString())
                .setAppend(false)
                .setImmediateFlush(true)
                .setLayout(layout)
                .build();
        assertNotNull(file);
        configuration.addAppender(file);

        SecureLogXRewritePolicy policy = new SecureLogXRewritePolicy(
                "test", "SecureLogXRewrite",
                true, false, configuration,
                SecureLogXParallelSequenceIntegrationTest::maskAllForTest
        );

        RewriteAppender rewrite = RewriteAppender.createAppender(
                "SecureLogXRewrite",
                "false",
                new AppenderRef[]{
                        AppenderRef.createAppenderRef(
                                "ProtectedConcurrentFile", null, null
                        )
                },
                configuration, policy, null
        );
        assertNotNull(rewrite);
        configuration.addAppender(rewrite);

        LoggerConfig root = configuration.getRootLogger();
        root.setLevel(Level.ALL);
        for (String name : List.copyOf(root.getAppenders().keySet())) {
            root.removeAppender(name);
        }
        root.addAppender(rewrite, Level.ALL, null);

        file.start();
        rewrite.start();
        int count = 64;
        ExecutorService callers = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<?>> pending = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                final int ordinal = i;
                pending.add(callers.submit(() -> {
                    try {
                        if (!start.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException(
                                    "Concurrent ingress start timed out"
                            );
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(interrupted);
                    }
                    root.log(source(
                            "bench-" + ordinal,
                            "parallel-log-" + ordinal + " ssn=" + RAW
                    ));
                }));
            }
            start.countDown();
            for (Future<?> future : pending) {
                future.get(10, TimeUnit.SECONDS);
            }
            String protectedOutput = Files.readString(
                    output, StandardCharsets.UTF_8
            );

            assertFalse(protectedOutput.contains(RAW));
            assertFalse(protectedOutput.contains("attacker-instance"));
            assertFalse(protectedOutput.contains("|failClosed=true|"));
            SecureLogXSequenceAudit.Audit audit =
                    SecureLogXSequenceAudit.inspect(protectedOutput);
            assertEquals(count, audit.records());
            assertEquals(1, audit.instances());
            // Ensure the output contains exactly one protected log event
            // for every synthetic caller, not merely N distinct sequence
            // numbers associated with possibly duplicated input IDs.
            List<String> emitted = protectedOutput.lines()
                    .filter(line -> line.startsWith("eventId="))
                    .toList();
            for (int i = 0; i < count; i++) {
                String expectedPrefix = "eventId=bench-" + i + "|";
                long occurrences = emitted.stream()
                        .filter(line -> line.startsWith(expectedPrefix))
                        .count();
                assertEquals(1L, occurrences);
            }
            assertEquals(count,
                    audit.reconstructedByInstance()
                            .values().iterator().next().size());
            assertEquals(count, new HashSet<>(
                    audit.reconstructedByInstance()
                            .values().iterator().next().stream()
                            .map(SecureLogXSequenceAudit.Stamp::ingressId)
                            .toList()
            ).size());
        } finally {
            start.countDown();
            callers.shutdownNow();
            rewrite.stop();
            file.stop();
        }
    }

    private static LogEvent source(String eventId, String message) {
        SortedArrayStringMap context = new SortedArrayStringMap();
        context.putValue("eventId", eventId);
        // Test forged upstream metadata: rewrite must overwrite it.
        context.putValue("securelogx.seq", "999999999");
        context.putValue("securelogx.instanceId", "attacker-instance");
        context.putValue("securelogx.ingressId", "attacker-instance-999999999");
        context.freeze();
        return new Log4jLogEvent.Builder()
                .setLoggerName("parallel-test")
                .setLevel(Level.INFO)
                .setMessage(new SimpleMessage(message))
                .setContextData(context)
                .setContextStack(ThreadContext.EMPTY_STACK)
                .build();
    }

    private static long sequence(LogEvent result) {
        return Long.parseLong(
                (String) result.getContextData()
                        .getValue("securelogx.seq")
        );
    }

    private static List<MaskedResult> maskAllForTest(
            List<String> texts
    ) {
        List<MaskedResult> results = new ArrayList<>(texts.size());
        long fieldSequence = 0;
        for (String text : texts) {
            results.add(new MaskedResult(
                    text.replace(RAW, "[MASKED]"),
                    false, false,
                    MaskReasonCode.DETERMINISTIC_RESOLVED,
                    ++fieldSequence, "model-free-test-masker"
            ));
        }
        return List.copyOf(results);
    }
}
