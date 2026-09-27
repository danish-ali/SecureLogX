package com.securelogx.validation;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import com.securelogx.config.SecureLogXConfig;
import com.securelogx.detection.DetectionEvidence;
import com.securelogx.detection.DetectionSource;
import com.securelogx.detection.DeterministicScanResult;
import com.securelogx.detection.DeterministicSensitiveDataDetector;
import com.securelogx.detection.HybridContextResolver;
import com.securelogx.detection.ResolutionAction;
import com.securelogx.detection.ResolvedSpan;
import com.securelogx.ner.TokenizedInput;
import com.securelogx.ner.impl.LabelAwareMaskingEngine;
import com.securelogx.ner.impl.ParallelTokenizer;
import com.securelogx.util.ArtifactIntegrityVerifier;
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
import java.util.Set;

/**
 * Non-sealed architecture-quality benchmark.
 *
 * Compares:
 * D0 - deterministic MASK evidence only
 * M0 - frozen ML-v1.3 on every record
 * H1 - current SecureLogX hybrid gate + resolver
 *
 * H2 remains explicitly unimplemented until a genuine contextual Redact/Keep
 * reviewer exists. This benchmark must not emulate H2 with heuristics.
 */
public final class ArchitectureComparisonBenchmark {

    private static final int INFERENCE_BATCH_SIZE = 64;

    private static final Set<String> HIGH_RISK = Set.of(
            "SSN",
            "ITIN",
            "TAX_ID",
            "CREDIT_CARD_NUMBER",
            "BANK_ACCOUNT_NUMBER",
            "ROUTING_NUMBER",
            "IBAN",
            "PASSPORT_NUMBER",
            "DRIVER_LICENSE",
            "AUTH_TOKEN",
            "API_KEY"
    );

    private static final Map<String, String> DATASETS =
            new LinkedHashMap<>();

    static {
        DATASETS.put("standard_dev", "data/split/dev.jsonl");
        DATASETS.put(
                "v1_3_challenge_dev",
                "data/ml_v1_3/real_structure/dev_challenge.jsonl"
        );
        DATASETS.put(
                "original_test_regression",
                "data/split/test.jsonl"
        );
    }

