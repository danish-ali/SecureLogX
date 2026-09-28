package com.securelogx.validation;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import com.securelogx.config.SecureLogXConfig;
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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Experimental comparison:
 *
 * M0  - current production-compatible token argmax + BIO span normalization
 * C1  - strict BIO-constrained Viterbi decoding
 *
 * Both decoders consume the exact same frozen ML-v1.3 logits. Production
 * decoding is not modified by this benchmark.
 */
public final class ConstrainedDecodingBenchmark {

    private static final int RECORD_BATCH_SIZE = 64;
    private static final int WINDOW_OVERLAP_CONTENT_TOKENS = 64;

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

    private ConstrainedDecodingBenchmark() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException(
                    "Usage: ConstrainedDecodingBenchmark "
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

        QualityMetrics m0 = new QualityMetrics("M0_ARGMAX");
        QualityMetrics c1 = new QualityMetrics("C1_BIO_VITERBI");
        DifferenceDiagnostics differences =
                new DifferenceDiagnostics();

        long records = 0;
        long windows = 0;
        long windowedRecords = 0;
        long illegalArgmaxTransitions = 0;
        long changedTokenLabels = 0;
        long argmaxEntityTokens = 0;
        long onnxCalls = 0;
        long maxWindowsPerCall = 0;

        Map<String, JSONObject> sourceResults =
                new LinkedHashMap<>();

        try (InferenceSession inference =
                     new InferenceSession(config)) {

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
                            "Required dataset missing: " + datasetPath
                    );
                }

                QualityMetrics sourceM0 =
                        new QualityMetrics("M0_ARGMAX");
                QualityMetrics sourceC1 =
                        new QualityMetrics("C1_BIO_VITERBI");

                long sourceRecords = 0;
                long sourceWindowedRecords = 0;
                long sourceWindows = 0;

                try (BufferedReader reader = Files.newBufferedReader(
                        datasetPath,
                        StandardCharsets.UTF_8
                )) {
                    List<RecordItem> batch =
                            new ArrayList<>(RECORD_BATCH_SIZE);
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

                        if (batch.size() == RECORD_BATCH_SIZE) {
                            BatchResult result = evaluateBatch(
                                    source,
                                    batch,
                                    tokenizer,
                                    inference,
                                    m0,
                                    c1,
                                    sourceM0,
                                    sourceC1,
                                    differences
                            );

                            records += batch.size();
                            sourceRecords += batch.size();
                            windows += result.windows();
                            sourceWindows += result.windows();
                            windowedRecords += result.windowedRecords();
                            sourceWindowedRecords +=
                                    result.windowedRecords();
                            illegalArgmaxTransitions +=
                                    result.illegalArgmaxTransitions();
                            changedTokenLabels +=
                                    result.changedTokenLabels();
                            argmaxEntityTokens +=
                                    result.argmaxEntityTokens();
                            onnxCalls += result.onnxCalls();
                            maxWindowsPerCall = Math.max(
                                    maxWindowsPerCall,
                                    result.maxWindowsPerCall()
                            );

                            batch.clear();
                        }
                    }

