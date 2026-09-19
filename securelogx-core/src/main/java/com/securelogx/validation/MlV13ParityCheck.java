package com.securelogx.validation;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import com.securelogx.ner.TokenizedInput;
import com.securelogx.ner.impl.LabelAwareMaskingEngine;
import com.securelogx.ner.impl.PureJavaTokenizer;
import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cross-runtime parity checker for the frozen SecureLogX ML-v1.3 BERT model.
 *
 * Validates Java against a Python-generated fixture at four levels:
 * 1. WordPiece token IDs
 * 2. attention mask and character offsets
 * 3. ONNX argmax label IDs
 * 4. decoded entity spans
 */
public final class MlV13ParityCheck {

    private MlV13ParityCheck() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException(
                    "Usage: MlV13ParityCheck <model.onnx> <tokenizer.json> <fixture.json>"
            );
        }

        Path modelPath = Path.of(args[0]);
        Path tokenizerPath = Path.of(args[1]);
        Path fixturePath = Path.of(args[2]);

        JSONObject fixture = new JSONObject(Files.readString(fixturePath));
        int maxLength = fixture.getInt("max_length");

        assertEquals(
                fixture.getString("onnx_sha256"),
                sha256(modelPath),
                "ONNX SHA-256"
        );
        assertEquals(
                fixture.getString("tokenizer_json_sha256"),
                sha256(tokenizerPath),
                "tokenizer.json SHA-256"
        );

        PureJavaTokenizer tokenizer = new PureJavaTokenizer(
                tokenizerPath.toString(),
                maxLength
        );
        LabelAwareMaskingEngine decoder = new LabelAwareMaskingEngine(false);

        int cases = 0;
        int tokenParity = 0;
        int labelParity = 0;
        int spanParity = 0;

        try (OrtEnvironment env = OrtEnvironment.getEnvironment();
             OrtSession.SessionOptions options = new OrtSession.SessionOptions();
             OrtSession session = env.createSession(modelPath.toString(), options)) {

            JSONArray caseArray = fixture.getJSONArray("cases");
            for (int caseIndex = 0; caseIndex < caseArray.length(); caseIndex++) {
                JSONObject expected = caseArray.getJSONObject(caseIndex);
                String text = expected.getString("text");
                TokenizedInput actual = tokenizer.encode(text);

                int[] expectedIds = intArray(expected.getJSONArray("input_ids"));
                int[] expectedMask = intArray(expected.getJSONArray("attention_mask"));
                int[] expectedTypes = intArray(expected.getJSONArray("token_type_ids"));
                List<int[]> expectedOffsets = offsetList(expected.getJSONArray("offsets"));

                assertArrayEquals(expectedIds, actual.getInputIds(), caseIndex, "input_ids");
                assertArrayEquals(expectedMask, actual.getAttentionMask(), caseIndex, "attention_mask");
                assertOffsetsEqual(expectedOffsets, actual.getOffsets(), caseIndex);
                tokenParity++;

                long[][] ids = new long[][]{toLong(actual.getInputIds())};
                long[][] mask = new long[][]{toLong(actual.getAttentionMask())};
                long[][] types = new long[][]{toLong(expectedTypes)};

                float[][][] logits;
                try (OnnxTensor idsTensor = OnnxTensor.createTensor(env, ids);
                     OnnxTensor maskTensor = OnnxTensor.createTensor(env, mask);
                     OnnxTensor typeTensor = OnnxTensor.createTensor(env, types)) {

                    Map<String, OnnxTensor> inputs = Map.of(
                            "input_ids", idsTensor,
                            "attention_mask", maskTensor,
                            "token_type_ids", typeTensor
                    );

                    try (OrtSession.Result result = session.run(inputs)) {
                        logits = (float[][][]) result.get(0).getValue();
                    }
                }

                int[] expectedPredictions =
                        intArray(expected.getJSONArray("predicted_label_ids"));
                int[] actualPredictions = argmax(logits[0]);
                assertArrayEquals(
                        expectedPredictions,
                        actualPredictions,
                        caseIndex,
                        "predicted_label_ids"
                );
                labelParity++;

                List<LabelAwareMaskingEngine.EntitySpan> actualSpans =
                        decoder.decodeSpans(text, logits, actual.getOffsets());
                List<ExpectedSpan> expectedSpans =
                        expectedSpans(expected.getJSONArray("decoded_spans"));

                assertSpansEqual(expectedSpans, actualSpans, caseIndex);
                spanParity++;
                cases++;
            }
        }

        System.out.println("JAVA ML-v1.3 PARITY PASSED");
        System.out.println("Cases: " + cases);
        System.out.println("Tokenizer parity: " + tokenParity + "/" + cases);
        System.out.println("ONNX argmax parity: " + labelParity + "/" + cases);
        System.out.println("Decoded-span parity: " + spanParity + "/" + cases);
        System.out.println("ONNX SHA-256: " + sha256(modelPath));
        System.out.println("Tokenizer SHA-256: " + sha256(tokenizerPath));
    }

    private static int[] intArray(JSONArray array) {
        int[] values = new int[array.length()];
        for (int i = 0; i < array.length(); i++) {
            values[i] = array.getInt(i);
        }
        return values;
    }

    private static List<int[]> offsetList(JSONArray array) {
        List<int[]> result = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONArray pair = array.getJSONArray(i);
            result.add(new int[]{pair.getInt(0), pair.getInt(1)});
        }
        return result;
    }

    private static List<ExpectedSpan> expectedSpans(JSONArray array) {
        List<ExpectedSpan> result = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject span = array.getJSONObject(i);
            result.add(
                    new ExpectedSpan(
                            span.getInt("start"),
                            span.getInt("end"),
                            span.getString("label")
                    )
            );
        }
        return result;
    }

    private static long[] toLong(int[] values) {
        long[] result = new long[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = values[i];
        }
        return result;
    }

    private static int[] argmax(float[][] rows) {
        int[] result = new int[rows.length];
        for (int row = 0; row < rows.length; row++) {
            int best = 0;
            for (int col = 1; col < rows[row].length; col++) {
                if (rows[row][col] > rows[row][best]) {
                    best = col;
                }
            }
            result[row] = best;
        }
        return result;
    }

    private static void assertArrayEquals(
            int[] expected,
            int[] actual,
            int caseIndex,
            String field
    ) {
        if (!Arrays.equals(expected, actual)) {
            throw new IllegalStateException(
                    "Case " + caseIndex + " " + field + " mismatch. expected="
                            + Arrays.toString(expected)
                            + " actual="
                            + Arrays.toString(actual)
            );
        }
    }

    private static void assertOffsetsEqual(
            List<int[]> expected,
            List<int[]> actual,
            int caseIndex
    ) {
        if (expected.size() != actual.size()) {
            throw new IllegalStateException(
                    "Case " + caseIndex + " offset length mismatch. expected="
                            + expected.size() + " actual=" + actual.size()
            );
        }

        for (int i = 0; i < expected.size(); i++) {
            if (!Arrays.equals(expected.get(i), actual.get(i))) {
                throw new IllegalStateException(
                        "Case " + caseIndex + " offset mismatch at token " + i
                                + ". expected=" + Arrays.toString(expected.get(i))
                                + " actual=" + Arrays.toString(actual.get(i))
                );
            }
        }
    }

    private static void assertSpansEqual(
            List<ExpectedSpan> expected,
            List<LabelAwareMaskingEngine.EntitySpan> actual,
            int caseIndex
    ) {
        if (expected.size() != actual.size()) {
            throw new IllegalStateException(
                    "Case " + caseIndex + " decoded span count mismatch. expected="
                            + expected + " actual=" + actual
            );
        }

        for (int i = 0; i < expected.size(); i++) {
            ExpectedSpan left = expected.get(i);
            LabelAwareMaskingEngine.EntitySpan right = actual.get(i);
            if (left.start != right.start()
                    || left.end != right.end()
                    || !left.label.equals(right.entityType())) {
                throw new IllegalStateException(
                        "Case " + caseIndex + " decoded span mismatch at index " + i
                                + ". expected=" + left + " actual=" + right
                );
            }
        }
    }

    private static void assertEquals(String expected, String actual, String field) {
        if (!expected.equals(actual)) {
            throw new IllegalStateException(
                    field + " mismatch. expected=" + expected + " actual=" + actual
            );
        }
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] data = Files.readAllBytes(path);
        return HexFormat.of().formatHex(digest.digest(data));
    }

    private record ExpectedSpan(int start, int end, String label) {
    }
}
