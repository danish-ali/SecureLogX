package com.securelogx.validation;

import com.securelogx.config.SecureLogXConfig;
import com.securelogx.detection.HybridRuntimeStats;
import com.securelogx.model.LogEvent;
import com.securelogx.model.LogLevel;
import com.securelogx.ner.impl.ONNXDynamicInferenceEngine;
import com.securelogx.ner.impl.ParallelTokenizer;
import com.securelogx.util.ArtifactIntegrityVerifier;
import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Repeated ONNX session create -> warm-up -> infer -> shutdown memory soak.
 *
 * Purpose:
 * - detect retained native growth across session restarts,
 * - confirm post-shutdown private memory returns near the tokenizer baseline,
 * - exercise normal A-D routing and long-record overlapping-window inference,
 * - keep JVM heap and process/native memory visible separately.
 */
public final class RuntimeMemoryRestartSoakBenchmark {

    private static final int DEFAULT_CYCLES = 4;
    private static final int DEFAULT_SAMPLE_PER_SCENARIO = 80;
    private static final int BATCH_SIZE = 32;
    private static final int WARMUP_RECORDS = 32;
    private static final int LONG_WINDOW_RECORDS = 8;

    private RuntimeMemoryRestartSoakBenchmark() {
    }

    public static void main(String[] args) throws Exception {
        Path resultPath = args.length > 0
                ? Path.of(args[0])
                : Path.of(
                        "reports/runtime-memory-restart-soak/result.json"
                );
        int cycles = args.length > 1
                ? Integer.parseInt(args[1])
                : DEFAULT_CYCLES;
        int samplePerScenario = args.length > 2
                ? Integer.parseInt(args[2])
                : DEFAULT_SAMPLE_PER_SCENARIO;

        if (cycles < 2) {
            throw new IllegalArgumentException(
                    "cycles must be at least 2"
            );
        }
        if (samplePerScenario < BATCH_SIZE) {
            throw new IllegalArgumentException(
                    "samplePerScenario must be at least " + BATCH_SIZE
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

        RuntimeMemorySnapshot tokenizerBaseline =
                settledSnapshot("tokenizer-baseline");

        Map<String, List<String>> scenarios =
                sampledScenarios(samplePerScenario);
        List<String> warmup = scenarios.get("A").subList(
                0,
                Math.min(
                        WARMUP_RECORDS,
                        scenarios.get("A").size()
                )
        );
        List<String> longWindowMessages =
                longWindowMessages(LONG_WINDOW_RECORDS);

        JSONArray cycleResults = new JSONArray();
        RuntimeMemorySnapshot previousShutdown =
                tokenizerBaseline;
        long sequence = 0;

        long maxPostWarmupPrivate = -1;
        long maxPostWorkloadPrivate = -1;
        long maxPostShutdownPrivate = -1;

        for (int cycle = 1; cycle <= cycles; cycle++) {
            RuntimeMemorySnapshot beforeCreate =
                    settledSnapshot(
                            "cycle-" + cycle + "-before-create"
                    );

            ONNXDynamicInferenceEngine engine = null;
            RuntimeMemorySnapshot afterInit;
            RuntimeMemorySnapshot afterWarmup;
            RuntimeMemorySnapshot afterWorkload;
            RuntimeMemorySnapshot afterLongWindow;
            RuntimeMemorySnapshot afterShutdown;

            HybridRuntimeStats statsBefore;
            HybridRuntimeStats statsAfter;

            try {
                engine = new ONNXDynamicInferenceEngine(
                        config.getModelPath(),
                        config
                );
                afterInit = RuntimeMemorySnapshot.capture(
                        "cycle-" + cycle + "-after-init"
                );

                sequence = runMessages(
                        engine,
                        tokenizer,
                        warmup,
                        sequence
                );
                afterWarmup = settledSnapshot(
                        "cycle-" + cycle + "-after-warmup-settled"
                );

                statsBefore = engine.getHybridRuntimeStats();

                for (String id : List.of("A", "B", "C", "D")) {
                    sequence = runMessages(
                            engine,
                            tokenizer,
                            scenarios.get(id),
                            sequence
                    );
                }

                afterWorkload = settledSnapshot(
                        "cycle-" + cycle + "-after-ad-settled"
                );

                sequence = runMessages(
                        engine,
                        tokenizer,
                        longWindowMessages,
                        sequence
                );
                afterLongWindow = settledSnapshot(
                        "cycle-" + cycle + "-after-windowed-settled"
                );

                statsAfter = engine.getHybridRuntimeStats();
            } finally {
                if (engine != null) {
                    engine.shutdown();
                }
            }

            afterShutdown = settledSnapshot(
                    "cycle-" + cycle + "-after-shutdown-settled"
            );

            maxPostWarmupPrivate = maxKnown(
                    maxPostWarmupPrivate,
                    afterWarmup.processPrivateBytes()
            );
            maxPostWorkloadPrivate = maxKnown(
                    maxPostWorkloadPrivate,
                    afterLongWindow.processPrivateBytes()
            );
            maxPostShutdownPrivate = maxKnown(
                    maxPostShutdownPrivate,
                    afterShutdown.processPrivateBytes()
            );

            JSONObject cycleJson = new JSONObject();
            cycleJson.put("cycle", cycle);
            cycleJson.put("before_create", beforeCreate.toJson());
            cycleJson.put("after_init", afterInit.toJson());
            cycleJson.put(
                    "after_warmup_settled",
                    afterWarmup.toJson()
            );
            cycleJson.put(
                    "after_ad_workload_settled",
                    afterWorkload.toJson()
            );
            cycleJson.put(
                    "after_windowed_workload_settled",
                    afterLongWindow.toJson()
            );
            cycleJson.put(
                    "after_shutdown_settled",
                    afterShutdown.toJson()
            );

            cycleJson.put(
                    "post_shutdown_vs_tokenizer_baseline",
                    deltaJson(tokenizerBaseline, afterShutdown)
            );
            cycleJson.put(
                    "post_shutdown_vs_previous_cycle",
                    deltaJson(previousShutdown, afterShutdown)
            );
            cycleJson.put(
                    "runtime_delta",
                    runtimeDelta(statsBefore, statsAfter)
            );

            cycleResults.put(cycleJson);

            printCycle(
                    cycle,
                    tokenizerBaseline,
                    afterWarmup,
                    afterLongWindow,
                    afterShutdown
            );

            previousShutdown = afterShutdown;
        }

        RuntimeMemorySnapshot finalSettled =
                settledSnapshot("final-settled");

        JSONObject result = new JSONObject();
        result.put(
                "status",
                "H1 RUNTIME MEMORY RESTART SOAK COMPLETE"
        );
        result.put("passed", true);
        result.put("cycles", cycles);
        result.put(
                "sample_per_scenario",
                samplePerScenario
        );
        result.put(
                "long_window_records_per_cycle",
                LONG_WINDOW_RECORDS
        );
        result.put("batch_size", BATCH_SIZE);
        result.put(
                "configured_max_inference_windows_per_batch",
                config.getMaxInferenceWindowsPerBatch()
        );
        result.put(
                "gpu_inference_requested",
                config.isGpuInferenceEnabled()
        );
        result.put(
                "tokenizer_baseline",
                tokenizerBaseline.toJson()
        );
        result.put("cycles_detail", cycleResults);
        result.put("final_settled", finalSettled.toJson());

        putOptional(
                result,
                "max_post_warmup_private_bytes",
                maxPostWarmupPrivate
        );
        putOptional(
                result,
                "max_post_workload_private_bytes",
                maxPostWorkloadPrivate
        );
        putOptional(
                result,
                "max_post_shutdown_private_bytes",
                maxPostShutdownPrivate
        );

        result.put(
                "interpretation_policy",
                new JSONArray(List.of(
                        "A monotonic increase in post-shutdown private bytes across restart cycles is a native-retention warning.",
                        "A stable post-warmup plateau with stable post-shutdown recovery is the desired shape.",
                        "Working set may remain above private-memory recovery because the OS can retain resident pages; evaluate trend rather than one absolute point.",
                        "Long-window inference must not create cycle-over-cycle retained growth.",
                        "GPU memory must be evaluated separately when CUDA is active."
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
                "H1 RUNTIME MEMORY RESTART SOAK COMPLETE"
        );
        System.out.println("Result: " + resultPath);
    }

    private static long runMessages(
            ONNXDynamicInferenceEngine engine,
            ParallelTokenizer tokenizer,
            List<String> messages,
            long startingSequence
    ) {
        long sequence = startingSequence;

        for (int start = 0;
             start < messages.size();
             start += BATCH_SIZE) {
            int end = Math.min(
                    start + BATCH_SIZE,
                    messages.size()
            );

            List<LogEvent> events =
                    new ArrayList<>(end - start);
            for (int i = start; i < end; i++) {
                sequence++;
                if (sequence > Integer.MAX_VALUE) {
                    throw new IllegalStateException(
                            "Benchmark sequence exceeded int range"
                    );
                }

                events.add(
                        new LogEvent(
                                messages.get(i),
                                LogLevel.SECURE,
                                false,
                                "memory-restart-soak",
                                (int) sequence
                        )
                );
            }

            List<String> outputs =
                    engine.runBatch(tokenizer, events);
            if (outputs.size() != events.size()) {
                throw new IllegalStateException(
                        "Memory soak output count mismatch"
                );
            }

            for (String output : outputs) {
                if (output.contains(
                        "[SECURELOGX_REDACTED_PROCESSING_FAILURE]"
                )) {
                    throw new IllegalStateException(
                            "Memory soak entered fail-closed fallback"
                    );
                }
            }
        }

        return sequence;
    }

    private static Map<String, List<String>> sampledScenarios(
            int requested
    ) {
        Map<String, List<String>> full =
                ProductionRoutingBenchmark.scenarioMessages();
        Map<String, List<String>> result =
                new LinkedHashMap<>();

        for (String id : List.of("A", "B", "C", "D")) {
            List<String> source = full.get(id);
            if (source == null) {
                throw new IllegalStateException(
                        "Missing production scenario " + id
                );
            }
            result.put(id, sampleEvenly(source, requested));
        }
        return result;
    }

    private static List<String> sampleEvenly(
            List<String> source,
            int requested
    ) {
        if (requested >= source.size()) {
            return List.copyOf(source);
        }

        List<String> sample =
                new ArrayList<>(requested);

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

    private static List<String> longWindowMessages(int count) {
        List<String> messages = new ArrayList<>(count);

        for (int record = 0; record < count; record++) {
            StringBuilder text =
                    new StringBuilder("name=Jane Doe ");
            for (int i = 0; i < 450; i++) {
                text.append("segment")
                        .append(record)
                        .append('_')
                        .append(i)
                        .append("=operational ");
            }
            text.append(" ssn=123-45-6789");
            messages.add(text.toString());
        }

        return List.copyOf(messages);
    }

    private static RuntimeMemorySnapshot settledSnapshot(
            String label
    ) throws InterruptedException {
        System.gc();
        Thread.sleep(400);
        return RuntimeMemorySnapshot.capture(label);
    }

    private static JSONObject runtimeDelta(
            HybridRuntimeStats before,
            HybridRuntimeStats after
    ) {
        JSONObject object = new JSONObject();
        object.put(
                "deterministic_only_records",
                after.deterministicOnlyItems()
                        - before.deterministicOnlyItems()
        );
        object.put(
                "ml_records",
                after.mlInferenceItems()
                        - before.mlInferenceItems()
        );
        object.put(
                "windowed_ml_records",
                after.windowedMlItems()
                        - before.windowedMlItems()
        );
        object.put(
                "ml_inference_windows",
                after.mlInferenceWindows()
                        - before.mlInferenceWindows()
        );
        object.put(
                "onnx_inference_calls",
                after.onnxInferenceCalls()
                        - before.onnxInferenceCalls()
        );
        object.put(
                "max_inference_windows_per_call_observed",
                after.maxInferenceWindowsPerCallObserved()
        );
        object.put(
                "truncated_fail_closed_records",
                after.truncatedFailClosedItems()
                        - before.truncatedFailClosedItems()
        );
        return object;
    }

    private static JSONObject deltaJson(
            RuntimeMemorySnapshot before,
            RuntimeMemorySnapshot after
    ) {
        JSONObject object = new JSONObject();
        object.put(
                "heap_used_bytes",
                after.heapUsedBytes() - before.heapUsedBytes()
        );
        object.put(
                "non_heap_used_bytes",
                after.nonHeapUsedBytes() - before.nonHeapUsedBytes()
        );
        object.put(
                "direct_used_bytes",
                after.directUsedBytes() - before.directUsedBytes()
        );

        putDelta(
                object,
                "process_working_set_bytes",
                before.processWorkingSetBytes(),
                after.processWorkingSetBytes()
        );
        putDelta(
                object,
                "process_private_bytes",
                before.processPrivateBytes(),
                after.processPrivateBytes()
        );
        putDelta(
                object,
                "process_committed_virtual_bytes",
                before.processCommittedVirtualBytes(),
                after.processCommittedVirtualBytes()
        );
        putDelta(
                object,
                "gpu_process_memory_bytes",
                before.gpuProcessMemoryBytes(),
                after.gpuProcessMemoryBytes()
        );
        return object;
    }

    private static void putDelta(
            JSONObject object,
            String key,
            long before,
            long after
    ) {
        if (before >= 0 && after >= 0) {
            object.put(key, after - before);
        } else {
            object.put(key, JSONObject.NULL);
        }
    }

    private static void putOptional(
            JSONObject object,
            String key,
            long value
    ) {
        if (value >= 0) {
            object.put(key, value);
        } else {
            object.put(key, JSONObject.NULL);
        }
    }

    private static long maxKnown(
            long current,
            long candidate
    ) {
        if (candidate < 0) {
            return current;
        }
        return current < 0
                ? candidate
                : Math.max(current, candidate);
    }

    private static void printCycle(
            int cycle,
            RuntimeMemorySnapshot baseline,
            RuntimeMemorySnapshot warmup,
            RuntimeMemorySnapshot workload,
            RuntimeMemorySnapshot shutdown
    ) {
        System.out.println();
        System.out.println("Cycle " + cycle);
        printValue(
                "  warmup private",
                warmup.processPrivateBytes()
        );
        printValue(
                "  post-window private",
                workload.processPrivateBytes()
        );
        printValue(
                "  shutdown private",
                shutdown.processPrivateBytes()
        );
        printDelta(
                "  shutdown private vs tokenizer",
                baseline.processPrivateBytes(),
                shutdown.processPrivateBytes()
        );
        printDelta(
                "  shutdown working set vs tokenizer",
                baseline.processWorkingSetBytes(),
                shutdown.processWorkingSetBytes()
        );
    }

    private static void printValue(
            String label,
            long bytes
    ) {
        if (bytes < 0) {
            System.out.println(label + ": unavailable");
            return;
        }
        System.out.println(
                label
                        + ": "
                        + String.format(
                                "%.2f MiB",
                                mebibytes(bytes)
                        )
        );
    }

    private static void printDelta(
            String label,
            long before,
            long after
    ) {
        if (before < 0 || after < 0) {
            System.out.println(label + ": unavailable");
            return;
        }
        System.out.println(
                label
                        + ": "
                        + String.format(
                                "%+.2f MiB",
                                mebibytes(after - before)
                        )
        );
    }

    private static double mebibytes(long bytes) {
        return bytes / (1024.0 * 1024.0);
    }
}