                    if (!batch.isEmpty()) {
                        BatchResult result = evaluateBatch(
                                source,
                                batch,
                                tokenizer,
                                inference,
                                m0,
                                c1,
                                sourceM0,
                                sourceC1,
                                differences
                        );

                        records += batch.size();
                        sourceRecords += batch.size();
                        windows += result.windows();
                        sourceWindows += result.windows();
                        windowedRecords += result.windowedRecords();
                        sourceWindowedRecords +=
                                result.windowedRecords();
                        illegalArgmaxTransitions +=
                                result.illegalArgmaxTransitions();
                        changedTokenLabels +=
                                result.changedTokenLabels();
                        argmaxEntityTokens +=
                                result.argmaxEntityTokens();
                        onnxCalls += result.onnxCalls();
                        maxWindowsPerCall = Math.max(
                                maxWindowsPerCall,
                                result.maxWindowsPerCall()
                        );
                    }
                }

                JSONObject sourceJson = new JSONObject();
                sourceJson.put("records", sourceRecords);
                sourceJson.put(
                        "windowed_records",
                        sourceWindowedRecords
                );
                sourceJson.put("inference_windows", sourceWindows);
                sourceJson.put("M0", sourceM0.toJson());
                sourceJson.put("C1", sourceC1.toJson());
                sourceResults.put(source, sourceJson);
            }
        }

        JSONObject output = new JSONObject();
        output.put(
                "status",
                "CONSTRAINED BIO DECODING EXPERIMENT COMPLETE"
        );
        output.put("passed", true);
        output.put("production_decoder_changed", false);
        output.put("sealed_challenge_accessed", false);
        output.put("records", records);
        output.put("windowed_records", windowedRecords);
        output.put("inference_windows", windows);
        output.put("onnx_inference_calls", onnxCalls);
        output.put(
                "max_windows_per_onnx_call",
                maxWindowsPerCall
        );
        output.put(
                "configured_max_inference_windows_per_batch",
                config.getMaxInferenceWindowsPerBatch()
        );
        output.put(
                "illegal_argmax_bio_transitions",
                illegalArgmaxTransitions
        );
        output.put(
                "constrained_changed_token_labels",
                changedTokenLabels
        );
        output.put(
                "argmax_entity_tokens",
                argmaxEntityTokens
        );
        output.put(
                "changed_token_rate_among_argmax_entity_tokens",
                ratio(changedTokenLabels, argmaxEntityTokens)
        );

        JSONObject decoders = new JSONObject();
        decoders.put("M0_ARGMAX", m0.toJson());
        decoders.put("C1_BIO_VITERBI", c1.toJson());
        output.put("decoders", decoders);
        output.put(
                "difference_diagnostics",
                differences.toJson()
        );

        JSONObject sources = new JSONObject();
        sourceResults.forEach(sources::put);
        output.put("by_source", sources);

        output.put(
                "promotion_gate",
                new JSONArray(List.of(
                        "C1 high-risk full-span recall must not be lower than M0.",
                        "C1 sensitive-character recall must not materially regress.",
                        "C1 non-sensitive-character redaction must not materially increase.",
                        "C1 must reduce or eliminate illegal BIO transitions by construction.",
                        "Any M0-full/C1-not-full regression must be inspected before production consideration.",
                        "Production decoder remains unchanged unless the full comparison is favorable."
                ))
        );

        Path parent = resultPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(
                resultPath,
                output.toString(2) + System.lineSeparator(),
                StandardCharsets.UTF_8
        );

        printMetrics(m0);
        printMetrics(c1);
        printDifferences(differences);

        System.out.println();
        System.out.println("BIO transition diagnostics");
        System.out.println(
                "  illegal argmax BIO transitions: "
                        + illegalArgmaxTransitions
        );
        System.out.println(
                "  token labels changed by C1: "
                        + changedTokenLabels
        );
        System.out.println(
                "  argmax entity tokens: "
                        + argmaxEntityTokens
        );
        System.out.println(
                "  changed/entity-token rate: "
                        + percent(
                                ratio(
                                        changedTokenLabels,
                                        argmaxEntityTokens
                                )
                        )
        );
        System.out.println(
                "  records/windowed/windows: "
                        + records
                        + " / "
                        + windowedRecords
                        + " / "
                        + windows
        );
        System.out.println(
                "  ONNX calls/max windows per call: "
                        + onnxCalls
                        + " / "
                        + maxWindowsPerCall
        );
        System.out.println("Result: " + resultPath);
    }

    private static BatchResult evaluateBatch(
            String source,
            List<RecordItem> batch,
            ParallelTokenizer tokenizer,
            InferenceSession inference,
            QualityMetrics m0,
            QualityMetrics c1,
            QualityMetrics sourceM0,
            QualityMetrics sourceC1,
            DifferenceDiagnostics differences
    ) throws Exception {
        List<String> texts =
                batch.stream().map(RecordItem::text).toList();

        InferenceResult inferenceResult =
                inference.infer(texts, tokenizer);

        long windowedRecords = 0;
        for (int i = 0; i < batch.size(); i++) {
            RecordItem record = batch.get(i);
            DecoderPair predictions =
                    inferenceResult.predictions().get(i);

            if (predictions.windowCount() > 1) {
                windowedRecords++;
            }

            List<PredictedSpan> m0Spans =
                    predicted(predictions.argmaxSpans());
            List<PredictedSpan> c1Spans =
                    predicted(predictions.constrainedSpans());

            m0.addRecord(
                    record.text(),
                    record.gold(),
                    m0Spans
            );
            c1.addRecord(
                    record.text(),
                    record.gold(),
                    c1Spans
            );
            sourceM0.addRecord(
                    record.text(),
                    record.gold(),
                    m0Spans
            );
            sourceC1.addRecord(
                    record.text(),
                    record.gold(),
                    c1Spans
            );

            differences.compare(
                    source,
                    record.text(),
                    record.gold(),
                    m0Spans,
                    c1Spans
            );
        }

        return new BatchResult(
                inferenceResult.inferenceWindows(),
                windowedRecords,
                inferenceResult.illegalArgmaxTransitions(),
                inferenceResult.changedTokenLabels(),
                inferenceResult.argmaxEntityTokens(),
                inferenceResult.onnxCalls(),
                inferenceResult.maxWindowsPerCall()
        );
    }

    private static List<PredictedSpan> predicted(
            List<LabelAwareMaskingEngine.EntitySpan> spans
    ) {
        List<PredictedSpan> result =
                new ArrayList<>(spans.size());
        for (LabelAwareMaskingEngine.EntitySpan span : spans) {
            result.add(
                    new PredictedSpan(
                            span.start(),
                            span.end(),
                            span.entityType()
                    )
            );
        }
        return List.copyOf(result);
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
        return List.copyOf(result);
    }

    private static void printMetrics(QualityMetrics metrics) {
        JSONObject json = metrics.toJson();
        System.out.println();
        System.out.println(metrics.id + " quality");
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
                        + percent(
                                json.getDouble("full_span_recall")
                        )
        );
        System.out.println(
                "  exact-boundary recall: "
                        + percent(
                                json.getDouble(
                                        "exact_boundary_recall"
                                )
                        )
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
                "  partial / missed gold spans: "
                        + json.getLong("partial_gold_spans")
                        + " / "
                        + json.getLong("missed_gold_spans")
        );
    }

    private static void printDifferences(
            DifferenceDiagnostics diagnostics
    ) {
        JSONObject json = diagnostics.toJson();

        System.out.println();
        System.out.println("M0 -> C1 difference diagnostics");
        System.out.println(
                "  records with changed predicted spans: "
                        + json.getLong("records_with_changed_predictions")
        );
        System.out.println(
                "  M0 full / C1 not full: "
                        + json.getLong("m0_full_c1_not_full")
        );
        System.out.println(
                "  high-risk regressions: "
                        + json.getLong("high_risk_regressions")
        );
        System.out.println(
                "  C1 full / M0 not full: "
                        + json.getLong("c1_full_m0_not_full")
        );
        System.out.println(
                "  exact-boundary losses / gains: "
                        + json.getLong("exact_boundary_losses")
                        + " / "
                        + json.getLong("exact_boundary_gains")
        );
    }

    private static String percent(double value) {
        return String.format("%.4f%%", value * 100.0);
    }

    private static double ratio(long numerator, long denominator) {
        return denominator == 0
                ? 0.0
                : (double) numerator / denominator;
    }

    private static final class InferenceSession
            implements AutoCloseable {

        private final OrtEnvironment env;
        private final OrtSession session;
        private final LabelAwareMaskingEngine argmaxDecoder =
                new LabelAwareMaskingEngine();
        private final BioConstrainedDecoder constrainedDecoder =
                new BioConstrainedDecoder();
        private final int maxInferenceWindowsPerBatch;

        private InferenceSession(
                SecureLogXConfig config
        ) throws Exception {
            env = OrtEnvironment.getEnvironment();
            maxInferenceWindowsPerBatch =
                    config.getMaxInferenceWindowsPerBatch();

            OrtSession created;
            try (OrtSession.SessionOptions options =
                         new OrtSession.SessionOptions()) {
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

                created = env.createSession(
                        config.getModelPath().replace("\\", "/"),
                        options
                );
            }
            session = created;
        }

        private InferenceResult infer(
                List<String> texts,
                ParallelTokenizer tokenizer
        ) throws Exception {
            List<TokenizedInput> flatWindows = new ArrayList<>();
            List<Integer> originalIndices = new ArrayList<>();
            int[] windowsPerRecord = new int[texts.size()];

            for (int recordIndex = 0;
                 recordIndex < texts.size();
                 recordIndex++) {
                List<TokenizedInput> windows =
                        tokenizer.tokenizeWindows(
                                texts.get(recordIndex),
                                WINDOW_OVERLAP_CONTENT_TOKENS
                        );

                if (windows.isEmpty()) {
                    throw new IllegalStateException(
                            "Tokenizer returned no inference windows"
                    );
                }

                windowsPerRecord[recordIndex] = windows.size();

                for (TokenizedInput window : windows) {
                    if (window.isTruncated()) {
                        throw new IllegalStateException(
                                "Windowed tokenizer returned truncated input"
                        );
                    }
                    flatWindows.add(window);
                    originalIndices.add(recordIndex);
                }
            }

            List<List<LabelAwareMaskingEngine.EntitySpan>>
                    argmaxByRecord = emptySpanLists(texts.size());
            List<List<LabelAwareMaskingEngine.EntitySpan>>
                    constrainedByRecord = emptySpanLists(texts.size());

            long illegalTransitions = 0;
            long changedLabels = 0;
            long argmaxEntityTokens = 0;
            long onnxCalls = 0;
            long maxWindowsPerCall = 0;

            for (int start = 0;
                 start < flatWindows.size();
                 start += maxInferenceWindowsPerBatch) {
                int end = Math.min(
                        start + maxInferenceWindowsPerBatch,
                        flatWindows.size()
                );

                List<TokenizedInput> chunk =
                        flatWindows.subList(start, end);
                int seqLen = chunk.stream()
                        .mapToInt(item -> item.getInputIds().length)
                        .max()
                        .orElse(0);

                long[][] inputIds =
                        new long[chunk.size()][seqLen];
                long[][] attentionMask =
                        new long[chunk.size()][seqLen];
                long[][] tokenTypeIds =
                        new long[chunk.size()][seqLen];

                for (int local = 0;
                     local < chunk.size();
                     local++) {
                    int[] ids = chunk.get(local).getInputIds();
                    int[] mask =
                            chunk.get(local).getAttentionMask();

                    for (int j = 0; j < ids.length; j++) {
                        inputIds[local][j] = ids[j];
                        attentionMask[local][j] = mask[j];
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

                    try (OrtSession.Result result =
                                 session.run(inputs)) {
                        float[][][] logits =
                                (float[][][]) result.get(0).getValue();

                        for (int local = 0;
                             local < chunk.size();
                             local++) {
                            int flatIndex = start + local;
                            int recordIndex =
                                    originalIndices.get(flatIndex);
                            String text = texts.get(recordIndex);
                            TokenizedInput tokenized =
                                    chunk.get(local);

                            List<LabelAwareMaskingEngine.EntitySpan>
                                    argmaxSpans =
                                    argmaxDecoder.decodeSpans(
                                            text,
                                            new float[][][]{
                                                    logits[local]
                                            },
                                            tokenized.getOffsets()
                                    );

                            BioConstrainedDecoder.DecodeResult
                                    constrained =
                                    constrainedDecoder.decode(
                                            text,
                                            logits[local],
                                            tokenized.getOffsets()
                                    );

                            argmaxByRecord.get(recordIndex)
                                    .addAll(argmaxSpans);
                            constrainedByRecord.get(recordIndex)
                                    .addAll(constrained.spans());

                            illegalTransitions +=
                                    constrained
                                            .illegalArgmaxTransitions();
                            changedLabels +=
                                    constrained.changedTokenLabels();
                            argmaxEntityTokens +=
                                    constrained.argmaxEntityTokens();
                        }
                    }
                }

                onnxCalls++;
                maxWindowsPerCall = Math.max(
                        maxWindowsPerCall,
                        chunk.size()
                );
            }

            List<DecoderPair> pairs =
                    new ArrayList<>(texts.size());
            for (int i = 0; i < texts.size(); i++) {
                pairs.add(
                        new DecoderPair(
                                mergeWindowSpans(argmaxByRecord.get(i)),
                                mergeWindowSpans(
                                        constrainedByRecord.get(i)
                                ),
                                windowsPerRecord[i]
                        )
                );
            }

            return new InferenceResult(
                    List.copyOf(pairs),
                    flatWindows.size(),
                    illegalTransitions,
                    changedLabels,
                    argmaxEntityTokens,
                    onnxCalls,
                    maxWindowsPerCall
            );
        }

        @Override
        public void close() throws Exception {
            session.close();
        }
    }

    private static List<List<LabelAwareMaskingEngine.EntitySpan>>
            emptySpanLists(int count) {
        List<List<LabelAwareMaskingEngine.EntitySpan>> result =
                new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            result.add(new ArrayList<>());
        }
        return result;
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
                current =
                        new LabelAwareMaskingEngine.EntitySpan(
                                Math.min(
                                        current.start(),
                                        span.start()
                                ),
                                Math.max(
                                        current.end(),
                                        span.end()
                                ),
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

    private static final class QualityMetrics {
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
        private long recordsPerfectRedaction;
        private long predictedSpans;

        private QualityMetrics(String id) {
            this.id = id;
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

            if (recordSensitive == recordSensitiveRedacted
                    && recordNonSensitiveRedacted == 0) {
                recordsPerfectRedaction++;
            }

            for (GoldSpan goldSpan : gold) {
                goldSpans++;

                int start = bounded(
                        goldSpan.start(),
                        text.length()
                );
                int end = bounded(
                        goldSpan.end(),
                        text.length()
                );

                boolean any = false;
                boolean all = true;
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

                if (hasExactBoundary(goldSpan, predicted)) {
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
            object.put(
                    "records_with_sensitive",
                    recordsWithSensitive
            );
            object.put("predicted_spans", predictedSpans);
            object.put("gold_spans", goldSpans);
            object.put("full_gold_spans", fullGoldSpans);
            object.put("partial_gold_spans", partialGoldSpans);
            object.put("missed_gold_spans", missedGoldSpans);
            object.put(
                    "exact_boundary_gold_spans",
                    exactBoundaryGoldSpans
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
                    "sensitive_character_recall",
                    ratio(
                            sensitiveCharactersRedacted,
                            sensitiveCharacters
                    )
            );
            object.put(
                    "non_sensitive_character_redaction_rate",
                    ratio(
                            nonSensitiveCharactersRedacted,
                            nonSensitiveCharacters
                    )
            );
            object.put(
                    "full_span_recall",
                    ratio(fullGoldSpans, goldSpans)
            );
            object.put(
                    "exact_boundary_recall",
                    ratio(
                            exactBoundaryGoldSpans,
                            goldSpans
                    )
            );
            object.put(
                    "high_risk_full_span_recall",
                    ratio(
                            highRiskFullGoldSpans,
                            highRiskGoldSpans
                    )
            );
            object.put(
                    "whole_record_perfect_redaction_rate",
                    ratio(recordsPerfectRedaction, records)
            );
            return object;
        }
    }

    private static final class DifferenceDiagnostics {
        private static final int MAX_SAMPLES = 30;

        private long recordsWithChangedPredictions;
        private long m0FullC1NotFull;
        private long highRiskRegressions;
        private long c1FullM0NotFull;
        private long exactBoundaryLosses;
        private long exactBoundaryGains;

        private final Map<String, Long> regressionsByLabel =
                new LinkedHashMap<>();
        private final Map<String, Long> gainsByLabel =
                new LinkedHashMap<>();
        private final List<String> samples = new ArrayList<>();

        private void compare(
                String source,
                String text,
                List<GoldSpan> gold,
                List<PredictedSpan> m0,
                List<PredictedSpan> c1
        ) {
            if (!canonicalSpans(m0).equals(canonicalSpans(c1))) {
                recordsWithChangedPredictions++;
            }

            for (GoldSpan span : gold) {
                boolean m0Full = fullyCovered(span, m0);
                boolean c1Full = fullyCovered(span, c1);

                if (m0Full && !c1Full) {
                    m0FullC1NotFull++;
                    regressionsByLabel.merge(
                            span.label(),
                            1L,
                            Long::sum
                    );
                    if (HIGH_RISK.contains(span.label())) {
                        highRiskRegressions++;
                    }
                    addSample(
                            source,
                            text,
                            span,
                            "FULL_REGRESSION",
                            m0,
                            c1
                    );
                } else if (!m0Full && c1Full) {
                    c1FullM0NotFull++;
                    gainsByLabel.merge(
                            span.label(),
                            1L,
                            Long::sum
                    );
                    addSample(
                            source,
                            text,
                            span,
                            "FULL_GAIN",
                            m0,
                            c1
                    );
                }

                boolean m0Exact =
                        hasExactBoundary(span, m0);
                boolean c1Exact =
                        hasExactBoundary(span, c1);

                if (m0Exact && !c1Exact) {
                    exactBoundaryLosses++;
                } else if (!m0Exact && c1Exact) {
                    exactBoundaryGains++;
                }
            }
        }

        private void addSample(
                String source,
                String text,
                GoldSpan gold,
                String kind,
                List<PredictedSpan> m0,
                List<PredictedSpan> c1
        ) {
            if (samples.size() >= MAX_SAMPLES) {
                return;
            }

            samples.add(
                    source
                            + " "
                            + kind
                            + " gold="
                            + gold.label()
                            + "["
                            + gold.start()
                            + ","
                            + gold.end()
                            + ") value='"
                            + safeSlice(
                                    text,
                                    gold.start(),
                                    gold.end()
                            ).replace("'", "\\'")
                            + "' m0="
                            + overlaps(gold, m0)
                            + " c1="
                            + overlaps(gold, c1)
            );
        }

        private JSONObject toJson() {
            JSONObject object = new JSONObject();
            object.put(
                    "records_with_changed_predictions",
                    recordsWithChangedPredictions
            );
            object.put(
                    "m0_full_c1_not_full",
                    m0FullC1NotFull
            );
            object.put(
                    "high_risk_regressions",
                    highRiskRegressions
            );
            object.put(
                    "c1_full_m0_not_full",
                    c1FullM0NotFull
            );
            object.put(
                    "exact_boundary_losses",
                    exactBoundaryLosses
            );
            object.put(
                    "exact_boundary_gains",
                    exactBoundaryGains
            );
            object.put(
                    "regressions_by_gold_label",
                    countsJson(regressionsByLabel)
            );
            object.put(
                    "gains_by_gold_label",
                    countsJson(gainsByLabel)
            );
            object.put("samples", new JSONArray(samples));
            return object;
        }
    }

    private static boolean hasExactBoundary(
            GoldSpan gold,
            List<PredictedSpan> predicted
    ) {
        for (PredictedSpan span : predicted) {
            if (span.start() == gold.start()
                    && span.end() == gold.end()) {
                return true;
            }
        }
        return false;
    }

    private static boolean fullyCovered(
            GoldSpan gold,
            List<PredictedSpan> predicted
    ) {
        for (int position = gold.start();
             position < gold.end();
             position++) {
            boolean covered = false;
            for (PredictedSpan span : predicted) {
                if (span.start() <= position
                        && span.end() > position) {
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

    private static List<String> canonicalSpans(
            List<PredictedSpan> spans
    ) {
        return spans.stream()
                .map(
                        span -> span.entityType()
                                + ":"
                                + span.start()
                                + ":"
                                + span.end()
                )
                .sorted()
                .toList();
    }

    private static List<String> overlaps(
            GoldSpan gold,
            List<PredictedSpan> spans
    ) {
        List<String> result = new ArrayList<>();
        for (PredictedSpan span : spans) {
            if (Math.max(gold.start(), span.start())
                    < Math.min(gold.end(), span.end())) {
                result.add(
                        span.entityType()
                                + "["
                                + span.start()
                                + ","
                                + span.end()
                                + ")"
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
        int start = bounded(rawStart, text.length());
        int end = Math.max(
                start,
                bounded(rawEnd, text.length())
        );
        return text.substring(start, end);
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

    private static JSONObject countsJson(
            Map<String, Long> counts
    ) {
        JSONObject object = new JSONObject();
        counts.entrySet().stream()
                .sorted(
                        (left, right) ->
                                Long.compare(
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
            String entityType
    ) {
    }

    private record DecoderPair(
            List<LabelAwareMaskingEngine.EntitySpan> argmaxSpans,
            List<LabelAwareMaskingEngine.EntitySpan> constrainedSpans,
            int windowCount
    ) {
        private DecoderPair {
            argmaxSpans = List.copyOf(argmaxSpans);
            constrainedSpans = List.copyOf(constrainedSpans);
        }
    }

    private record InferenceResult(
            List<DecoderPair> predictions,
            long inferenceWindows,
            long illegalArgmaxTransitions,
            long changedTokenLabels,
            long argmaxEntityTokens,
            long onnxCalls,
            long maxWindowsPerCall
    ) {
        private InferenceResult {
            predictions = List.copyOf(predictions);
        }
    }

    private record BatchResult(
            long windows,
            long windowedRecords,
            long illegalArgmaxTransitions,
            long changedTokenLabels,
            long argmaxEntityTokens,
            long onnxCalls,
            long maxWindowsPerCall
    ) {
    }
}
