package com.securelogx.validation;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import com.securelogx.config.SecureLogXConfig;
import com.securelogx.detection.DetectionSource;
import com.securelogx.detection.HybridRuntimeStats;
import com.securelogx.detection.MaskingPolicy;
import com.securelogx.detection.ResolutionAction;
import com.securelogx.detection.ResolvedSpan;
import com.securelogx.model.LogEvent;
import com.securelogx.model.LogLevel;
import com.securelogx.ner.TokenizedInput;
import com.securelogx.ner.impl.LabelAwareMaskingEngine;
import com.securelogx.ner.impl.ONNXDynamicInferenceEngine;
import com.securelogx.ner.impl.ParallelTokenizer;
import com.securelogx.util.ArtifactIntegrityVerifier;
import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Steady-state end-to-end performance comparison between:
 *
 * M0 - frozen ML-v1.3 on every record
 * H1 - current SecureLogX hybrid routing + windowed ML + resolver/policy
 *
 * Uses the same Scenario A-D messages as ProductionRoutingBenchmark.
 *
 * This is an engineering benchmark rather than JMH. It provides reproducible
 * end-to-end throughput and batch latency measurements. A later release phase
 * should add profiler/JFR/JMH work for allocation and microbenchmark analysis.
 */
public final class PerformanceComparisonBenchmark {

    private static final int DEFAULT_SAMPLE_PER_SCENARIO = 160;
    private static final int DEFAULT_ITERATIONS = 3;
    private static final int BATCH_SIZE = 32;
    private static final int WARMUP_RECORDS = 32;
    private static final int WINDOW_OVERLAP_CONTENT_TOKENS = 64;

    private PerformanceComparisonBenchmark() {
    }

