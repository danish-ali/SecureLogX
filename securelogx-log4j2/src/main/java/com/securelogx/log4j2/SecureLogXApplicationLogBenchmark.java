package com.securelogx.log4j2;

import com.securelogx.validation.RuntimeMemorySnapshot;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.ThreadContext;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.FileAppender;
import org.apache.logging.log4j.core.appender.rewrite.RewriteAppender;
import org.apache.logging.log4j.core.config.AbstractConfiguration;
import org.apache.logging.log4j.core.config.AppenderRef;
import org.apache.logging.log4j.core.config.ConfigurationSource;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.apache.logging.log4j.message.ParameterizedMessage;
import org.apache.logging.log4j.message.SimpleMessage;
import org.apache.logging.log4j.message.StringMapMessage;
import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Representative real-application Log4j2 benchmark.
 *
 * Uses the application Logger API and the supported production topology:
 *
 * Logger -> LoggerConfig -> RewriteAppender -> SecureLogXRewritePolicy
 *        -> FileAppender
 *
 * This is an engineering characterization, not a JMH microbenchmark or SLA.
 */
public final class SecureLogXApplicationLogBenchmark {

    private static final String REWRITE_NAME = "SecureLogXRewrite";
    private static final int DEFAULT_EVENTS = 48;
    private static final int WARMUP_EVENTS = 4;

    private static final String SSN = "123-45-6789";
    private static final String EMAIL = "jane.doe@example.com";
    private static final String CARD = "4111111111111111";
    private static final String API_KEY =
            "sk_live_A1B2C3D4E5F6G7H8I9J0";
    private static final String JWT =
            "eyJhbGciOiJIUzI1NiJ9."
                    + "eyJzdWIiOiIxMjM0NTY3ODkwIn0."
                    + "SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c";
    private static final String ACCOUNT = "9876543210";
    private static final String PHONE = "7045551212";
    private static final String VERSION_CONTROL = "1.2.3.4";

    private static final List<String> RAW_SENSITIVE_VALUES = List.of(
            SSN,
            EMAIL,
            CARD,
            API_KEY,
            JWT,
            ACCOUNT,
            PHONE
    );

    private SecureLogXApplicationLogBenchmark() {
    }

