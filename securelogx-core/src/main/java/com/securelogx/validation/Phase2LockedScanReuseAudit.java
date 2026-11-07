package com.securelogx.validation;

import com.securelogx.detection.DeterministicScanResult;
import com.securelogx.detection.HybridMaskingPipeline;
import com.securelogx.detection.OrganizationRuleRegistry;
import com.securelogx.detection.PreQueueDeterministicRouter;
import com.securelogx.model.LogEvent;
import com.securelogx.model.LogLevel;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Read-only locked-dataset Phase 2 scan-handoff parity audit.
 *
 * Does NOT infer full ML/redaction accuracy. Run the independent high-risk
 * gold-span audit and real-ONNX masked-output A/B separately.
 * Only counters, corpus SHA-256 and identifiers appear in the report.
 */
public final class Phase2LockedScanReuseAudit {
    private static final Map<String, String> CORPUS = Map.of(
            "standard_dev", "data/split/dev.jsonl",
            "challenge_dev", "data/ml_v1_3/real_structure/dev_challenge.jsonl",
            "original_test", "data/split/test.jsonl"
    );
    private static final int LOCKED_RECORD_COUNT = 6463;

    private Phase2LockedScanReuseAudit() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2 && args.length != 4) {
            throw new IllegalArgumentException(
                    "Usage: Phase2LockedScanReuseAudit <NER-root> <local-report.json> [organization-id approved-rules-file]"
            );
        }
        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        Path report = Path.of(args[1]).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("NER dataset root unavailable");
        }

        OrganizationRuleRegistry approved = args.length == 4
                ? OrganizationRuleRegistry.load(args[2], args[3])
                : OrganizationRuleRegistry.disabled();
        HybridMaskingPipeline pipeline = new HybridMaskingPipeline(approved);
        PreQueueDeterministicRouter router = new PreQueueDeterministicRouter(pipeline);
        long records = 0;
        long callerScansReused = 0;
        long workerRescansRequired = 0;
        long directFields = 0;
        Map<String, JSONObject> sources = new LinkedHashMap<>();

        for (Map.Entry<String, String> pair : CORPUS.entrySet()) {
            Path input = root.resolve(pair.getValue()).normalize();
            if (!input.startsWith(root) || !Files.isRegularFile(input)) {
                throw new IllegalStateException("Missing locked corpus input: " + pair.getKey());
            }
            long perSource = 0;
            try (BufferedReader reader = Files.newBufferedReader(
                    input, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    JSONObject data = new JSONObject(line);
                    String raw = data.getString("text");
                    // One immutable event per corpus record, exactly as the
                    // single-field Log4j masking entry point would construct.
                    LogEvent event = new LogEvent(
                            raw, LogLevel.INFO, false, "phase2-corpus-audit",
                            ++records, 1_780_000_000_000L);
                    perSource++;
                    DeterministicScanResult baseline = pipeline.scan(raw);
                    var plan = router.route(List.of(event));
                    plan.reusableScans().validate(pipeline, plan.unresolvedFields());
                    if (plan.unresolvedFields().isEmpty()) {
                        if (baseline.requiresMl() || plan.deterministicFields() != 1) {
                            throw new IllegalStateException(
                                    "Unexpected deterministic bypass at " + pair.getKey() + ":" + perSource);
                        }
                        String reference = pipeline.maskWithoutMl(
                                raw, baseline, event.shouldShowLastFour());
                        if (!reference.equals(plan.merge(List.of()).get(0).maskedText())) {
                            throw new IllegalStateException(
                                    "Deterministic output parity mismatch at " + pair.getKey() + ":" + perSource);
                        }
                        directFields++;
                    } else {
                        if (plan.unresolvedFields().size() != 1
                                || plan.unresolvedFields().get(0) != event) {
                            throw new IllegalStateException(
                                    "Worker identity mismatch at " + pair.getKey() + ":" + perSource);
                        }
                        Optional<DeterministicScanResult> supplied =
                                plan.reusableScans().scanFor(pipeline, event, 0);
                        if (supplied.isPresent()) {
                            if (!baseline.requiresMl() || !baseline.equals(supplied.get())) {
                                throw new IllegalStateException(
                                        "Reused scan parity mismatch at " + pair.getKey() + ":" + perSource);
                            }
                            callerScansReused++;
                        } else {
                            // Long, multiline or otherwise caller-ineligible
                            // records must still be scanned by the worker.
                            if (!baseline.equals(pipeline.scan(raw))) {
                                throw new IllegalStateException(
                                        "Worker rescan parity mismatch at " + pair.getKey() + ":" + perSource);
                            }
                            workerRescansRequired++;
                        }
                    }
                }
            }
            sources.put(pair.getKey(), new JSONObject()
                    .put("records", perSource)
                    .put("sha256", sha256(input)));
        }

        if (records != LOCKED_RECORD_COUNT) {
            throw new IllegalStateException(
                    "Locked corpus count mismatch, observed " + records);
        }
        if (directFields + callerScansReused + workerRescansRequired != records) {
            throw new IllegalStateException("Not all corpus records accounted for");
        }

        JSONObject out = new JSONObject()
                .put("kind", "phase2-locked-scan-handoff-audit")
                .put("passed", true)
                .put("record_count", records)
                .put("caller_scans_reused", callerScansReused)
                .put("worker_rescans_required", workerRescansRequired)
                .put("deterministic_direct_fields", directFields)
                .put("reuse_policy_identity_checked", true)
                .put("original_event_identity_and_order_checked", true)
                .put("datasets", new JSONObject(sources))
                .put("approved_policy_enabled", args.length == 4)
                .put("full_onnx_output_parity", "NOT_TESTED")
                .put("gold_span_privacy_accuracy", "NOT_TESTED_BY_THIS_AUDIT");

        Path parent = report.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(report, out.toString(2) + System.lineSeparator(),
                StandardCharsets.UTF_8);
        System.out.println("PHASE 2 LOCKED SCAN REUSE PARITY PASSED: " + records
                + " records, " + callerScansReused + " eligible reused scans, "
                + workerRescansRequired + " worker rescan fallbacks.");
        System.out.println("Payload-free report: " + report);
    }

    private static String sha256(Path input) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream stream = Files.newInputStream(input)) {
            byte[] chunk = new byte[8192];
            int read;
            while ((read = stream.read(chunk)) != -1) {
                digest.update(chunk, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