    public static void main(String[] args) throws Exception {
        Path resultPath = args.length > 0
                ? Path.of(args[0])
                : Path.of("reports/performance-comparison/result.json");
        int samplePerScenario = args.length > 1
                ? Integer.parseInt(args[1])
                : DEFAULT_SAMPLE_PER_SCENARIO;
        int iterations = args.length > 2
                ? Integer.parseInt(args[2])
                : DEFAULT_ITERATIONS;

        if (samplePerScenario < BATCH_SIZE) {
            throw new IllegalArgumentException(
                    "samplePerScenario must be at least " + BATCH_SIZE
            );
        }
        if (iterations < 1) {
            throw new IllegalArgumentException(
                    "iterations must be at least 1"
            );
        }

        SecureLogXConfig config = new SecureLogXConfig("dev");
        ArtifactIntegrityVerifier.verifySha256(
                "ML-v1.3 ONNX model",
                config.getModelPath(),
                config.getModelSha256()
        );
        ArtifactIntegrityVerifier.verifySha256(
                "ML-v1.3 tokenizer",
                config.getTokenizerPath(),
                config.getTokenizerSha256()
        );

        ParallelTokenizer tokenizer = new ParallelTokenizer(
                config.getTokenizerPath(),
                config.getMaxSequenceLength()
        );

        Map<String, List<String>> allScenarios =
                ProductionRoutingBenchmark.scenarioMessages();
        Map<String, List<String>> sampledScenarios =
                new LinkedHashMap<>();

        for (String id : List.of("A", "B", "C", "D")) {
            List<String> full = allScenarios.get(id);
            if (full == null) {
                throw new IllegalStateException(
                        "Missing production scenario " + id
                );
            }
            sampledScenarios.put(
                    id,
                    sampleEvenly(full, samplePerScenario)
            );
        }

        List<String> warmup = sampledScenarios.get("A")
                .subList(
                        0,
                        Math.min(
                                WARMUP_RECORDS,
                                sampledScenarios.get("A").size()
                        )
                );

        long m0InitStart = System.nanoTime();
        MlOnlyRunner m0 = new MlOnlyRunner(config, tokenizer);
        long m0InitNanos = System.nanoTime() - m0InitStart;

        long h1InitStart = System.nanoTime();
        HybridRunner h1 = new HybridRunner(config, tokenizer);
        long h1InitNanos = System.nanoTime() - h1InitStart;

        try {
            // Warm up model/session/JIT paths outside measured samples.
            m0.run(warmup);
            h1.run(warmup);

            JSONArray scenarioResults = new JSONArray();

            for (Map.Entry<String, List<String>> entry
                    : sampledScenarios.entrySet()) {
                String scenarioId = entry.getKey();
                List<String> messages = entry.getValue();

                Measurement m0Measurement = measure(
                        messages,
                        iterations,
                        m0::run
                );

                HybridRuntimeStats before = h1.stats();
                Measurement h1Measurement = measure(
                        messages,
                        iterations,
                        h1::run
                );
                HybridRuntimeStats after = h1.stats();

                long h1Deterministic = after.deterministicOnlyItems()
                        - before.deterministicOnlyItems();
                long h1Ml = after.mlInferenceItems()
                        - before.mlInferenceItems();
                long h1Windowed = after.windowedMlItems()
                        - before.windowedMlItems();
                long h1Windows = after.mlInferenceWindows()
                        - before.mlInferenceWindows();
                long h1FailClosed = after.truncatedFailClosedItems()
                        - before.truncatedFailClosedItems();

                long routed = h1Deterministic + h1Ml + h1FailClosed;
                double h1MlRate = routed == 0
                        ? 0.0
                        : (double) h1Ml / routed;

                JSONObject scenario = new JSONObject();
                scenario.put("scenario", scenarioId);
                scenario.put("sample_records", messages.size());
                scenario.put("iterations", iterations);
                scenario.put("batch_size", BATCH_SIZE);
                scenario.put("M0", m0Measurement.toJson());
                scenario.put("H1", h1Measurement.toJson());
                scenario.put(
                        "steady_state_speedup_m0_over_h1",
                        ratio(
                                m0Measurement.totalNanos,
                                h1Measurement.totalNanos
                        )
                );
                scenario.put("h1_deterministic_records", h1Deterministic);
                scenario.put("h1_ml_records", h1Ml);
                scenario.put("h1_ml_invocation_rate", h1MlRate);
                scenario.put("h1_windowed_records", h1Windowed);
                scenario.put("h1_ml_inference_windows", h1Windows);
                scenario.put(
                        "h1_truncated_fail_closed_records",
                        h1FailClosed
                );
                scenarioResults.put(scenario);

                printScenario(
                        scenarioId,
                        m0Measurement,
                        h1Measurement,
                        h1MlRate,
                        h1FailClosed
                );
            }

            JSONObject result = new JSONObject();
            result.put(
                    "status",
                    "M0/H1 PERFORMANCE COMPARISON COMPLETE"
            );
            result.put("passed", true);
            result.put("benchmark_type", "engineering_end_to_end");
            result.put("sample_per_scenario", samplePerScenario);
            result.put("iterations", iterations);
            result.put("batch_size", BATCH_SIZE);
            result.put("window_overlap_content_tokens",
                    WINDOW_OVERLAP_CONTENT_TOKENS);
            result.put("m0_init_ms", nanosToMillis(m0InitNanos));
            result.put("h1_init_ms", nanosToMillis(h1InitNanos));
            result.put(
                    "available_processors",
                    Runtime.getRuntime().availableProcessors()
            );
            result.put(
                    "java_version",
                    System.getProperty("java.version")
            );
            result.put(
                    "os_name",
                    System.getProperty("os.name")
            );
            result.put(
                    "os_arch",
                    System.getProperty("os.arch")
            );
            result.put("scenarios", scenarioResults);
            result.put(
                    "notes",
                    new JSONArray(List.of(
                            "Warm-up is excluded from measured samples.",
                            "Batch latency percentiles are computed across 32-record benchmark calls.",
                            "M0 and H1 use the same frozen model/tokenizer and overlapping-window policy.",
                            "This benchmark does not yet report allocation rate or peak RSS; use JFR/JMH/profiler work in the later performance-hardening phase."
                    ))
            );

            Path parent = resultPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(
                    resultPath,
                    result.toString(2) + System.lineSeparator(),
                    StandardCharsets.UTF_8
            );

            System.out.println();
            System.out.println(
                    "M0/H1 PERFORMANCE COMPARISON COMPLETE"
            );
            System.out.println(
                    "M0 init: "
                            + formatMillis(nanosToMillis(m0InitNanos))
                            + " ms"
            );
            System.out.println(
                    "H1 init: "
                            + formatMillis(nanosToMillis(h1InitNanos))
                            + " ms"
            );
            System.out.println("Result: " + resultPath);
        } finally {
            h1.close();
            m0.close();
        }
    }