    public static void main(String[] args) throws Exception {
        Path resultPath = args.length > 0
                ? Path.of(args[0])
                : Path.of(
                        "reports/application-log-benchmark/result.json"
                );
        int eventCount = args.length > 1
                ? Integer.parseInt(args[1])
                : DEFAULT_EVENTS;

        if (eventCount < 12) {
            throw new IllegalArgumentException(
                    "eventCount must be at least 12"
            );
        }

        Path reportDirectory = resultPath.getParent() == null
                ? Path.of(".")
                : resultPath.getParent();
        Files.createDirectories(reportDirectory);
        Path logFile = reportDirectory.resolve(
                "protected-application.log"
        );
        Files.deleteIfExists(logFile);

        SecureMaskerRegistry.closeAll();

        RuntimeMemorySnapshot processStart =
                settledSnapshot("process-start");

        LoggerContext context = new LoggerContext(
                "securelogx-application-benchmark"
        );
        BenchmarkConfiguration configuration =
                new BenchmarkConfiguration(context);

        configurePipeline(configuration, logFile);
        context.start(configuration);

        SecureLogXLog4j2ConfigurationValidator
                .validateOrThrow(
                        configuration,
                        REWRITE_NAME,
                        false
                );

        RuntimeMemorySnapshot afterInit =
                settledSnapshot("after-log4j-securelogx-init");

        Logger logger = context.getLogger(
                "com.securelogx.benchmark.Application"
        );

        try {
            for (int i = 0; i < WARMUP_EVENTS; i++) {
                emit(
                        logger,
                        "warmup-" + i,
                        i
                );
            }

            List<Long> latencies =
                    new ArrayList<>(eventCount);
            long start = System.nanoTime();

            for (int i = 0; i < eventCount; i++) {
                long eventStart = System.nanoTime();
                emit(
                        logger,
                        "bench-" + i,
                        i
                );
                latencies.add(
                        System.nanoTime() - eventStart
                );
            }

            long elapsedNanos =
                    System.nanoTime() - start;

            RuntimeMemorySnapshot afterWorkload =
                    settledSnapshot("after-application-workload");

            context.stop();

            RuntimeMemorySnapshot afterStop =
                    settledSnapshot("after-context-stop");

            if (SecureMaskerRegistry.ownedContextCount() != 0
                    || SecureMaskerRegistry.ownedMaskerCount() != 0) {
                throw new IllegalStateException(
                        "Application benchmark retained registry ownership"
                );
            }

            String output = Files.readString(
                    logFile,
                    StandardCharsets.UTF_8
            );

            Validation validation = validateOutput(
                    output,
                    eventCount
            );

            JSONObject result = new JSONObject();
            result.put(
                    "status",
                    "REPRESENTATIVE APPLICATION LOG BENCHMARK PASSED"
            );
            result.put("passed", true);
            result.put("events", eventCount);
            result.put("warmup_events", WARMUP_EVENTS);
            result.put(
                    "elapsed_millis",
                    TimeUnit.NANOSECONDS.toMillis(
                            elapsedNanos
                    )
            );
            result.put(
                    "events_per_second",
                    eventCount
                            / (elapsedNanos / 1_000_000_000.0)
            );
            result.put(
                    "caller_latency_millis",
                    latencyJson(latencies)
            );
            result.put(
                    "ml_invoked_events",
                    validation.mlInvokedEvents()
            );
            result.put(
                    "fail_closed_events",
                    validation.failClosedEvents()
            );
            result.put(
                    "measured_output_events",
                    validation.measuredOutputEvents()
            );
            result.put(
                    "severity_counts",
                    validation.severityCounts()
            );
            result.put(
                    "raw_payload_forwarded",
                    false
            );
            result.put(
                    "benign_version_preserved",
                    validation.benignVersionPreserved()
            );
            result.put(
                    "output_file_bytes",
                    Files.size(logFile)
            );
            result.put(
                    "process_start",
                    processStart.toJson()
            );
            result.put(
                    "after_init",
                    afterInit.toJson()
            );
            result.put(
                    "after_workload",
                    afterWorkload.toJson()
            );
            result.put(
                    "after_context_stop",
                    afterStop.toJson()
            );
            result.put(
                    "workload_memory_delta",
                    memoryDelta(
                            afterInit,
                            afterWorkload
                    )
            );
            result.put(
                    "post_stop_memory_delta",
                    memoryDelta(
                            processStart,
                            afterStop
                    )
            );
            result.put(
                    "final_owned_contexts",
                    SecureMaskerRegistry.ownedContextCount()
            );
            result.put(
                    "final_owned_maskers",
                    SecureMaskerRegistry.ownedMaskerCount()
            );
            result.put(
                    "workload_categories",
                    new JSONArray(List.of(
                            "parameterized-message",
                            "contextual-ml-text",
                            "map-message",
                            "mdc-value",
                            "throwable-message",
                            "long-windowed-record",
                            "non-sensitive-control",
                            "api-key-jwt",
                            "credit-card",
                            "phone",
                            "severity-mix",
                            "thread-context-stack"
                    ))
            );
            result.put(
                    "sealed_challenge_inference",
                    false
            );

            Files.writeString(
                    resultPath,
                    result.toString(2)
                            + System.lineSeparator()
            );

            printSummary(result, logFile, resultPath);
        } finally {
            ThreadContext.clearAll();
            if (!context.isStopped()) {
                context.stop();
            }
        }
    }

    private static void configurePipeline(
            BenchmarkConfiguration configuration,
            Path logFile
    ) {
        PatternLayout layout = PatternLayout.newBuilder()
                .setConfiguration(configuration)
                .setPattern(
                        "eventId=%X{eventId}"
                                + "|level=%p"
                                + "|ml=%X{securelogx.mlInvoked}"
                                + "|failClosed=%X{securelogx.failClosed}"
                                + "|reason=%X{securelogx.reason}"
                                + "|customer=%X{customer}"
                                + "|stack=%x"
                                + "|msg=%m"
                                + "|throwable=%throwable{short.message}%n"
                )
                .build();

        FileAppender fileAppender =
                FileAppender.newBuilder()
                        .setName("ProtectedFile")
                        .setConfiguration(configuration)
                        .setFileName(logFile.toString())
                        .setAppend(false)
                        .setImmediateFlush(true)
                        .setLayout(layout)
                        .build();

        if (fileAppender == null) {
            throw new IllegalStateException(
                    "Failed to create benchmark FileAppender"
            );
        }

        configuration.addAppender(fileAppender);

        SecureLogXRewritePolicy policy =
                SecureLogXRewritePolicy.createPolicy(
                        "dev",
                        REWRITE_NAME,
                        "true",
                        "false",
                        configuration
                );

        RewriteAppender rewriteAppender =
                RewriteAppender.createAppender(
                        REWRITE_NAME,
                        "false",
                        new AppenderRef[]{
                                AppenderRef.createAppenderRef(
                                        "ProtectedFile",
                                        null,
                                        null
                                )
                        },
                        configuration,
                        policy,
                        null
                );

        if (rewriteAppender == null) {
            throw new IllegalStateException(
                    "Failed to create benchmark RewriteAppender"
            );
        }

        configuration.addAppender(rewriteAppender);

        LoggerConfig root = configuration.getRootLogger();
        root.setLevel(Level.ALL);
        for (String existing
                : List.copyOf(root.getAppenders().keySet())) {
            root.removeAppender(existing);
        }
        root.addAppender(
                rewriteAppender,
                Level.ALL,
                null
        );
    }

