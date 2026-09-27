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

        Map<String, SourceMetrics> bySource = new LinkedHashMap<>();
        long records = 0;
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
                            long batchMl = evaluateBatch(
                                    source,
                                    batch,
                                    tokenizer,
                                    inference,
                                    detector,
                                    resolver,
                                    d0,
                                    m0,
                                    h1,
                                    sourceMetrics
                            );
                            h1MlRecords += batchMl;
                            records += batch.size();
                            batch.clear();
                        }
                    }

                    if (!batch.isEmpty()) {
                        long batchMl = evaluateBatch(
                                source,
                                batch,
                                tokenizer,
                                inference,
                                detector,
                                resolver,
                                d0,
                                m0,
                                h1,
                                sourceMetrics
                        );
                        h1MlRecords += batchMl;
                        records += batch.size();
                    }
                }
            }
        }

        d0.setMlInvocation(0, records);
        m0.setMlInvocation(records, records);
        h1.setMlInvocation(h1MlRecords, records);

        JSONObject result = new JSONObject();
        result.put(
                "status",
                "ARCHITECTURE COMPARISON D0/M0/H1 COMPLETE; H2 PENDING"
        );
        result.put("passed", true);
        result.put("records", records);
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
        System.out.println();
        System.out.println(
                "H2: EXPERIMENTAL_NOT_IMPLEMENTED "
                        + "(requires genuine contextual reviewer)"
        );
        System.out.println("Result: " + resultPath);
    }

    private static long evaluateBatch(
            String source,
            List<RecordItem> batch,
            ParallelTokenizer tokenizer,
            ValidationInferenceSession inference,
            DeterministicSensitiveDataDetector detector,
            HybridContextResolver resolver,
            ArchitectureMetrics d0,
            ArchitectureMetrics m0,
            ArchitectureMetrics h1,
            SourceMetrics sourceMetrics
    ) throws Exception {
        List<String> texts = batch.stream()
                .map(RecordItem::text)
                .toList();

        List<List<LabelAwareMaskingEngine.EntitySpan>> mlPredictions =
                inference.infer(texts, tokenizer);

        long h1MlRecords = 0;

        for (int i = 0; i < batch.size(); i++) {
            RecordItem record = batch.get(i);
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

        return h1MlRecords;
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

    private static final class SourceMetrics {
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
            object.put("records", records);
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