    private static Measurement measure(
            List<String> messages,
            int iterations,
            BatchRunner runner
    ) throws Exception {
        List<Long> batchNanos = new ArrayList<>();
        long totalNanos = 0;
        long totalRecords = 0;
        long checksum = 0;

        for (int iteration = 0; iteration < iterations; iteration++) {
            for (int start = 0; start < messages.size(); start += BATCH_SIZE) {
                int end = Math.min(start + BATCH_SIZE, messages.size());
                List<String> batch = messages.subList(start, end);

                long started = System.nanoTime();
                List<String> outputs = runner.run(batch);
                long elapsed = System.nanoTime() - started;

                if (outputs.size() != batch.size()) {
                    throw new IllegalStateException(
                            "Benchmark output count mismatch"
                    );
                }

                for (String output : outputs) {
                    checksum += output.hashCode();
                }

                batchNanos.add(elapsed);
                totalNanos += elapsed;
                totalRecords += batch.size();
            }
        }

        return new Measurement(
                totalRecords,
                totalNanos,
                batchNanos,
                checksum
        );
    }

    private static List<String> sampleEvenly(
            List<String> source,
            int requested
    ) {
        if (requested >= source.size()) {
            return List.copyOf(source);
        }

        List<String> sample = new ArrayList<>(requested);
        if (requested == 1) {
            sample.add(source.get(0));
            return List.copyOf(sample);
        }

        for (int i = 0; i < requested; i++) {
            int index = (int) Math.round(
                    (double) i
                            * (source.size() - 1)
                            / (requested - 1)
            );
            sample.add(source.get(index));
        }
        return List.copyOf(sample);
    }

    private static void printScenario(
            String id,
            Measurement m0,
            Measurement h1,
            double h1MlRate,
            long h1FailClosed
    ) {
        System.out.println();
        System.out.println("Scenario " + id);
        System.out.println(
                "  M0 throughput: "
                        + formatDouble(m0.throughput())
                        + " records/s"
        );
        System.out.println(
                "  H1 throughput: "
                        + formatDouble(h1.throughput())
                        + " records/s"
        );
        System.out.println(
                "  speedup M0/H1: "
                        + formatDouble(
                                ratio(m0.totalNanos, h1.totalNanos)
                        )
                        + "x"
        );
        System.out.println(
                "  M0 batch p50/p95/p99: "
                        + formatMillis(m0.percentileMillis(0.50))
                        + " / "
                        + formatMillis(m0.percentileMillis(0.95))
                        + " / "
                        + formatMillis(m0.percentileMillis(0.99))
                        + " ms"
        );
        System.out.println(
                "  H1 batch p50/p95/p99: "
                        + formatMillis(h1.percentileMillis(0.50))
                        + " / "
                        + formatMillis(h1.percentileMillis(0.95))
                        + " / "
                        + formatMillis(h1.percentileMillis(0.99))
                        + " ms"
        );
        System.out.println(
                "  H1 ML invocation: "
                        + String.format("%.2f%%", h1MlRate * 100.0)
        );
        System.out.println(
                "  H1 fail-closed records: " + h1FailClosed
        );
    }

    private static double ratio(long numerator, long denominator) {
        return denominator == 0
                ? 0.0
                : (double) numerator / denominator;
    }

    private static double nanosToMillis(long nanos) {
        return nanos / 1_000_000.0;
    }

    private static String formatMillis(double millis) {
        return String.format("%.3f", millis);
    }

    private static String formatDouble(double value) {
        return String.format("%.3f", value);
    }

    @FunctionalInterface
    private interface BatchRunner {
        List<String> run(List<String> messages) throws Exception;
    }

    private static final class Measurement {
        private final long records;
        private final long totalNanos;
        private final List<Long> batchNanos;
        private final long checksum;

        private Measurement(
                long records,
                long totalNanos,
                List<Long> batchNanos,
                long checksum
        ) {
            this.records = records;
            this.totalNanos = totalNanos;
            this.batchNanos = List.copyOf(batchNanos);
            this.checksum = checksum;
        }

        private double throughput() {
            return totalNanos == 0
                    ? 0.0
                    : records / (totalNanos / 1_000_000_000.0);
        }

        private double percentileMillis(double percentile) {
            if (batchNanos.isEmpty()) {
                return 0.0;
            }
            List<Long> ordered = new ArrayList<>(batchNanos);
            ordered.sort(Long::compareTo);
            int index = (int) Math.ceil(percentile * ordered.size()) - 1;
            index = Math.max(0, Math.min(index, ordered.size() - 1));
            return nanosToMillis(ordered.get(index));
        }