    private static void emit(
            Logger logger,
            String eventId,
            int ordinal
    ) {
        ThreadContext.clearAll();
        ThreadContext.put("eventId", eventId);
        ThreadContext.put(
                "customer",
                ordinal % 10 == 3
                        ? SSN
                        : "customer-" + ordinal
        );
        ThreadContext.push("request-" + ordinal);

        Level level = switch (ordinal % 4) {
            case 0 -> Level.INFO;
            case 1 -> Level.WARN;
            case 2 -> Level.ERROR;
            default -> Level.DEBUG;
        };

        try {
            switch (ordinal % 10) {
                case 0 -> logger.log(
                        level,
                        null,
                        new ParameterizedMessage(
                                "payment ssn={} email={}",
                                SSN,
                                EMAIL
                        )
                );
                case 1 -> logger.log(
                        level,
                        null,
                        new SimpleMessage(
                                "Customer Jane Doe moved to "
                                        + "14 Oak Lane in Charlotte"
                        )
                );
                case 2 -> logger.log(
                        level,
                        null,
                        new StringMapMessage(
                                Map.of(
                                        "account",
                                        ACCOUNT,
                                        "email",
                                        EMAIL
                                )
                        )
                );
                case 3 -> logger.log(
                        level,
                        null,
                        new SimpleMessage(
                                "customer context update completed"
                        )
                );
                case 4 -> logger.log(
                        level,
                        null,
                        new SimpleMessage(
                                "request processing failed"
                        ),
                        new IllegalStateException(
                                "customer ssn="
                                        + SSN
                                        + " email="
                                        + EMAIL
                        )
                );
                case 5 -> logger.log(
                        level,
                        null,
                        new SimpleMessage(longWindowedMessage())
                );
                case 6 -> logger.log(
                        level,
                        null,
                        new SimpleMessage(
                                "service version="
                                        + VERSION_CONTROL
                                        + " response=200"
                        )
                );
                case 7 -> logger.log(
                        level,
                        null,
                        new ParameterizedMessage(
                                "auth apiKey={} token={}",
                                API_KEY,
                                JWT
                        )
                );
                case 8 -> logger.log(
                        level,
                        null,
                        new ParameterizedMessage(
                                "checkout cardNumber={}",
                                CARD
                        )
                );
                case 9 -> logger.log(
                        level,
                        null,
                        new ParameterizedMessage(
                                "support phone={} customer=Jane Doe",
                                PHONE
                        )
                );
                default -> throw new IllegalStateException(
                        "Unexpected workload ordinal"
                );
            }
        } finally {
            ThreadContext.clearAll();
        }
    }

    private static String longWindowedMessage() {
        StringBuilder value = new StringBuilder(
                "name=Jane Doe workflow=application "
        );
        for (int i = 0; i < 450; i++) {
            value.append("segment")
                    .append(i)
                    .append("=operational ");
        }
        value.append("ssn=").append(SSN);
        return value.toString();
    }

    private static Validation validateOutput(
            String output,
            int eventCount
    ) {
        for (String raw : RAW_SENSITIVE_VALUES) {
            if (output.contains(raw)) {
                throw new IllegalStateException(
                        "Raw sensitive fixture reached application log: "
                                + raw
                );
            }
        }

        List<String> measured = output.lines()
                .filter(line -> line.contains("eventId=bench-"))
                .toList();

        if (measured.size() != eventCount) {
            throw new IllegalStateException(
                    "Application output count mismatch. expected="
                            + eventCount
                            + " actual="
                            + measured.size()
            );
        }

        long failClosed = output.lines()
                .filter(line -> line.contains("failClosed=true"))
                .count();

        if (failClosed != 0) {
            throw new IllegalStateException(
                    "Sequential representative workload fail-closed "
                            + failClosed
                            + " events"
            );
        }

        long mlInvoked = measured.stream()
                .filter(line -> line.contains("|ml=true|"))
                .count();

        if (mlInvoked < 1) {
            throw new IllegalStateException(
                    "Representative workload did not exercise ML"
            );
        }

        boolean versionPreserved =
                output.contains(
                        "version=" + VERSION_CONTROL
                );
        if (!versionPreserved) {
            throw new IllegalStateException(
                    "Benign version-like IP control was overmasked"
            );
        }

        JSONObject severityCounts = new JSONObject();
        for (Level level : List.of(
                Level.INFO,
                Level.WARN,
                Level.ERROR,
                Level.DEBUG
        )) {
            long count = measured.stream()
                    .filter(
                            line -> line.contains(
                                    "|level="
                                            + level.name()
                                            + "|"
                            )
                    )
                    .count();
            severityCounts.put(level.name(), count);
            if (count < 1) {
                throw new IllegalStateException(
                        "Severity was not represented: "
                                + level.name()
                );
            }
        }

        return new Validation(
                measured.size(),
                mlInvoked,
                failClosed,
                versionPreserved,
                severityCounts
        );
    }

