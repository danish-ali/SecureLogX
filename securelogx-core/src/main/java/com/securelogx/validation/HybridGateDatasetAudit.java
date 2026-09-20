package com.securelogx.validation;

import com.securelogx.detection.DetectionEvidence;
import com.securelogx.detection.DeterministicScanResult;
import com.securelogx.detection.DeterministicSensitiveDataDetector;
import com.securelogx.detection.ResolutionAction;
import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dataset-wide security audit for the deterministic ML-bypass gate.
 *
 * A bypassed record is only acceptable when every gold sensitive span is fully
 * covered by deterministic MASK evidence. Deterministic MASK evidence may not
 * target gold-O text, and deterministic ALLOW evidence may not overlap any
 * gold sensitive span.
 */
public final class HybridGateDatasetAudit {

    private static final int MAX_FAILURE_SAMPLES = 20;

    private HybridGateDatasetAudit() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException(
                    "Usage: HybridGateDatasetAudit <gold-fixture.json> <result.json>"
            );
        }

        Path fixturePath = Path.of(args[0]);
        Path resultPath = Path.of(args[1]);
        JSONObject fixture = new JSONObject(Files.readString(fixturePath));

        if (fixture.optBoolean("sealed_challenge_accessed", true)) {
            throw new IllegalStateException(
                    "Gold fixture must not include sealed-challenge access"
            );
        }

        DeterministicSensitiveDataDetector detector =
                new DeterministicSensitiveDataDetector();

        long records = 0;
        long goldSpans = 0;
        long bypassRecords = 0;
        long mlRecords = 0;
        long deterministicMasks = 0;
        long deterministicAllows = 0;
        long bypassUncoveredGold = 0;
        long deterministicOvermask = 0;
        long allowGoldConflict = 0;

        Map<String, SourceStats> bySource = new LinkedHashMap<>();
        List<String> failureSamples = new ArrayList<>();

        JSONArray cases = fixture.getJSONArray("cases");
        for (int i = 0; i < cases.length(); i++) {
            JSONObject item = cases.getJSONObject(i);
            String source = item.getString("source");
            int sourceIndex = item.getInt("source_index");
            String text = item.getString("text");
            List<GoldSpan> gold = goldSpans(item.getJSONArray("entities"));

            SourceStats sourceStats =
                    bySource.computeIfAbsent(source, ignored -> new SourceStats());

            records++;
            sourceStats.records++;
            goldSpans += gold.size();
            sourceStats.goldSpans += gold.size();

            DeterministicScanResult scan = detector.scan(text);

            if (scan.requiresMl()) {
                mlRecords++;
                sourceStats.mlRecords++;
            } else {
                bypassRecords++;
                sourceStats.bypassRecords++;
            }

            for (DetectionEvidence evidence : scan.evidence()) {
                if (evidence.action() == ResolutionAction.MASK) {
                    deterministicMasks++;
                    sourceStats.deterministicMasks++;

                    if (!overlapsAnyGold(evidence, gold)) {
                        deterministicOvermask++;
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
                    deterministicAllows++;
                    sourceStats.deterministicAllows++;

                    if (overlapsAnyGold(evidence, gold)) {
                        allowGoldConflict++;
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
                        bypassUncoveredGold++;
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

        boolean passed = bypassUncoveredGold == 0
                && deterministicOvermask == 0
                && allowGoldConflict == 0;

        JSONObject result = new JSONObject();
        result.put(
                "status",
                passed
                        ? "HYBRID GATE DATASET AUDIT PASSED"
                        : "HYBRID GATE DATASET AUDIT FAILED"
        );
        result.put("passed", passed);
        result.put("records", records);
        result.put("gold_spans", goldSpans);
        result.put("bypass_records", bypassRecords);
        result.put("ml_records", mlRecords);
        result.put(
                "bypass_rate",
                records == 0 ? 0.0 : (double) bypassRecords / records
        );
        result.put("deterministic_masks", deterministicMasks);
        result.put("deterministic_allows", deterministicAllows);
        result.put("bypass_uncovered_gold", bypassUncoveredGold);
        result.put("deterministic_overmask", deterministicOvermask);
        result.put("allow_gold_conflict", allowGoldConflict);
        result.put("sealed_challenge_inference", false);
        result.put("model_inference", false);

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
                result.toString(2) + System.lineSeparator()
        );

        System.out.println(result.getString("status"));
        System.out.println("Records: " + records);
        System.out.println("Gold spans: " + goldSpans);
        System.out.println(
                "Deterministic bypass: "
                        + bypassRecords
                        + "/"
                        + records
                        + " ("
                        + String.format("%.2f%%", 100.0 * result.getDouble("bypass_rate"))
                        + ")"
        );
        System.out.println("Bypass uncovered gold: " + bypassUncoveredGold);
        System.out.println("Deterministic overmask: " + deterministicOvermask);
        System.out.println("ALLOW/gold conflicts: " + allowGoldConflict);
        System.out.println("Result: " + resultPath);

        if (!passed) {
            throw new IllegalStateException(
                    "Hybrid deterministic gate failed dataset security audit"
            );
        }
    }

    private static List<GoldSpan> goldSpans(JSONArray array) {
        List<GoldSpan> result = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.getJSONObject(i);
            result.add(
                    new GoldSpan(
                            item.getInt("start"),
                            item.getInt("end"),
                            item.getString("label")
                    )
            );
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
    }
}