        private JSONObject toJson() {
            JSONObject object = new JSONObject();
            object.put("records", records);
            object.put("total_ms", nanosToMillis(totalNanos));
            object.put("throughput_records_per_second", throughput());
            object.put("batch_samples", batchNanos.size());
            object.put("batch_p50_ms", percentileMillis(0.50));
            object.put("batch_p95_ms", percentileMillis(0.95));
            object.put("batch_p99_ms", percentileMillis(0.99));
            object.put("output_checksum", checksum);
            return object;
        }
    }

    private static final class HybridRunner implements AutoCloseable {
        private final ParallelTokenizer tokenizer;
        private final ONNXDynamicInferenceEngine engine;
        private long sequence;

        private HybridRunner(
                SecureLogXConfig config,
                ParallelTokenizer tokenizer
        ) throws Exception {
            this.tokenizer = tokenizer;
            this.engine = new ONNXDynamicInferenceEngine(
                    config.getModelPath(),
                    config
            );
        }

        private List<String> run(List<String> messages) {
            List<LogEvent> events = new ArrayList<>(messages.size());
            for (String message : messages) {
                events.add(
                        new LogEvent(
                                message,
                                LogLevel.SECURE,
                                false,
                                "performance-benchmark",
                                ++sequence
                        )
                );
            }
            return engine.runBatch(tokenizer, events);
        }

        private HybridRuntimeStats stats() {
            return engine.getHybridRuntimeStats();
        }

        @Override
        public void close() {
            engine.shutdown();
        }
    }

    private static final class MlOnlyRunner implements AutoCloseable {
        private static final int WINDOW_BATCH_SIZE = 32;

        private final ParallelTokenizer tokenizer;
        private final OrtEnvironment env;
        private final OrtSession session;
        private final LabelAwareMaskingEngine decoder =
                new LabelAwareMaskingEngine();
        private final MaskingPolicy maskingPolicy = new MaskingPolicy();

        private MlOnlyRunner(
                SecureLogXConfig config,
                ParallelTokenizer tokenizer
        ) throws Exception {
            this.tokenizer = tokenizer;
            this.env = OrtEnvironment.getEnvironment();

            OrtSession.SessionOptions options =
                    new OrtSession.SessionOptions();
            if (config.isCpuMultithreadingEnabled()) {
                options.setIntraOpNumThreads(
                        Math.max(
                                1,
                                Math.min(
                                        config.getMaxCpuThreads(),
                                        Runtime.getRuntime()
                                                .availableProcessors()
                                )
                        )
                );
            }

            this.session = env.createSession(
                    config.getModelPath().replace("\\", "/"),
                    options
            );
        }

