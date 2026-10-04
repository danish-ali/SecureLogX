package com.securelogx.log4j2;

import com.securelogx.api.MaskedResult;
import com.securelogx.api.MaskReasonCode;
import com.securelogx.validation.RuntimeMemorySnapshot;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.AbstractConfiguration;
import org.apache.logging.log4j.core.config.ConfigurationSource;
import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Repeated real-model LoggerContext lifecycle validation.
 *
 * Each cycle creates a new LoggerContext, obtains the production registry-owned
 * SecureMasker/ONNX session, performs real masking, stops the context, verifies
 * registry cleanup, and records native/process memory after shutdown.
 */
public final class SecureLogXContextLifecycleCheck {

    private static final String RAW_SSN = "123-45-6789";
    private static final int DEFAULT_CYCLES = 4;

    private SecureLogXContextLifecycleCheck() {
    }

    public static void main(String[] args) throws Exception {
        Path resultPath = args.length > 0
                ? Path.of(args[0])
                : Path.of(
                        "reports/log4j2-context-lifecycle/result.json"
                );
        int cycles = args.length > 1
                ? Integer.parseInt(args[1])
                : DEFAULT_CYCLES;

        if (cycles < 2) {
            throw new IllegalArgumentException(
                    "cycles must be at least 2"
            );
        }

        SecureMaskerRegistry.closeAll();

        RuntimeMemorySnapshot processStart =
                settledSnapshot("process-start");

        JSONArray cycleResults = new JSONArray();

        for (int cycle = 1; cycle <= cycles; cycle++) {
            cycleResults.put(runCycle(cycle));
        }

        RuntimeMemorySnapshot finalSnapshot =
                settledSnapshot("after-all-context-stops");

        if (SecureMaskerRegistry.ownedContextCount() != 0
                || SecureMaskerRegistry.ownedMaskerCount() != 0) {
            throw new IllegalStateException(
                    "Registry retained masker/context after lifecycle run"
            );
        }

        JSONObject result = new JSONObject();
        result.put(
                "status",
                "LOG4J2 REAL CONTEXT LIFECYCLE CHECK PASSED"
        );
        result.put("passed", true);
        result.put("cycles", cycles);
        result.put("cycle_results", cycleResults);
        result.put("process_start", processStart.toJson());
        result.put(
                "after_all_context_stops",
                finalSnapshot.toJson()
        );
        result.put(
                "final_memory_delta",
                memoryDelta(processStart, finalSnapshot)
        );
        result.put(
                "final_owned_contexts",
                SecureMaskerRegistry.ownedContextCount()
        );
        result.put(
                "final_owned_maskers",
                SecureMaskerRegistry.ownedMaskerCount()
        );
        result.put("sealed_challenge_inference", false);
        result.put("raw_payload_forwarded", false);

        addPrivateMemoryTrend(result, cycleResults);

        Path parent = resultPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(
                resultPath,
                result.toString(2) + System.lineSeparator()
        );

        printSummary(result, resultPath);
    }

    private static JSONObject runCycle(int cycle)
            throws Exception {
        LoggerContext context = new LoggerContext(
                "securelogx-redeploy-cycle-" + cycle
        );
        TestConfiguration configuration =
                new TestConfiguration(context);

        RuntimeMemorySnapshot beforeCreate =
                settledSnapshot(
                        "cycle-" + cycle + "-before-create"
                );

        try {
            SecureMaskingService service =
                    SecureMaskerRegistry.get(
                            "dev",
                            configuration
                    );

            if (SecureMaskerRegistry.ownedContextCount() != 1
                    || SecureMaskerRegistry.ownedMaskerCount() != 1) {
                throw new IllegalStateException(
                        "Unexpected registry ownership after create"
                );
            }

            RuntimeMemorySnapshot afterCreate =
                    settledSnapshot(
                            "cycle-" + cycle + "-after-create"
                    );

            List<MaskedResult> results = service.maskAll(
                    List.of(
                            "name=Jane Doe action=login ssn="
                                    + RAW_SSN
                                    + " cycle="
                                    + cycle
                    )
            );

            if (results.size() != 1) {
                throw new IllegalStateException(
                        "Unexpected lifecycle masking result count"
                );
            }

            MaskedResult masked = results.get(0);
            if (masked.failClosed()) {
                throw new IllegalStateException(
                        "Lifecycle inference failed closed: "
                                + masked.reasonCode()
                );
            }
            if (!masked.mlInvoked()) {
                throw new IllegalStateException(
                        "Lifecycle fixture did not exercise ML"
                );
            }
            if (masked.maskedText().contains(RAW_SSN)) {
                throw new IllegalStateException(
                        "Raw SSN survived lifecycle inference"
                );
            }
            if (masked.reasonCode()
                    != MaskReasonCode.ML_RESOLVED
                    && masked.reasonCode()
                    != MaskReasonCode.DETERMINISTIC_RESOLVED) {
                throw new IllegalStateException(
                        "Unexpected lifecycle masking reason: "
                                + masked.reasonCode()
                );
            }

            RuntimeMemorySnapshot afterInference =
                    settledSnapshot(
                            "cycle-" + cycle + "-after-inference"
                    );

            context.stop();

            if (SecureMaskerRegistry.ownedContextCount() != 0
                    || SecureMaskerRegistry.ownedMaskerCount() != 0) {
                throw new IllegalStateException(
                        "LoggerContext shutdown did not clear registry"
                );
            }

            RuntimeMemorySnapshot afterStop =
                    settledSnapshot(
                            "cycle-" + cycle + "-after-stop"
                    );

            JSONObject json = new JSONObject();
            json.put("cycle", cycle);
            json.put("before_create", beforeCreate.toJson());
            json.put("after_create", afterCreate.toJson());
            json.put(
                    "after_inference",
                    afterInference.toJson()
            );
            json.put("after_stop", afterStop.toJson());
            json.put(
                    "active_memory_delta",
                    memoryDelta(beforeCreate, afterInference)
            );
            json.put(
                    "post_stop_memory_delta",
                    memoryDelta(beforeCreate, afterStop)
            );
            json.put(
                    "owned_contexts_after_stop",
                    SecureMaskerRegistry.ownedContextCount()
            );
            json.put(
                    "owned_maskers_after_stop",
                    SecureMaskerRegistry.ownedMaskerCount()
            );
            json.put("ml_invoked", masked.mlInvoked());
            json.put(
                    "reason_code",
                    masked.reasonCode().name()
            );
            json.put("raw_payload_forwarded", false);
            return json;
        } finally {
            if (!context.isStopped()) {
                context.stop();
            }
        }
    }

