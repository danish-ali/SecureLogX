package com.securelogx.validation;

import com.securelogx.detection.DetectionEvidence;
import com.securelogx.detection.DeterministicScanResult;
import com.securelogx.detection.DeterministicSensitiveDataDetector;
import com.securelogx.detection.ResolutionAction;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dataset-wide security audit for the deterministic ML-bypass gate.
 *
 * The NER repository is treated strictly as a read-only source of the frozen
 * labeled datasets. Hybrid logic and audit implementation live in SecureLogX.
 *
 * Audited datasets:
 * - data/split/dev.jsonl
 * - data/ml_v1_3/real_structure/dev_challenge.jsonl
 * - data/split/test.jsonl
 *
 * The sealed challenge is never accessed.
 */
public final class HybridGateDatasetAudit {

    private static final int MAX_FAILURE_SAMPLES = 20;
    private static final Pattern KEY_VALUE_SHAPE = Pattern.compile(
            "([A-Za-z][A-Za-z0-9_.-]{1,40})\\s*[:=]"
    );

    private static final Map<String, String> DATASETS = Map.of(
            "standard_dev",
            "data/split/dev.jsonl",
            "v1_3_challenge_dev",
            "data/ml_v1_3/real_structure/dev_challenge.jsonl",
            "original_test_regression",
            "data/split/test.jsonl"
    );