    private static JSONObject latencyJson(
            List<Long> nanos
    ) {
        List<Long> sorted = nanos.stream()
                .sorted()
                .toList();

        JSONObject json = new JSONObject();
        json.put("count", sorted.size());
        json.put("p50", millis(percentile(sorted, 0.50)));
        json.put("p95", millis(percentile(sorted, 0.95)));
        json.put("p99", millis(percentile(sorted, 0.99)));
        json.put(
                "max",
                millis(sorted.get(sorted.size() - 1))
        );
        return json;
    }

    private static long percentile(
            List<Long> sorted,
            double percentile
    ) {
        int index = (int) Math.ceil(
                percentile * sorted.size()
        ) - 1;
        return sorted.get(
                Math.max(
                        0,
                        Math.min(index, sorted.size() - 1)
                )
        );
    }

    private static double millis(long nanos) {
        return nanos / 1_000_000.0;
    }

    private static RuntimeMemorySnapshot settledSnapshot(
            String label
    ) throws InterruptedException {
        System.gc();
        Thread.sleep(400);
        System.gc();
        Thread.sleep(400);
        return RuntimeMemorySnapshot.capture(label);
    }

    private static JSONObject memoryDelta(
            RuntimeMemorySnapshot before,
            RuntimeMemorySnapshot after
    ) {
        JSONObject json = new JSONObject();
        json.put(
                "heap_used_bytes",
                after.heapUsedBytes() - before.heapUsedBytes()
        );
        json.put(
                "non_heap_used_bytes",
                after.nonHeapUsedBytes()
                        - before.nonHeapUsedBytes()
        );
        json.put(
                "direct_used_bytes",
                after.directUsedBytes()
                        - before.directUsedBytes()
        );
        putOptionalDelta(
                json,
                "process_working_set_bytes",
                before.processWorkingSetBytes(),
                after.processWorkingSetBytes()
        );
        putOptionalDelta(
                json,
                "process_private_bytes",
                before.processPrivateBytes(),
                after.processPrivateBytes()
        );
        putOptionalDelta(
                json,
                "gpu_process_memory_bytes",
                before.gpuProcessMemoryBytes(),
                after.gpuProcessMemoryBytes()
        );
        return json;
    }

    private static void putOptionalDelta(
            JSONObject json,
            String key,
            long before,
            long after
    ) {
        if (before < 0 || after < 0) {
            json.put(key, JSONObject.NULL);
        } else {
            json.put(key, after - before);
        }
    }

    private static void printSummary(
            JSONObject result,
            Path logFile,
            Path resultPath
    ) {
        JSONObject latency =
                result.getJSONObject(
                        "caller_latency_millis"
                );

        System.out.println();
        System.out.println(
                "REPRESENTATIVE APPLICATION LOG BENCHMARK PASSED"
        );
        System.out.println(
                "Events: " + result.getInt("events")
        );
        System.out.println(
                "ML-invoked events: "
                        + result.getLong(
                                "ml_invoked_events"
                        )
        );
        System.out.println(
                "Caller p50/p95/p99: "
                        + latency.get("p50")
                        + " / "
                        + latency.get("p95")
                        + " / "
                        + latency.get("p99")
                        + " ms"
        );
        System.out.println(
                "Throughput: "
                        + String.format(
                                "%.3f",
                                result.getDouble(
                                        "events_per_second"
                                )
                        )
                        + " events/s"
        );
        System.out.println(
                "Fail-closed events: "
                        + result.getLong(
                                "fail_closed_events"
                        )
        );
        System.out.println("Raw payload forwarded: false");
        System.out.println(
                "Benign version preserved: "
                        + result.getBoolean(
                                "benign_version_preserved"
                        )
        );
        System.out.println("Protected log: " + logFile);
        System.out.println("Result: " + resultPath);
    }

    private record Validation(
            int measuredOutputEvents,
            long mlInvokedEvents,
            long failClosedEvents,
            boolean benignVersionPreserved,
            JSONObject severityCounts
    ) {
    }

    private static final class BenchmarkConfiguration
            extends AbstractConfiguration {

        private BenchmarkConfiguration(
                LoggerContext context
        ) {
            super(
                    context,
                    ConfigurationSource.NULL_SOURCE
            );
        }

        @Override
        protected void doConfigure() {
            // Pipeline is assembled programmatically before context.start().
        }
    }
}