    private static RuntimeMemorySnapshot settledSnapshot(
            String label
    ) throws InterruptedException {
        System.gc();
        Thread.sleep(500);
        System.gc();
        Thread.sleep(500);
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

    private static void addPrivateMemoryTrend(
            JSONObject result,
            JSONArray cycles
    ) {
        if (cycles.length() < 2) {
            result.put(
                    "post_stop_private_memory_trend_bytes",
                    JSONObject.NULL
            );
            return;
        }

        JSONObject first = cycles.getJSONObject(0)
                .getJSONObject("after_stop");
        JSONObject last = cycles.getJSONObject(
                cycles.length() - 1
        ).getJSONObject("after_stop");

        if (first.isNull("process_private_bytes")
                || last.isNull("process_private_bytes")) {
            result.put(
                    "post_stop_private_memory_trend_bytes",
                    JSONObject.NULL
            );
            return;
        }

        result.put(
                "post_stop_private_memory_trend_bytes",
                last.getLong("process_private_bytes")
                        - first.getLong("process_private_bytes")
        );
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
            Path resultPath
    ) {
        JSONArray cycles =
                result.getJSONArray("cycle_results");

        System.out.println();
        System.out.println(
                "LOG4J2 REAL CONTEXT LIFECYCLE CHECK PASSED"
        );

        for (int i = 0; i < cycles.length(); i++) {
            JSONObject cycle = cycles.getJSONObject(i);
            JSONObject afterStop =
                    cycle.getJSONObject("after_stop");
            System.out.println(
                    "Cycle "
                            + cycle.getInt("cycle")
                            + " post-stop private="
                            + optionalMiB(
                                    afterStop,
                                    "process_private_bytes"
                            )
                            + " MiB, workingSet="
                            + optionalMiB(
                                    afterStop,
                                    "process_working_set_bytes"
                            )
                            + " MiB, registry="
                            + cycle.getInt(
                                    "owned_maskers_after_stop"
                            )
            );
        }

        System.out.println(
                "Post-stop private-memory trend: "
                        + optionalDeltaMiB(
                                result,
                                "post_stop_private_memory_trend_bytes"
                        )
                        + " MiB"
        );
        System.out.println(
                "Final registry contexts/maskers: "
                        + result.getInt("final_owned_contexts")
                        + "/"
                        + result.getInt("final_owned_maskers")
        );
        System.out.println("Raw payload forwarded: false");
        System.out.println("Result: " + resultPath);
    }

    private static String optionalMiB(
            JSONObject object,
            String key
    ) {
        if (object.isNull(key)) {
            return "n/a";
        }
        return String.format(
                "%.2f",
                object.getLong(key)
                        / (1024.0 * 1024.0)
        );
    }

    private static String optionalDeltaMiB(
            JSONObject object,
            String key
    ) {
        if (object.isNull(key)) {
            return "n/a";
        }
        return String.format(
                "%.2f",
                object.getLong(key)
                        / (1024.0 * 1024.0)
        );
    }

    private static final class TestConfiguration
            extends AbstractConfiguration {

        private TestConfiguration(LoggerContext context) {
            super(
                    context,
                    ConfigurationSource.NULL_SOURCE
            );
        }

        @Override
        protected void doConfigure() {
            // Registry ownership only; no destination appenders are required.
        }
    }
}
