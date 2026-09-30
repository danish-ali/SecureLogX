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
 * Production-oriented memory benchmark for the H1 runtime.
 *
 * The benchmark intentionally reports both JVM-managed memory and whole-process
 * memory because ONNX Runtime owns native allocations outside the Java heap.
 *
 * This benchmark does not impose release thresholds yet. Its first purpose is
 * to establish the baseline and identify retained-growth behavior. Release
 * budgets should be set from repeated measurements on representative hardware.
 */
public final class RuntimeMemoryBenchmark {

    private static final int DEFAULT_SAMPLE_PER_SCENARIO = 160;
    private static final int DEFAULT_ITERATIONS = 3;
    private static final int BATCH_SIZE = 32;
    private static final int WARMUP_RECORDS = 32;

    private RuntimeMemoryBenchmark() {
    }

    public static void main(String[] args) throws Exception {
        Path resultPath = args.length > 0
                ? Path.of(args[0])
                : Path.of("reports/runtime-memory/result.json");
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

        JSONArray checkpoints = new JSONArray();
        RuntimeMemorySnapshot processStart =
                settledSnapshot("process-start");
        checkpoints.put(processStart.toJson());

        ParallelTokenizer tokenizer = new ParallelTokenizer(
                config.getTokenizerPath(),
                config.getMaxSequenceLength()
        );
        RuntimeMemorySnapshot afterTokenizer =
                settledSnapshot("after-tokenizer");
        checkpoints.put(afterTokenizer.toJson());

        Map<String, List<String>> sampled =
                sampledScenarios(samplePerScenario);
        List<String> warmup = sampled.get("A").subList(
                0,
                Math.min(WARMUP_RECORDS, sampled.get("A").size())
        );

        ONNXDynamicInferenceEngine engine = null;
        RuntimeMemorySnapshot afterEngineInit = null;
        RuntimeMemorySnapshot afterWarmup = null;
        RuntimeMemorySnapshot afterWarmupSettled = null;
        RuntimeMemorySnapshot afterShutdown = null;
        RuntimeMemorySnapshot afterShutdownSettled = null;

        JSONArray scenarioResults = new JSONArray();
        int sequence = 0;

        try {
            engine = new ONNXDynamicInferenceEngine(
                    config.getModelPath(),
                    config
            );

            afterEngineInit = RuntimeMemorySnapshot.capture(
                    "after-engine-init"
            );
            checkpoints.put(afterEngineInit.toJson());

            List<LogEvent> warmupEvents = toEvents(
                    warmup,
                    sequence
            );
            sequence += warmupEvents.size();
            engine.runBatch(tokenizer, warmupEvents);

            afterWarmup = RuntimeMemorySnapshot.capture(
                    "after-warmup"
            );
            checkpoints.put(afterWarmup.toJson());

            afterWarmupSettled =
                    settledSnapshot("after-warmup-settled");
            checkpoints.put(afterWarmupSettled.toJson());

            for (Map.Entry<String, List<String>> scenario
                    : sampled.entrySet()) {
                String id = scenario.getKey();
                List<String> messages = scenario.getValue();

                RuntimeMemorySnapshot before =
                        settledSnapshot("scenario-" + id + "-before");
                checkpoints.put(before.toJson());

                HybridRuntimeStats statsBefore =
                        engine.getHybridRuntimeStats();
                JSONArray iterationSnapshots = new JSONArray();

                for (int iteration = 1;
                     iteration <= iterations;
                     iteration++) {
                    for (int start = 0;
                         start < messages.size();
                         start += BATCH_SIZE) {
                        int end = Math.min(
                                start + BATCH_SIZE,
                                messages.size()
                        );
                        List<String> batch =
                                messages.subList(start, end);
                        List<LogEvent> events =
                                toEvents(batch, sequence);
                        sequence += events.size();

                        List<String> outputs =
                                engine.runBatch(tokenizer, events);
                        if (outputs.size() != events.size()) {
                            throw new IllegalStateException(
                                    "Memory benchmark output count mismatch"
                            );
                        }
                        for (String output : outputs) {
                            if (output.contains(
                                    "[SECURELOGX_REDACTED_PROCESSING_FAILURE]"
                            )) {
                                throw new IllegalStateException(
                                        "Memory benchmark entered fail-closed "
                                                + "fallback in scenario "
                                                + id
                                );
                            }
                        }
                    }

                    RuntimeMemorySnapshot iterationSnapshot =
                            RuntimeMemorySnapshot.capture(
                                    "scenario-"
                                            + id
                                            + "-iteration-"
                                            + iteration
                            );
                    checkpoints.put(iterationSnapshot.toJson());
                    iterationSnapshots.put(
                            iterationSnapshot.toJson()
                    );
                }

                RuntimeMemorySnapshot after =
                        settledSnapshot("scenario-" + id + "-settled");
                checkpoints.put(after.toJson());

                HybridRuntimeStats statsAfter =
                        engine.getHybridRuntimeStats();

                JSONObject scenarioJson = new JSONObject();
                scenarioJson.put("scenario", id);
                scenarioJson.put(
                        "sample_records",
                        messages.size()
                );
                scenarioJson.put("iterations", iterations);
                scenarioJson.put(
                        "total_records_processed",
                        (long) messages.size() * iterations
                );
                scenarioJson.put("before", before.toJson());
                scenarioJson.put(
                        "iteration_snapshots",
                        iterationSnapshots
                );
                scenarioJson.put("after_settled", after.toJson());
                scenarioJson.put(
                        "retained_growth",
                        deltaJson(before, after)
                );
                scenarioJson.put(
                        "runtime_delta",
                        runtimeDelta(statsBefore, statsAfter)
                );
                scenarioResults.put(scenarioJson);

                printScenario(id, before, after);
            }
        } finally {
            if (engine != null) {
                engine.shutdown();
            }

            afterShutdown =
                    RuntimeMemorySnapshot.capture("after-engine-shutdown");
            checkpoints.put(afterShutdown.toJson());

            afterShutdownSettled =
                    settledSnapshot("after-engine-shutdown-settled");
            checkpoints.put(afterShutdownSettled.toJson());
        }

        JSONObject result = new JSONObject();
        result.put(
                "status",
                "H1 RUNTIME MEMORY BASELINE COMPLETE"
        );
        result.put("passed", true);
        result.put(
                "memory_model",
                "JVM memory and whole-process memory are reported separately; "
                        + "whole-process metrics include native ONNX/JNI/JVM "
                        + "allocations that are not visible in Java heap usage."
        );
        result.put("sample_per_scenario", samplePerScenario);
        result.put("iterations", iterations);
        result.put("batch_size", BATCH_SIZE);
        result.put(
                "gpu_inference_requested",
                config.isGpuInferenceEnabled()
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

        result.put("process_start", processStart.toJson());
        result.put("after_tokenizer", afterTokenizer.toJson());
        result.put("after_engine_init", afterEngineInit.toJson());
        result.put("after_warmup", afterWarmup.toJson());
        result.put(
                "after_warmup_settled",
                afterWarmupSettled.toJson()
        );
        result.put("scenarios", scenarioResults);
        result.put("after_shutdown", afterShutdown.toJson());
        result.put(
                "after_shutdown_settled",
                afterShutdownSettled.toJson()
        );

        result.put(
                "engine_init_delta",
                deltaJson(afterTokenizer, afterEngineInit)
        );
        result.put(
                "resident_runtime_delta",
                deltaJson(afterTokenizer, afterWarmupSettled)
        );
        result.put(
                "post_shutdown_delta_vs_tokenizer",
                deltaJson(afterTokenizer, afterShutdownSettled)
        );

        result.put("checkpoints", checkpoints);
        result.put(
                "release_policy",
                new JSONArray(List.of(
                        "Do not use JVM heap alone as the ONNX memory limit.",
                        "Track whole-process working set/private bytes or equivalent OS metrics.",
                        "Track JVM heap, non-heap, and direct buffers separately.",
                        "Track GPU process memory separately when CUDA is active.",
                        "Treat repeated post-GC/post-warmup growth as a leak signal.",
                        "Set hard release thresholds only after repeated baseline runs on representative deployment hardware."
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
        System.out.println("H1 RUNTIME MEMORY BASELINE COMPLETE");
        printSnapshot("Process start", processStart);
        printSnapshot("After tokenizer", afterTokenizer);
        printSnapshot("After engine init", afterEngineInit);
        printSnapshot("After warmup settled", afterWarmupSettled);
        printSnapshot("After shutdown settled", afterShutdownSettled);
        System.out.println("Result: " + resultPath);
    }

    private static Map<String, List<String>> sampledScenarios(
            int samplePerScenario
    ) {
        Map<String, List<String>> full =
                ProductionRoutingBenchmark.scenarioMessages();
        Map<String, List<String>> result = new LinkedHashMap<>();

        for (String id : List.of("A", "B", "C", "D")) {
            List<String> source = full.get(id);
            if (source == null) {
                throw new IllegalStateException(
                        "Missing production scenario " + id
                );
            }
            result.put(id, sampleEvenly(source, samplePerScenario));
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

        List<String> sample = new ArrayList<>(requested);
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

    private static List<LogEvent> toEvents(
            List<String> messages,
            int startingSequence
    ) {
        List<LogEvent> events = new ArrayList<>(messages.size());
        int sequence = startingSequence;

        for (String message : messages) {
            events.add(
                    new LogEvent(
                            message,
                            LogLevel.SECURE,
                            false,
                            "runtime-memory-benchmark",
                            ++sequence
                    )
            );
        }
        return events;
    }

    private static RuntimeMemorySnapshot settledSnapshot(
            String label
    ) throws InterruptedException {
        System.gc();
        Thread.sleep(400);
        return RuntimeMemorySnapshot.capture(label);
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
                "process_committed_virtual_bytes",
                before.processCommittedVirtualBytes(),
                after.processCommittedVirtualBytes()
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
                after.mlInferenceItems() - before.mlInferenceItems()
        );
        object.put(
                "windowed_ml_records",
                after.windowedMlItems() - before.windowedMlItems()
        );
        object.put(
                "ml_inference_windows",
                after.mlInferenceWindows() - before.mlInferenceWindows()
        );
        object.put(
                "onnx_inference_calls",
                after.onnxInferenceCalls() - before.onnxInferenceCalls()
        );
        object.put(
                "max_inference_windows_per_call_observed",
                after.maxInferenceWindowsPerCallObserved()
        );
        object.put(
                "windowed_onnx_inference_calls",
                after.windowedOnnxInferenceCalls()
                        - before.windowedOnnxInferenceCalls()
        );
        object.put(
                "max_windowed_inference_windows_per_call_observed",
                after.maxWindowedInferenceWindowsPerCallObserved()
        );
        object.put(
                "truncated_fail_closed_records",
                after.truncatedFailClosedItems()
                        - before.truncatedFailClosedItems()
        );
        return object;
    }

    private static void printScenario(
            String id,
            RuntimeMemorySnapshot before,
            RuntimeMemorySnapshot after
    ) {
        System.out.println();
        System.out.println("Scenario " + id + " retained growth");
        printDelta(
                "  heap used",
                before.heapUsedBytes(),
                after.heapUsedBytes()
        );
        printDelta(
                "  process working set",
                before.processWorkingSetBytes(),
                after.processWorkingSetBytes()
        );
        printDelta(
                "  process private",
                before.processPrivateBytes(),
                after.processPrivateBytes()
        );
        printDelta(
                "  committed virtual",
                before.processCommittedVirtualBytes(),
                after.processCommittedVirtualBytes()
        );
        printDelta(
                "  GPU process",
                before.gpuProcessMemoryBytes(),
                after.gpuProcessMemoryBytes()
        );
    }

    private static void printSnapshot(
            String label,
            RuntimeMemorySnapshot snapshot
    ) {
        System.out.println();
        System.out.println(label);
        System.out.println(
                "  heap used: "
                        + mebibytes(snapshot.heapUsedBytes())
                        + " MiB"
        );
        printValue(
                "  process working set",
                snapshot.processWorkingSetBytes()
        );
        printValue(
                "  process private",
                snapshot.processPrivateBytes()
        );
        printValue(
                "  committed virtual",
                snapshot.processCommittedVirtualBytes()
        );
        printValue(
                "  GPU process",
                snapshot.gpuProcessMemoryBytes()
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

        double delta = mebibytes(after - before);
        System.out.println(
                label
                        + ": "
                        + String.format("%+.2f", delta)
                        + " MiB"
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
                label + ": " + String.format("%.2f", mebibytes(bytes))
                        + " MiB"
        );
    }

    private static double mebibytes(long bytes) {
        return bytes / (1024.0 * 1024.0);
    }
}