        private List<String> run(List<String> messages) throws Exception {
            List<TokenizedInput> flatWindows = new ArrayList<>();
            List<Integer> originalIndices = new ArrayList<>();

            for (int originalIndex = 0;
                 originalIndex < messages.size();
                 originalIndex++) {
                List<TokenizedInput> windows =
                        tokenizer.tokenizeWindows(
                                messages.get(originalIndex),
                                WINDOW_OVERLAP_CONTENT_TOKENS
                        );
                if (windows.isEmpty()) {
                    throw new IllegalStateException(
                            "Tokenizer returned no M0 windows"
                    );
                }

                for (TokenizedInput window : windows) {
                    if (window.isTruncated()) {
                        throw new IllegalStateException(
                                "M0 window unexpectedly truncated"
                        );
                    }
                    flatWindows.add(window);
                    originalIndices.add(originalIndex);
                }
            }

            List<List<LabelAwareMaskingEngine.EntitySpan>> spansByRecord =
                    new ArrayList<>(messages.size());
            for (int i = 0; i < messages.size(); i++) {
                spansByRecord.add(new ArrayList<>());
            }

            for (int start = 0;
                 start < flatWindows.size();
                 start += WINDOW_BATCH_SIZE) {
                int end = Math.min(
                        start + WINDOW_BATCH_SIZE,
                        flatWindows.size()
                );
                List<TokenizedInput> chunk =
                        flatWindows.subList(start, end);

                int seqLen = chunk.stream()
                        .mapToInt(item -> item.getInputIds().length)
                        .max()
                        .orElse(0);

                long[][] inputIds = new long[chunk.size()][seqLen];
                long[][] attentionMask = new long[chunk.size()][seqLen];
                long[][] tokenTypeIds = new long[chunk.size()][seqLen];

                for (int i = 0; i < chunk.size(); i++) {
                    int[] ids = chunk.get(i).getInputIds();
                    int[] mask = chunk.get(i).getAttentionMask();
                    for (int j = 0; j < ids.length; j++) {
                        inputIds[i][j] = ids[j];
                        attentionMask[i][j] = mask[j];
                    }
                }

                try (OnnxTensor inputTensor =
                             OnnxTensor.createTensor(env, inputIds);
                     OnnxTensor maskTensor =
                             OnnxTensor.createTensor(env, attentionMask);
                     OnnxTensor typeTensor =
                             OnnxTensor.createTensor(env, tokenTypeIds)) {

                    Map<String, OnnxTensor> inputs = Map.of(
                            "input_ids", inputTensor,
                            "attention_mask", maskTensor,
                            "token_type_ids", typeTensor
                    );

                    try (OrtSession.Result result = session.run(inputs)) {
                        float[][][] logits =
                                (float[][][]) result.get(0).getValue();

                        for (int local = 0; local < chunk.size(); local++) {
                            int flat = start + local;
                            int originalIndex =
                                    originalIndices.get(flat);
                            TokenizedInput window = chunk.get(local);

                            spansByRecord.get(originalIndex).addAll(
                                    decoder.decodeSpans(
                                            messages.get(originalIndex),
                                            new float[][][]{logits[local]},
                                            window.getOffsets()
                                    )
                            );
                        }
                    }
                }
            }

            List<String> outputs = new ArrayList<>(messages.size());
            for (int i = 0; i < messages.size(); i++) {
                List<LabelAwareMaskingEngine.EntitySpan> merged =
                        mergeWindowSpans(spansByRecord.get(i));
                List<ResolvedSpan> resolved =
                        new ArrayList<>(merged.size());
                for (LabelAwareMaskingEngine.EntitySpan span : merged) {
                    resolved.add(
                            new ResolvedSpan(
                                    span.start(),
                                    span.end(),
                                    span.entityType(),
                                    ResolutionAction.MASK,
                                    DetectionSource.ML,
                                    "m0-performance-benchmark"
                            )
                    );
                }
                outputs.add(
                        maskingPolicy.apply(
                                messages.get(i),
                                resolved,
                                false
                        )
                );
            }
            return outputs;
        }

        @Override
        public void close() throws Exception {
            session.close();
        }
    }

    private static List<LabelAwareMaskingEngine.EntitySpan>
            mergeWindowSpans(
                    List<LabelAwareMaskingEngine.EntitySpan> spans
            ) {
        if (spans.isEmpty()) {
            return List.of();
        }

        List<LabelAwareMaskingEngine.EntitySpan> ordered =
                new ArrayList<>(spans);
        ordered.sort(
                Comparator.comparing(
                                LabelAwareMaskingEngine.EntitySpan::entityType
                        )
                        .thenComparingInt(
                                LabelAwareMaskingEngine.EntitySpan::start
                        )
                        .thenComparingInt(
                                LabelAwareMaskingEngine.EntitySpan::end
                        )
        );

        List<LabelAwareMaskingEngine.EntitySpan> merged =
                new ArrayList<>();
        LabelAwareMaskingEngine.EntitySpan current = null;

        for (LabelAwareMaskingEngine.EntitySpan span : ordered) {
            if (current == null) {
                current = span;
                continue;
            }

            boolean sameType =
                    current.entityType().equals(span.entityType());
            boolean overlaps =
                    Math.max(current.start(), span.start())
                            < Math.min(current.end(), span.end());

            if (sameType && overlaps) {
                current = new LabelAwareMaskingEngine.EntitySpan(
                        Math.min(current.start(), span.start()),
                        Math.max(current.end(), span.end()),
                        current.entityType()
                );
            } else {
                merged.add(current);
                current = span;
            }
        }

        if (current != null) {
            merged.add(current);
        }

        merged.sort(
                Comparator.comparingInt(
                                LabelAwareMaskingEngine.EntitySpan::start
                        )
                        .thenComparingInt(
                                LabelAwareMaskingEngine.EntitySpan::end
                        )
                        .thenComparing(
                                LabelAwareMaskingEngine.EntitySpan::entityType
                        )
        );
        return List.copyOf(merged);
    }
}
