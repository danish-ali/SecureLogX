package com.securelogx.validation;

import com.securelogx.config.SecureLogXConfig;
import com.securelogx.model.LogEvent;
import com.securelogx.model.LogLevel;
import com.securelogx.ner.impl.ONNXDynamicInferenceEngine;
import com.securelogx.ner.impl.ParallelTokenizer;
import com.securelogx.util.ArtifactIntegrityVerifier;
import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Exercises the production ONNXDynamicInferenceEngine masking path using cases
 * whose expected entity spans were frozen by the Python/ONNX parity fixture.
 */
public final class MlV13RuntimeSmokeCheck {

    private static final int MAX_CASES = 16;

    private MlV13RuntimeSmokeCheck() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException(
                    "Usage: MlV13RuntimeSmokeCheck <fixture.json> <result.json>"
            );
        }

        Path fixturePath = Path.of(args[0]);
        Path resultPath = Path.of(args[1]);
        JSONObject fixture = new JSONObject(Files.readString(fixturePath));

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

        List<SmokeCase> smokeCases = selectCases(fixture.getJSONArray("cases"));
        List<LogEvent> events = new ArrayList<>();
        for (int i = 0; i < smokeCases.size(); i++) {
            SmokeCase item = smokeCases.get(i);
            events.add(
                    new LogEvent(
                            item.text(),
                            LogLevel.SECURE,
                            false,
                            "ml-v1.3-smoke",
                            i + 1
                    )
            );
        }

        List<String> outputs;
        try (RuntimeEngine engine = new RuntimeEngine(config)) {
            outputs = engine.run(tokenizer, events);
        }

        if (outputs.size() != smokeCases.size()) {
            throw new IllegalStateException(
                    "Output count mismatch. expected="
                            + smokeCases.size()
                            + " actual="
                            + outputs.size()
            );
        }

        int exactSpanMaskChecks = 0;
        for (int i = 0; i < smokeCases.size(); i++) {
            SmokeCase item = smokeCases.get(i);
            String output = outputs.get(i);

            if (output.contains("[PROCESSING_FAILED]")) {
                throw new IllegalStateException(
                        "Case " + i + " fell back to PROCESSING_FAILED"
                );
            }

            String maskedMessage = extractMessage(output);
            if (maskedMessage.length() != item.text().length()) {
                throw new IllegalStateException(
                        "Case " + i + " masked-message length changed. expected="
                                + item.text().length()
                                + " actual="
                                + maskedMessage.length()
                );
            }

            for (RawEntity entity : item.entities()) {
                exactSpanMaskChecks++;
                String actualMasked = maskedMessage.substring(
                        entity.start(),
                        entity.end()
                );
                String expectedMasked = fullyMasked(entity.rawText());

                if (!actualMasked.equals(expectedMasked)) {
                    throw new IllegalStateException(
                            "Case " + i + " entity span was not masked exactly. label="
                                    + entity.label()
                                    + " span=["
                                    + entity.start()
                                    + ","
                                    + entity.end()
                                    + ") raw="
                                    + entity.rawText()
                                    + " expectedMasked="
                                    + expectedMasked
                                    + " actualMasked="
                                    + actualMasked
                    );
                }
            }
        }

        JSONObject result = new JSONObject();
        result.put("status", "ML-v1.3 JAVA RUNTIME SMOKE PASSED");
        result.put("passed", true);
        result.put("cases", smokeCases.size());
        result.put("exact_span_mask_checks", exactSpanMaskChecks);
        result.put("processing_failures", 0);
        result.put("max_sequence_length", config.getMaxSequenceLength());
        result.put("model_sha256", config.getModelSha256());
        result.put("tokenizer_sha256", config.getTokenizerSha256());
        result.put("sealed_challenge_inference", false);

        Path parent = resultPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(
                resultPath,
                result.toString(2) + System.lineSeparator()
        );

        System.out.println("ML-v1.3 JAVA RUNTIME SMOKE PASSED");
        System.out.println("Cases: " + smokeCases.size());
        System.out.println("Exact span mask checks: " + exactSpanMaskChecks);
        System.out.println("Processing failures: 0");
        System.out.println("Result: " + resultPath);
    }

    private static List<SmokeCase> selectCases(JSONArray cases) {
        List<SmokeCase> selected = new ArrayList<>();

        for (int i = 0; i < cases.length() && selected.size() < MAX_CASES; i++) {
            JSONObject item = cases.getJSONObject(i);
            JSONArray spans = item.getJSONArray("decoded_spans");
            if (spans.length() == 0) {
                continue;
            }

            String text = item.getString("text");
            List<RawEntity> entities = new ArrayList<>();
            boolean valid = true;

            for (int j = 0; j < spans.length(); j++) {
                JSONObject span = spans.getJSONObject(j);
                int start = span.getInt("start");
                int end = span.getInt("end");
                if (start < 0 || end <= start || end > text.length()) {
                    valid = false;
                    break;
                }

                String raw = text.substring(start, end);
                if (raw.chars().noneMatch(Character::isLetterOrDigit)) {
                    valid = false;
                    break;
                }
                entities.add(
                        new RawEntity(
                                span.getString("label"),
                                start,
                                end,
                                raw
                        )
                );
            }

            if (valid && !entities.isEmpty()) {
                selected.add(new SmokeCase(text, entities));
            }
        }

        if (selected.isEmpty()) {
            throw new IllegalStateException(
                    "No suitable entity-bearing cases found in parity fixture"
            );
        }
        return selected;
    }

    private record SmokeCase(String text, List<RawEntity> entities) {
    }

    private static String extractMessage(String formattedOutput) {
        String marker = " message=\"";
        int start = formattedOutput.indexOf(marker);
        if (start < 0) {
            throw new IllegalStateException(
                    "Formatted runtime output does not contain message marker: "
                            + formattedOutput
            );
        }
        start += marker.length();

        int end = formattedOutput.lastIndexOf('"');
        if (end < start) {
            throw new IllegalStateException(
                    "Formatted runtime output does not terminate message field: "
                            + formattedOutput
            );
        }
        return formattedOutput.substring(start, end);
    }

    private static String fullyMasked(String rawText) {
        StringBuilder masked = new StringBuilder(rawText.length());
        for (int i = 0; i < rawText.length(); i++) {
            char c = rawText.charAt(i);
            masked.append(Character.isLetterOrDigit(c) ? '*' : c);
        }
        return masked.toString();
    }

    private record RawEntity(
            String label,
            int start,
            int end,
            String rawText
    ) {
    }

    private static final class RuntimeEngine implements AutoCloseable {
        private final ONNXDynamicInferenceEngine delegate;

        private RuntimeEngine(SecureLogXConfig config) throws Exception {
            this.delegate = new ONNXDynamicInferenceEngine(
                    config.getModelPath(),
                    config
            );
        }

        private List<String> run(
                ParallelTokenizer tokenizer,
                List<LogEvent> events
        ) {
            return delegate.runBatch(tokenizer::tokenize, events);
        }

        @Override
        public void close() {
            delegate.shutdown();
        }
    }
}