    private ArchitectureComparisonBenchmark() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException(
                    "Usage: ArchitectureComparisonBenchmark "
                            + "<SecureLogX-NER-root> <result.json>"
            );
        }

        Path nerRoot = Path.of(args[0]).toAbsolutePath().normalize();
        Path resultPath = Path.of(args[1]);
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

        DeterministicSensitiveDataDetector detector =
                new DeterministicSensitiveDataDetector();
        HybridContextResolver resolver = new HybridContextResolver();

        ArchitectureMetrics d0 = new ArchitectureMetrics("D0");
        ArchitectureMetrics m0 = new ArchitectureMetrics("M0");
        ArchitectureMetrics h1 = new ArchitectureMetrics("H1");
        HybridRegressionDiagnostics regressionDiagnostics =
                new HybridRegressionDiagnostics();

        Map<String, SourceMetrics> bySource = new LinkedHashMap<>();
        TruncationDiagnostics truncationDiagnostics =
                new TruncationDiagnostics();
        long inputRecords = 0;
        long scoredRecords = 0;
        long h1MlRecords = 0;

        try (ValidationInferenceSession inference =
                     new ValidationInferenceSession(config)) {

            for (Map.Entry<String, String> dataset : DATASETS.entrySet()) {
                String source = dataset.getKey();
                Path datasetPath =
                        nerRoot.resolve(dataset.getValue()).normalize();

                if (!datasetPath.startsWith(nerRoot)) {
                    throw new IllegalStateException(
                            "Dataset path escaped NER root: " + datasetPath
                    );
                }
                if (!Files.exists(datasetPath)) {
                    throw new IllegalStateException(
                            "Required NER dataset is missing: " + datasetPath
                    );
                }

                SourceMetrics sourceMetrics = new SourceMetrics();
                bySource.put(source, sourceMetrics);

                try (BufferedReader reader = Files.newBufferedReader(
                        datasetPath,
                        StandardCharsets.UTF_8
                )) {
                    List<RecordItem> batch =
                            new ArrayList<>(INFERENCE_BATCH_SIZE);
                    String line;

                    while ((line = reader.readLine()) != null) {
                        if (line.isBlank()) {
                            continue;
                        }

                        JSONObject item = new JSONObject(line);
                        batch.add(
                                new RecordItem(
                                        item.getString("text"),
                                        goldSpans(
                                                item.optJSONArray("entities")
                                        )
                                )
                        );

                        if (batch.size() == INFERENCE_BATCH_SIZE) {
                            BatchEvaluationResult batchResult =
                                    evaluateBatch(
                                            source,
                                            batch,
                                            tokenizer,
                                            inference,
                                            detector,
                                            resolver,
                                            d0,
                                            m0,
                                            h1,
                                            sourceMetrics,
                                            regressionDiagnostics,
                                            truncationDiagnostics
                                    );
                            h1MlRecords += batchResult.h1MlRecords();
                            scoredRecords += batchResult.scoredRecords();
                            inputRecords += batch.size();
                            batch.clear();
                        }
                    }

                    if (!batch.isEmpty()) {
                        BatchEvaluationResult batchResult =
                                evaluateBatch(
                                        source,
                                        batch,
                                        tokenizer,
                                        inference,
                                        detector,
                                        resolver,
                                        d0,
                                        m0,
                                        h1,
                                        sourceMetrics,
                                        regressionDiagnostics,
                                        truncationDiagnostics
                                );
                        h1MlRecords += batchResult.h1MlRecords();
                        scoredRecords += batchResult.scoredRecords();
                        inputRecords += batch.size();
                    }
                }
            }
        }

        d0.setMlInvocation(0, scoredRecords);
        m0.setMlInvocation(scoredRecords, scoredRecords);
        h1.setMlInvocation(h1MlRecords, scoredRecords);

        JSONObject result = new JSONObject();
        result.put(
                "status",
                "ARCHITECTURE COMPARISON D0/M0/H1 COMPLETE; H2 PENDING"
        );
        result.put("passed", true);
        result.put("input_records", inputRecords);
        result.put("scored_records", scoredRecords);
        result.put(
                "excluded_truncated_records",
                truncationDiagnostics.totalTruncated()
        );
        result.put("sealed_challenge_accessed", false);
        result.put("sealed_challenge_inference", false);
        result.put("model_sha256", config.getModelSha256());
        result.put("tokenizer_sha256", config.getTokenizerSha256());
        result.put("character_metric_scope", "all characters in record");
        result.put("batch_size", INFERENCE_BATCH_SIZE);
        result.put(
                "inference_method",
                "ML-v1.3 is evaluated once on every record so M0 and H1 use identical predictions; H1 ML rate is logical routing, not benchmark compute usage"
        );

        JSONObject architectures = new JSONObject();
        architectures.put("D0", d0.toJson());
        architectures.put("M0", m0.toJson());
        architectures.put("H1", h1.toJson());

        JSONObject h2 = new JSONObject();
        h2.put("status", "EXPERIMENTAL_NOT_IMPLEMENTED");
        h2.put(
                "reason",
                "A genuine contextual Redact/Keep reviewer is required; "
                        + "heuristic emulation would not be a valid baseline."
        );
        architectures.put("H2", h2);
        result.put("architectures", architectures);
        result.put(
                "m0_h1_regression_diagnostics",
                regressionDiagnostics.toJson()
        );
        result.put(
                "truncation_diagnostics",
                truncationDiagnostics.toJson()
        );

        JSONObject sources = new JSONObject();
        for (Map.Entry<String, SourceMetrics> entry : bySource.entrySet()) {
            sources.put(entry.getKey(), entry.getValue().toJson());
        }
        result.put("by_source", sources);

        Path parent = resultPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(
                resultPath,
                result.toString(2) + System.lineSeparator(),
                StandardCharsets.UTF_8
        );

        printSummary(d0);
        printSummary(m0);
        printSummary(h1);
        printRegressionDiagnostics(regressionDiagnostics);
        System.out.println();
        System.out.println("Truncation cohort");
        System.out.println("  input records: " + inputRecords);
        System.out.println("  scored records: " + scoredRecords);
        System.out.println(
                "  excluded truncated records: "
                        + truncationDiagnostics.totalTruncated()
        );
        JSONObject truncationBySource =
                truncationDiagnostics.toJson()
                        .getJSONObject("by_source");
        truncationBySource.keySet().stream()
                .sorted()
                .forEach(
                        source -> System.out.println(
                                "    "
                                        + source
                                        + ": "
                                        + truncationBySource.getLong(source)
                        )
                );
        System.out.println();
        System.out.println(
                "H2: EXPERIMENTAL_NOT_IMPLEMENTED "
                        + "(requires genuine contextual reviewer)"
        );
        System.out.println("Result: " + resultPath);
    }

    private static BatchEvaluationResult evaluateBatch(
            String source,
            List<RecordItem> batch,
            ParallelTokenizer tokenizer,
            ValidationInferenceSession inference,
            DeterministicSensitiveDataDetector detector,
            HybridContextResolver resolver,
            ArchitectureMetrics d0,
            ArchitectureMetrics m0,
            ArchitectureMetrics h1,
            SourceMetrics sourceMetrics,
            HybridRegressionDiagnostics regressionDiagnostics,
            TruncationDiagnostics truncationDiagnostics
    ) throws Exception {
        List<RecordItem> scorable = new ArrayList<>();
        for (RecordItem record : batch) {
            TokenizedInput probe = tokenizer.tokenize(record.text());
            sourceMetrics.inputRecords++;

            if (probe.isTruncated()) {
                sourceMetrics.truncatedRecords++;
                truncationDiagnostics.add(
                        source,
                        record.text(),
                        probe.getCoveredCharacterEnd(),
                        record.gold()
                );
            } else {
                scorable.add(record);
            }
        }

        if (scorable.isEmpty()) {
            return new BatchEvaluationResult(0, 0);
        }

        List<String> texts = scorable.stream()
                .map(RecordItem::text)
                .toList();

        List<List<LabelAwareMaskingEngine.EntitySpan>> mlPredictions =
                inference.infer(texts, tokenizer);

        long h1MlRecords = 0;

        for (int i = 0; i < scorable.size(); i++) {
            RecordItem record = scorable.get(i);
            List<LabelAwareMaskingEngine.EntitySpan> mlSpans =
                    mlPredictions.get(i);

            DeterministicScanResult scan =
                    detector.scan(record.text());

            List<PredictedSpan> d0Spans = deterministicMaskSpans(
                    scan.evidence()
            );
            List<PredictedSpan> m0Spans = mlMaskSpans(mlSpans);

            List<LabelAwareMaskingEngine.EntitySpan> h1MlSpans;
            if (scan.requiresMl()) {
                h1MlRecords++;
                h1MlSpans = mlSpans;
            } else {
                h1MlSpans = List.of();
            }

            List<PredictedSpan> h1Spans = resolvedMaskSpans(
                    resolver.resolve(
                            scan.evidence(),
                            h1MlSpans
                    )
            );

            regressionDiagnostics.compare(
                    source,
                    record.text(),
                    record.gold(),
                    m0Spans,
                    h1Spans,
                    scan.evidence()
            );

            d0.addRecord(record.text(), record.gold(), d0Spans);
            m0.addRecord(record.text(), record.gold(), m0Spans);
            h1.addRecord(record.text(), record.gold(), h1Spans);

            sourceMetrics.d0.addRecord(
                    record.text(),
                    record.gold(),
                    d0Spans
            );
            sourceMetrics.m0.addRecord(
                    record.text(),
                    record.gold(),
                    m0Spans
            );
            sourceMetrics.h1.addRecord(
                    record.text(),
                    record.gold(),
                    h1Spans
            );

            sourceMetrics.records++;
            if (scan.requiresMl()) {
                sourceMetrics.h1MlRecords++;
            }
        }

        return new BatchEvaluationResult(
                h1MlRecords,
                scorable.size()
        );
    }

    private static List<PredictedSpan> deterministicMaskSpans(
            List<DetectionEvidence> evidence
    ) {
        List<PredictedSpan> spans = new ArrayList<>();
        for (DetectionEvidence item : evidence) {
            if (item.action() == ResolutionAction.MASK) {
                spans.add(
                        new PredictedSpan(
                                item.start(),
                                item.end(),
                                item.entityType(),
                                DetectionSource.DETERMINISTIC.name()
                        )
                );
            }
        }
        return spans;
    }

    private static List<PredictedSpan> mlMaskSpans(
            List<LabelAwareMaskingEngine.EntitySpan> mlSpans
    ) {
        List<PredictedSpan> spans = new ArrayList<>();
        for (LabelAwareMaskingEngine.EntitySpan item : mlSpans) {
            spans.add(
                    new PredictedSpan(
                            item.start(),
                            item.end(),
                            item.entityType(),
                            DetectionSource.ML.name()
                    )
            );
        }
        return spans;
    }

    private static List<PredictedSpan> resolvedMaskSpans(
            List<ResolvedSpan> resolved
    ) {
        List<PredictedSpan> spans = new ArrayList<>();
        for (ResolvedSpan item : resolved) {
            if (item.action() == ResolutionAction.MASK) {
                spans.add(
                        new PredictedSpan(
                                item.start(),
                                item.end(),
                                item.entityType(),
                                item.winningSource().name()
                        )
                );
            }
        }
        return spans;
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

    private static void printSummary(ArchitectureMetrics metrics) {
        JSONObject json = metrics.toJson();
        System.out.println();
        System.out.println(metrics.id + " architecture quality");
        System.out.println(
                "  sensitive-character recall: "
                        + percent(
                                json.getDouble(
                                        "sensitive_character_recall"
                                )
                        )
        );
        System.out.println(
                "  non-sensitive-character redaction: "
                        + percent(
                                json.getDouble(
                                        "non_sensitive_character_redaction_rate"
                                )
                        )
        );
        System.out.println(
                "  full-span recall: "
                        + percent(json.getDouble("full_span_recall"))
        );
        System.out.println(
                "  high-risk full-span recall: "
                        + percent(
                                json.getDouble(
                                        "high_risk_full_span_recall"
                                )
                        )
        );
        System.out.println(
                "  whole-record perfect redaction: "
                        + percent(
                                json.getDouble(
                                        "whole_record_perfect_redaction_rate"
                                )
                        )
        );
        System.out.println(
                "  ML invocation: "
                        + percent(
                                json.getDouble(
                                        "logical_ml_invocation_rate"
                                )
                        )
        );
    }

    private static void printRegressionDiagnostics(
            HybridRegressionDiagnostics diagnostics
    ) {
        JSONObject json = diagnostics.toJson();
        System.out.println();
        System.out.println("M0 -> H1 regression diagnostics");
        System.out.println(
                "  M0 full / H1 not full: "
                        + json.getLong("m0_full_h1_not_full")
        );
        System.out.println(
                "  high-risk regressions: "
                        + json.getLong("high_risk_regressions")
        );
        System.out.println(
                "  H1 full / M0 not full: "
                        + json.getLong("h1_full_m0_not_full")
        );
        System.out.println("  regressions by gold label:");
        JSONObject byLabel = json.getJSONObject("regressions_by_gold_label");
        byLabel.keySet().stream()
                .sorted(
                        (left, right) -> Long.compare(
                                byLabel.getLong(right),
                                byLabel.getLong(left)
                        )
                )
                .limit(12)
                .forEach(
                        label -> System.out.println(
                                "    "
                                        + byLabel.getLong(label)
                                        + "  "
                                        + label
                        )
                );

        System.out.println("  overlapping decisive evidence reasons:");
        JSONObject byReason =
                json.getJSONObject("overlapping_decisive_evidence_reasons");
        byReason.keySet().stream()
                .sorted(
                        (left, right) -> Long.compare(
                                byReason.getLong(right),
                                byReason.getLong(left)
                        )
                )
                .limit(12)
                .forEach(
                        reason -> System.out.println(
                                "    "
                                        + byReason.getLong(reason)
                                        + "  "
                                        + reason
                        )
                );
    }

    private static String percent(double value) {
        return String.format("%.4f%%", value * 100.0);
    }

    private static final class ValidationInferenceSession
            implements AutoCloseable {

        private final OrtEnvironment env;
        private final OrtSession session;
        private final LabelAwareMaskingEngine decoder;
        private final int maxSequenceLength;

        private ValidationInferenceSession(
                SecureLogXConfig config
        ) throws Exception {
            env = OrtEnvironment.getEnvironment();
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

            session = env.createSession(
                    config.getModelPath().replace("\\", "/"),
                    options
            );
            decoder = new LabelAwareMaskingEngine();
            maxSequenceLength = config.getMaxSequenceLength();
        }

        private List<List<LabelAwareMaskingEngine.EntitySpan>> infer(
                List<String> texts,
                ParallelTokenizer tokenizer
        ) throws Exception {
            if (texts.isEmpty()) {
                return List.of();
            }

            List<TokenizedInput> encoded = new ArrayList<>(texts.size());
            int seqLen = 0;

            for (String text : texts) {
                TokenizedInput tokenized = tokenizer.tokenize(text);
                if (tokenized.isTruncated()) {
                    throw new IllegalStateException(
                            "Internal benchmark invariant violated: "
                                    + "truncated record reached inference"
                    );
                }
                encoded.add(tokenized);
                seqLen = Math.max(
                        seqLen,
                        Math.min(
                                tokenized.getInputIds().length,
                                maxSequenceLength
                        )
                );
            }

            long[][] inputIds = new long[texts.size()][seqLen];
            long[][] attentionMask = new long[texts.size()][seqLen];
            long[][] tokenTypeIds = new long[texts.size()][seqLen];

            for (int i = 0; i < encoded.size(); i++) {
                int[] ids = encoded.get(i).getInputIds();
                int[] mask = encoded.get(i).getAttentionMask();
                int copyLength = Math.min(ids.length, seqLen);

                for (int j = 0; j < copyLength; j++) {
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

                    List<List<LabelAwareMaskingEngine.EntitySpan>>
                            predictions =
                            new ArrayList<>(texts.size());

                    for (int i = 0; i < texts.size(); i++) {
                        List<int[]> offsets =
                                encoded.get(i).getOffsets();
                        List<int[]> truncatedOffsets =
                                offsets.size() > seqLen
                                        ? offsets.subList(0, seqLen)
                                        : offsets;

                        predictions.add(
                                decoder.decodeSpans(
                                        texts.get(i),
                                        new float[][][]{logits[i]},
                                        truncatedOffsets
                                )
                        );
                    }

                    return predictions;
                }
            }
        }

        @Override
        public void close() throws Exception {
            session.close();
        }
    }

    private static final class ArchitectureMetrics {
        private final String id;

        private long records;
        private long recordsWithSensitive;
        private long sensitiveCharacters;
        private long sensitiveCharactersRedacted;
        private long nonSensitiveCharacters;
        private long nonSensitiveCharactersRedacted;

        private long goldSpans;
        private long fullGoldSpans;
        private long exactBoundaryGoldSpans;
        private long partialGoldSpans;
        private long missedGoldSpans;

        private long highRiskGoldSpans;
        private long highRiskFullGoldSpans;

        private long recordsAllSensitiveCovered;
        private long sensitiveRecordsAllCovered;
        private long recordsPerfectRedaction;
        private long predictedSpans;

        private long mlRecords;
        private long routedRecords;

        private ArchitectureMetrics(String id) {
            this.id = id;
        }

        private void setMlInvocation(long mlRecords, long totalRecords) {
            this.mlRecords = mlRecords;
            this.routedRecords = totalRecords;
        }

        private void addRecord(
                String text,
                List<GoldSpan> gold,
                List<PredictedSpan> predicted
        ) {
            records++;
            predictedSpans += predicted.size();

            boolean[] goldMask = new boolean[text.length()];
            boolean[] predictedMask = new boolean[text.length()];

            for (GoldSpan span : gold) {
                mark(goldMask, span.start(), span.end());
            }
            for (PredictedSpan span : predicted) {
                mark(predictedMask, span.start(), span.end());
            }

            long recordSensitive = 0;
            long recordSensitiveRedacted = 0;
            long recordNonSensitiveRedacted = 0;

            for (int i = 0; i < text.length(); i++) {
                if (goldMask[i]) {
                    sensitiveCharacters++;
                    recordSensitive++;
                    if (predictedMask[i]) {
                        sensitiveCharactersRedacted++;
                        recordSensitiveRedacted++;
                    }
                } else {
                    nonSensitiveCharacters++;
                    if (predictedMask[i]) {
                        nonSensitiveCharactersRedacted++;
                        recordNonSensitiveRedacted++;
                    }
                }
            }

            if (!gold.isEmpty()) {
                recordsWithSensitive++;
            }

            if (recordSensitive == recordSensitiveRedacted) {
                recordsAllSensitiveCovered++;
                if (recordSensitive > 0) {
                    sensitiveRecordsAllCovered++;
                }
                if (recordNonSensitiveRedacted == 0) {
                    recordsPerfectRedaction++;
                }
            }

            for (GoldSpan goldSpan : gold) {
                goldSpans++;

                boolean any = false;
                boolean all = true;

                int start = bounded(goldSpan.start(), text.length());
                int end = bounded(goldSpan.end(), text.length());

                for (int i = start; i < end; i++) {
                    if (predictedMask[i]) {
                        any = true;
                    } else {
                        all = false;
                    }
                }

                if (all && end > start) {
                    fullGoldSpans++;
                } else if (any) {
                    partialGoldSpans++;
                } else {
                    missedGoldSpans++;
                }

                boolean exact = predicted.stream().anyMatch(
                        item -> item.start() == goldSpan.start()
                                && item.end() == goldSpan.end()
                );
                if (exact) {
                    exactBoundaryGoldSpans++;
                }

                if (HIGH_RISK.contains(goldSpan.label())) {
                    highRiskGoldSpans++;
                    if (all && end > start) {
                        highRiskFullGoldSpans++;
                    }
                }
            }
        }

        private JSONObject toJson() {
            JSONObject object = new JSONObject();
            object.put("records", records);
            object.put("records_with_sensitive", recordsWithSensitive);

            object.put("sensitive_characters", sensitiveCharacters);
            object.put(
                    "sensitive_characters_redacted",
                    sensitiveCharactersRedacted
            );
            object.put(
                    "sensitive_character_recall",
                    ratio(
                            sensitiveCharactersRedacted,
                            sensitiveCharacters
                    )
            );

            object.put(
                    "non_sensitive_characters",
                    nonSensitiveCharacters
            );
            object.put(
                    "non_sensitive_characters_redacted",
                    nonSensitiveCharactersRedacted
            );
            object.put(
                    "non_sensitive_character_redaction_rate",
                    ratio(
                            nonSensitiveCharactersRedacted,
                            nonSensitiveCharacters
                    )
            );

            object.put("gold_spans", goldSpans);
            object.put("full_gold_spans", fullGoldSpans);
            object.put("partial_gold_spans", partialGoldSpans);
            object.put("missed_gold_spans", missedGoldSpans);
            object.put(
                    "exact_boundary_gold_spans",
                    exactBoundaryGoldSpans
            );
            object.put(
                    "full_span_recall",
                    ratio(fullGoldSpans, goldSpans)
            );
            object.put(
                    "exact_boundary_recall",
                    ratio(exactBoundaryGoldSpans, goldSpans)
            );

            object.put(
                    "high_risk_gold_spans",
                    highRiskGoldSpans
            );
            object.put(
                    "high_risk_full_gold_spans",
                    highRiskFullGoldSpans
            );
            object.put(
                    "high_risk_full_span_recall",
                    ratio(
                            highRiskFullGoldSpans,
                            highRiskGoldSpans
                    )
            );

            object.put(
                    "records_all_sensitive_covered",
                    recordsAllSensitiveCovered
            );
            object.put(
                    "all_record_sensitive_coverage_rate",
                    ratio(recordsAllSensitiveCovered, records)
            );
            object.put(
                    "sensitive_records_all_covered",
                    sensitiveRecordsAllCovered
            );
            object.put(
                    "sensitive_record_full_coverage_rate",
                    ratio(
                            sensitiveRecordsAllCovered,
                            recordsWithSensitive
                    )
            );
            object.put(
                    "records_perfect_redaction",
                    recordsPerfectRedaction
            );
            object.put(
                    "whole_record_perfect_redaction_rate",
                    ratio(recordsPerfectRedaction, records)
            );

            object.put("predicted_spans", predictedSpans);
            object.put("logical_ml_records", mlRecords);
            object.put(
                    "logical_ml_invocation_rate",
                    ratio(mlRecords, routedRecords)
            );
            return object;
        }

        private static void mark(
                boolean[] mask,
                int rawStart,
                int rawEnd
        ) {
            int start = bounded(rawStart, mask.length);
            int end = bounded(rawEnd, mask.length);
            for (int i = start; i < end; i++) {
                mask[i] = true;
            }
        }

        private static int bounded(int value, int length) {
            return Math.max(0, Math.min(length, value));
        }

        private static double ratio(long numerator, long denominator) {
            return denominator == 0
                    ? 0.0
                    : (double) numerator / denominator;
        }
    }

    private static final class HybridRegressionDiagnostics {
        private static final int MAX_SAMPLES = 30;

        private long m0FullH1NotFull;
        private long highRiskRegressions;
        private long h1FullM0NotFull;

        private final Map<String, Long> byGoldLabel =
                new LinkedHashMap<>();
        private final Map<String, Long> byEvidenceReason =
                new LinkedHashMap<>();
        private final Map<String, Long> bySource =
                new LinkedHashMap<>();
        private final List<String> samples = new ArrayList<>();

        private void compare(
                String source,
                String text,
                List<GoldSpan> gold,
                List<PredictedSpan> m0,
                List<PredictedSpan> h1,
                List<DetectionEvidence> deterministicEvidence
        ) {
            for (GoldSpan span : gold) {
                boolean m0Full = fullyCovered(span, m0);
                boolean h1Full = fullyCovered(span, h1);

                if (m0Full && !h1Full) {
                    m0FullH1NotFull++;
                    byGoldLabel.merge(span.label(), 1L, Long::sum);
                    bySource.merge(source, 1L, Long::sum);

                    if (HIGH_RISK.contains(span.label())) {
                        highRiskRegressions++;
                    }

                    boolean foundDecisiveOverlap = false;
                    for (DetectionEvidence evidence : deterministicEvidence) {
                        if (evidence.action() == ResolutionAction.ESCALATE
                                || !evidence.overlaps(
                                        span.start(),
                                        span.end()
                                )) {
                            continue;
                        }

                        foundDecisiveOverlap = true;
                        String key = evidence.action()
                                + ":"
                                + evidence.entityType()
                                + ":"
                                + evidence.reason();
                        byEvidenceReason.merge(key, 1L, Long::sum);
                    }

                    if (!foundDecisiveOverlap) {
                        byEvidenceReason.merge(
                                "NO_DECISIVE_GOLD_OVERLAP",
                                1L,
                                Long::sum
                        );
                    }

                    addSample(
                            source,
                            text,
                            span,
                            m0,
                            h1,
                            deterministicEvidence
                    );
                } else if (!m0Full && h1Full) {
                    h1FullM0NotFull++;
                }
            }
        }

        private void addSample(
                String source,
                String text,
                GoldSpan gold,
                List<PredictedSpan> m0,
                List<PredictedSpan> h1,
                List<DetectionEvidence> deterministicEvidence
        ) {
            if (samples.size() >= MAX_SAMPLES) {
                return;
            }

            String raw = safeSlice(text, gold.start(), gold.end());
            List<String> decisive = new ArrayList<>();
            for (DetectionEvidence evidence : deterministicEvidence) {
                if (evidence.action() != ResolutionAction.ESCALATE
                        && evidence.overlaps(gold.start(), gold.end())) {
                    decisive.add(
                            evidence.action()
                                    + ":"
                                    + evidence.entityType()
                                    + "["
                                    + evidence.start()
                                    + ","
                                    + evidence.end()
                                    + "):"
                                    + evidence.reason()
                    );
                }
            }

            samples.add(
                    source
                            + " gold="
                            + gold.label()
                            + "["
                            + gold.start()
                            + ","
                            + gold.end()
                            + ") value="
                            + quote(raw)
                            + " decisive="
                            + decisive
                            + " m0="
                            + overlappingPredictions(gold, m0)
                            + " h1="
                            + overlappingPredictions(gold, h1)
            );
        }

        private JSONObject toJson() {
            JSONObject object = new JSONObject();
            object.put("m0_full_h1_not_full", m0FullH1NotFull);
            object.put("high_risk_regressions", highRiskRegressions);
            object.put("h1_full_m0_not_full", h1FullM0NotFull);
            object.put(
                    "regressions_by_gold_label",
                    countsJson(byGoldLabel)
            );
            object.put(
                    "overlapping_decisive_evidence_reasons",
                    countsJson(byEvidenceReason)
            );
            object.put("regressions_by_source", countsJson(bySource));
            object.put("samples", new JSONArray(samples));
            return object;
        }

        private static boolean fullyCovered(
                GoldSpan gold,
                List<PredictedSpan> predicted
        ) {
            for (int position = gold.start();
                 position < gold.end();
                 position++) {
                boolean covered = false;
                for (PredictedSpan item : predicted) {
                    if (item.start() <= position && item.end() > position) {
                        covered = true;
                        break;
                    }
                }
                if (!covered) {
                    return false;
                }
            }
            return gold.end() > gold.start();
        }

        private static List<String> overlappingPredictions(
                GoldSpan gold,
                List<PredictedSpan> predicted
        ) {
            List<String> result = new ArrayList<>();
            for (PredictedSpan item : predicted) {
                if (Math.max(gold.start(), item.start())
                        < Math.min(gold.end(), item.end())) {
                    result.add(
                            item.entityType()
                                    + "["
                                    + item.start()
                                    + ","
                                    + item.end()
                                    + "):"
                                    + item.source()
                    );
                }
            }
            return result;
        }

        private static String safeSlice(
                String text,
                int rawStart,
                int rawEnd
        ) {
            int start = Math.max(0, Math.min(text.length(), rawStart));
            int end = Math.max(start, Math.min(text.length(), rawEnd));
            return text.substring(start, end);
        }

        private static String quote(String value) {
            return "'" + value.replace("'", "\\'") + "'";
        }

        private static JSONObject countsJson(Map<String, Long> counts) {
            JSONObject object = new JSONObject();
            counts.entrySet().stream()
                    .sorted(
                            (left, right) -> Long.compare(
                                    right.getValue(),
                                    left.getValue()
                            )
                    )
                    .forEach(
                            entry -> object.put(
                                    entry.getKey(),
                                    entry.getValue()
                            )
                    );
            return object;
        }
    }

    private static final class TruncationDiagnostics {
        private static final int MAX_SAMPLES = 20;

        private long totalTruncated;
        private final Map<String, Long> bySource =
                new LinkedHashMap<>();
        private final List<String> samples = new ArrayList<>();

        private void add(
                String source,
                String text,
                int coveredCharacterEnd,
                List<GoldSpan> gold
        ) {
            totalTruncated++;
            bySource.merge(source, 1L, Long::sum);

            if (samples.size() < MAX_SAMPLES) {
                long goldBeyondCoverage = gold.stream()
                        .filter(span -> span.end() > coveredCharacterEnd)
                        .count();
                samples.add(
                        source
                                + " coveredChars="
                                + coveredCharacterEnd
                                + " totalChars="
                                + text.length()
                                + " goldSpans="
                                + gold.size()
                                + " goldSpansBeyondCoverage="
                                + goldBeyondCoverage
                );
            }
        }

        private long totalTruncated() {
            return totalTruncated;
        }

        private JSONObject toJson() {
            JSONObject object = new JSONObject();
            object.put("excluded_records", totalTruncated);
            object.put("by_source", countsJson(bySource));
            object.put("samples", new JSONArray(samples));
            object.put(
                    "scoring_policy",
                    "Excluded from D0/M0/H1 quality metrics because M0/H1 "
                            + "cannot validly score beyond the frozen 384-token window"
            );
            object.put(
                    "runtime_policy",
                    "ML-routed over-window records fail closed until "
                            + "validated overlapping-window inference exists"
            );
            return object;
        }

        private static JSONObject countsJson(Map<String, Long> counts) {
            JSONObject object = new JSONObject();
            counts.forEach(object::put);
            return object;
        }
    }

    private record BatchEvaluationResult(
            long h1MlRecords,
            long scoredRecords
    ) {
    }

    private static final class SourceMetrics {
        private long inputRecords;
        private long truncatedRecords;
        private long records;
        private long h1MlRecords;
        private final ArchitectureMetrics d0 =
                new ArchitectureMetrics("D0");
        private final ArchitectureMetrics m0 =
                new ArchitectureMetrics("M0");
        private final ArchitectureMetrics h1 =
                new ArchitectureMetrics("H1");

        private JSONObject toJson() {
            d0.setMlInvocation(0, records);
            m0.setMlInvocation(records, records);
            h1.setMlInvocation(h1MlRecords, records);

            JSONObject object = new JSONObject();
            object.put("input_records", inputRecords);
            object.put("scored_records", records);
            object.put("excluded_truncated_records", truncatedRecords);
            object.put("D0", d0.toJson());
            object.put("M0", m0.toJson());
            object.put("H1", h1.toJson());
            return object;
        }
    }

    private record RecordItem(
            String text,
            List<GoldSpan> gold
    ) {
        private RecordItem {
            gold = List.copyOf(gold);
        }
    }

    private record GoldSpan(
            int start,
            int end,
            String label
    ) {
    }

    private record PredictedSpan(
            int start,
            int end,
            String entityType,
            String source
    ) {
    }
}