    private HybridGateDatasetAudit() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException(
                    "Usage: HybridGateDatasetAudit <SecureLogX-NER-root> <result.json>"
            );
        }

        Path nerRoot = Path.of(args[0]).toAbsolutePath().normalize();
        Path resultPath = Path.of(args[1]);

        DeterministicSensitiveDataDetector detector =
                new DeterministicSensitiveDataDetector();

        AuditTotals totals = new AuditTotals();
        Map<String, SourceStats> bySource = new LinkedHashMap<>();
        List<String> failureSamples = new ArrayList<>();

        for (Map.Entry<String, String> dataset : DATASETS.entrySet()) {
            String source = dataset.getKey();
            Path path = nerRoot.resolve(dataset.getValue()).normalize();

            if (!path.startsWith(nerRoot)) {
                throw new IllegalStateException(
                        "Dataset path escaped NER root: " + path
                );
            }
            if (!Files.exists(path)) {
                throw new IllegalStateException(
                        "Required NER dataset is missing: " + path
                );
            }

            SourceStats sourceStats = new SourceStats();
            bySource.put(source, sourceStats);

            auditJsonl(
                    source,
                    path,
                    detector,
                    totals,
                    sourceStats,
                    failureSamples
            );
        }

        boolean safetyPassed = totals.bypassUncoveredGold == 0
                && totals.deterministicOvermask == 0
                && totals.allowGoldConflict == 0;
        boolean bypassDemonstrated = totals.bypassRecords > 0;

        JSONObject result = new JSONObject();
        result.put(
                "status",
                safetyPassed
                        ? (
                                bypassDemonstrated
                                        ? "HYBRID GATE SAFETY PASSED; BYPASS DEMONSTRATED"
                                        : "HYBRID GATE SAFETY PASSED; BYPASS NOT YET DEMONSTRATED"
                        )
                        : "HYBRID GATE DATASET AUDIT FAILED"
        );
        result.put("passed", safetyPassed);
        result.put("safety_passed", safetyPassed);
        result.put("bypass_demonstrated", bypassDemonstrated);
        result.put("efficiency_ready", safetyPassed && bypassDemonstrated);
        result.put("records", totals.records);
        result.put("gold_spans", totals.goldSpans);
        result.put("bypass_records", totals.bypassRecords);
        result.put("ml_records", totals.mlRecords);
        result.put(
                "bypass_rate",
                totals.records == 0
                        ? 0.0
                        : (double) totals.bypassRecords / totals.records
        );
        result.put("deterministic_masks", totals.deterministicMasks);
        result.put("deterministic_allows", totals.deterministicAllows);
        result.put("bypass_uncovered_gold", totals.bypassUncoveredGold);
        result.put("deterministic_overmask", totals.deterministicOvermask);
        result.put("allow_gold_conflict", totals.allowGoldConflict);
        result.put("sealed_challenge_inference", false);
        result.put("sealed_challenge_accessed", false);
        result.put("model_inference", false);
        result.put("ner_repository_mutated", false);
        result.put("gate_reasons", countsJson(totals.gateReasons));
        result.put("record_shapes", countsJson(totals.recordShapes));

        JSONObject sources = new JSONObject();
        for (Map.Entry<String, SourceStats> entry : bySource.entrySet()) {
            SourceStats stats = entry.getValue();
            JSONObject value = new JSONObject();
            value.put("records", stats.records);
            value.put("gold_spans", stats.goldSpans);
            value.put("bypass_records", stats.bypassRecords);
            value.put("ml_records", stats.mlRecords);
            value.put(
                    "bypass_rate",
                    stats.records == 0
                            ? 0.0
                            : (double) stats.bypassRecords / stats.records
            );
            value.put("deterministic_masks", stats.deterministicMasks);
            value.put("deterministic_allows", stats.deterministicAllows);
            value.put(
                    "bypass_uncovered_gold",
                    stats.bypassUncoveredGold
            );
            value.put(
                    "deterministic_overmask",
                    stats.deterministicOvermask
            );
            value.put(
                    "allow_gold_conflict",
                    stats.allowGoldConflict
            );
            value.put("gate_reasons", countsJson(stats.gateReasons));
            value.put("record_shapes", countsJson(stats.recordShapes));
            sources.put(entry.getKey(), value);
        }

        result.put("by_source", sources);
        result.put("failure_samples", new JSONArray(failureSamples));

        Path parent = resultPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(
                resultPath,
                result.toString(2) + System.lineSeparator(),
                StandardCharsets.UTF_8
        );

        System.out.println(result.getString("status"));
        System.out.println("Records: " + totals.records);
        System.out.println("Gold spans: " + totals.goldSpans);
        System.out.println(
                "Deterministic bypass: "
                        + totals.bypassRecords
                        + "/"
                        + totals.records
                        + " ("
                        + String.format(
                                "%.2f%%",
                                100.0 * result.getDouble("bypass_rate")
                        )
                        + ")"
        );
        System.out.println(
                "Bypass uncovered gold: " + totals.bypassUncoveredGold
        );
        System.out.println(
                "Deterministic overmask: " + totals.deterministicOvermask
        );
        System.out.println(
                "ALLOW/gold conflicts: " + totals.allowGoldConflict
        );
        System.out.println("Top ML gate reasons:");
        printTopCounts(totals.gateReasons, 12);
        System.out.println("Record shapes:");
        printTopCounts(totals.recordShapes, 10);
        System.out.println("Result: " + resultPath);

        if (!safetyPassed) {
            throw new IllegalStateException(
                    "Hybrid deterministic gate failed dataset security audit"
            );
        }

        if (!bypassDemonstrated) {
            System.out.println(
                    "NOTE: safety passed, but this corpus does not yet demonstrate "
                            + "a deterministic bypass performance benefit."
            );
        }
    }

    private static void auditJsonl(
            String source,
            Path path,
            DeterministicSensitiveDataDetector detector,
            AuditTotals totals,
            SourceStats sourceStats,
            List<String> failureSamples
    ) throws Exception {
        try (BufferedReader reader = Files.newBufferedReader(
                path,
                StandardCharsets.UTF_8
        )) {
            String line;
            int sourceIndex = 0;

            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }

                JSONObject item = new JSONObject(trimmed);
                String text = item.getString("text");
                List<GoldSpan> gold = goldSpans(
                        item.optJSONArray("entities")
                );

                auditRecord(
                        source,
                        sourceIndex,
                        text,
                        gold,
                        detector,
                        totals,
                        sourceStats,
                        failureSamples
                );

                sourceIndex++;
            }
        }
    }

    private static void auditRecord(
            String source,
            int sourceIndex,
            String text,
            List<GoldSpan> gold,
            DeterministicSensitiveDataDetector detector,
            AuditTotals totals,
            SourceStats sourceStats,
            List<String> failureSamples
    ) {
        totals.records++;
        sourceStats.records++;

        totals.goldSpans += gold.size();
        sourceStats.goldSpans += gold.size();

        DeterministicScanResult scan = detector.scan(text);

        increment(totals.gateReasons, scan.gateReason());
        increment(sourceStats.gateReasons, scan.gateReason());

        String shape = recordShape(text);
        increment(totals.recordShapes, shape);
        increment(sourceStats.recordShapes, shape);

        if (scan.requiresMl()) {
            totals.mlRecords++;
            sourceStats.mlRecords++;
        } else {
            totals.bypassRecords++;
            sourceStats.bypassRecords++;
        }

        for (DetectionEvidence evidence : scan.evidence()) {
            if (evidence.action() == ResolutionAction.MASK) {
                totals.deterministicMasks++;
                sourceStats.deterministicMasks++;

                if (!overlapsAnyGold(evidence, gold)) {
                    totals.deterministicOvermask++;
                    sourceStats.deterministicOvermask++;
                    addFailure(
                            failureSamples,
                            source,
                            sourceIndex,
                            "deterministic MASK on gold-O span",
                            evidence,
                            text
                    );
                }
            } else if (evidence.action() == ResolutionAction.ALLOW) {
                totals.deterministicAllows++;
                sourceStats.deterministicAllows++;

                if (overlapsAnyGold(evidence, gold)) {
                    totals.allowGoldConflict++;
                    sourceStats.allowGoldConflict++;
                    addFailure(
                            failureSamples,
                            source,
                            sourceIndex,
                            "deterministic ALLOW overlaps gold sensitive span",
                            evidence,
                            text
                    );
                }
            }
        }

        if (!scan.requiresMl()) {
            for (GoldSpan span : gold) {
                if (!fullyCoveredByMask(span, scan.evidence())) {
                    totals.bypassUncoveredGold++;
                    sourceStats.bypassUncoveredGold++;

                    if (failureSamples.size() < MAX_FAILURE_SAMPLES) {
                        failureSamples.add(
                                source
                                        + "#"
                                        + sourceIndex
                                        + " bypass leaves gold "
                                        + span.label
                                        + " ["
                                        + span.start
                                        + ","
                                        + span.end
                                        + ") uncovered"
                        );
                    }
                }
            }
        }
    }

    private static List<GoldSpan> goldSpans(JSONArray array) {
        List<GoldSpan> result = new ArrayList<>();
        if (array == null) {
            return result;
        }

        for (int i = 0; i < array.length(); i++) {
            Object raw = array.get(i);

            if (raw instanceof JSONArray tuple) {
                result.add(
                        new GoldSpan(
                                tuple.getInt(0),
                                tuple.getInt(1),
                                tuple.getString(2)
                        )
                );
            } else if (raw instanceof JSONObject object) {
                result.add(
                        new GoldSpan(
                                object.getInt("start"),
                                object.getInt("end"),
                                object.getString("label")
                        )
                );
            } else {
                throw new IllegalStateException(
                        "Unsupported entity representation at index " + i
                );
            }
        }

        return result;
    }

    private static boolean overlapsAnyGold(
            DetectionEvidence evidence,
            List<GoldSpan> gold
    ) {
        for (GoldSpan span : gold) {
            if (Math.max(evidence.start(), span.start)
                    < Math.min(evidence.end(), span.end)) {
                return true;
            }
        }
        return false;
    }

    private static boolean fullyCoveredByMask(
            GoldSpan gold,
            List<DetectionEvidence> evidence
    ) {
        for (DetectionEvidence item : evidence) {
            if (item.action() == ResolutionAction.MASK
                    && item.start() <= gold.start
                    && item.end() >= gold.end) {
                return true;
            }
        }
        return false;
    }

    private static void increment(
            Map<String, Long> counts,
            String key
    ) {
        counts.merge(key, 1L, Long::sum);
    }

    private static String recordShape(String text) {
        String trimmed = text.trim();
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            return "json";
        }
        if (text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0) {
            return "multiline";
        }
        Matcher matcher = KEY_VALUE_SHAPE.matcher(text);
        if (matcher.find()) {
            return "key_value_like";
        }
        return "free_text";
    }

    private static JSONObject countsJson(Map<String, Long> counts) {
        JSONObject object = new JSONObject();
        List<Map.Entry<String, Long>> entries =
                new ArrayList<>(counts.entrySet());
        entries.sort(
                (left, right) -> {
                    int byCount = Long.compare(
                            right.getValue(),
                            left.getValue()
                    );
                    return byCount != 0
                            ? byCount
                            : left.getKey().compareTo(right.getKey());
                }
        );
        for (Map.Entry<String, Long> entry : entries) {
            object.put(entry.getKey(), entry.getValue());
        }
        return object;
    }

    private static void printTopCounts(
            Map<String, Long> counts,
            int limit
    ) {
        List<Map.Entry<String, Long>> entries =
                new ArrayList<>(counts.entrySet());
        entries.sort(
                (left, right) -> {
                    int byCount = Long.compare(
                            right.getValue(),
                            left.getValue()
                    );
                    return byCount != 0
                            ? byCount
                            : left.getKey().compareTo(right.getKey());
                }
        );

        int shown = 0;
        for (Map.Entry<String, Long> entry : entries) {
            if (shown++ >= limit) {
                break;
            }
            System.out.println(
                    "  " + entry.getValue() + "  " + entry.getKey()
            );
        }
    }

    private static void addFailure(
            List<String> samples,
            String source,
            int sourceIndex,
            String problem,
            DetectionEvidence evidence,
            String text
    ) {
        if (samples.size() >= MAX_FAILURE_SAMPLES) {
            return;
        }

        String raw = text.substring(evidence.start(), evidence.end());
        samples.add(
                source
                        + "#"
                        + sourceIndex
                        + " "
                        + problem
                        + " "
                        + evidence.entityType()
                        + " ["
                        + evidence.start()
                        + ","
                        + evidence.end()
                        + ") raw="
                        + raw
                        + " reason="
                        + evidence.reason()
        );
    }

    private record GoldSpan(int start, int end, String label) {
    }

    private static final class AuditTotals {
        private long records;
        private long goldSpans;
        private long bypassRecords;
        private long mlRecords;
        private long deterministicMasks;
        private long deterministicAllows;
        private long bypassUncoveredGold;
        private long deterministicOvermask;
        private long allowGoldConflict;
        private final Map<String, Long> gateReasons = new LinkedHashMap<>();
        private final Map<String, Long> recordShapes = new LinkedHashMap<>();
    }

    private static final class SourceStats {
        private long records;
        private long goldSpans;
        private long bypassRecords;
        private long mlRecords;
        private long deterministicMasks;
        private long deterministicAllows;
        private long bypassUncoveredGold;
        private long deterministicOvermask;
        private long allowGoldConflict;
        private final Map<String, Long> gateReasons = new LinkedHashMap<>();
        private final Map<String, Long> recordShapes = new LinkedHashMap<>();
    }
}
