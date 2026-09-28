package com.securelogx.validation;

import com.securelogx.ner.impl.LabelAwareMaskingEngine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Experimental strict-BIO Viterbi decoder for the frozen ML-v1.3 ontology.
 *
 * Production decoding remains unchanged. This class exists only for the
 * constrained-decoding comparison benchmark.
 *
 * BIO constraints:
 * - a sequence cannot start with I-X,
 * - I-X may follow only B-X or I-X,
 * - O and B-X may follow any valid prior state.
 *
 * Complexity is O(tokens * labels), not O(tokens * labels^2), because every
 * O/B state shares the same best unrestricted predecessor while an I-X state
 * has only two valid predecessors.
 */
final class BioConstrainedDecoder {

    private static final String[] LABELS = {
            "O",
            "B-PERSON_NAME", "I-PERSON_NAME",
            "B-DOB", "I-DOB",
            "B-AGE", "I-AGE",
            "B-SSN", "I-SSN",
            "B-ITIN", "I-ITIN",
            "B-TAX_ID", "I-TAX_ID",
            "B-EMAIL", "I-EMAIL",
            "B-PHONE", "I-PHONE",
            "B-STREET_ADDRESS", "I-STREET_ADDRESS",
            "B-CITY", "I-CITY",
            "B-STATE_PROVINCE", "I-STATE_PROVINCE",
            "B-POSTAL_CODE", "I-POSTAL_CODE",
            "B-COUNTRY", "I-COUNTRY",
            "B-CREDIT_CARD_NUMBER", "I-CREDIT_CARD_NUMBER",
            "B-BANK_ACCOUNT_NUMBER", "I-BANK_ACCOUNT_NUMBER",
            "B-ROUTING_NUMBER", "I-ROUTING_NUMBER",
            "B-IBAN", "I-IBAN",
            "B-SWIFT_BIC", "I-SWIFT_BIC",
            "B-BUSINESS_ID", "I-BUSINESS_ID",
            "B-PASSPORT_NUMBER", "I-PASSPORT_NUMBER",
            "B-DRIVER_LICENSE", "I-DRIVER_LICENSE",
            "B-IP_ADDRESS", "I-IP_ADDRESS",
            "B-DEVICE_ID", "I-DEVICE_ID",
            "B-AUTH_TOKEN", "I-AUTH_TOKEN",
            "B-API_KEY", "I-API_KEY"
    };

    private static final float NEG_INF =
            -Float.MAX_VALUE / 4.0f;

    DecodeResult decode(
            String originalText,
            float[][] logits,
            List<int[]> offsets
    ) {
        int totalTokens = Math.min(logits.length, offsets.size());
        List<Integer> activeTokenIndices = new ArrayList<>();

        for (int tokenIndex = 0;
             tokenIndex < totalTokens;
             tokenIndex++) {
            int[] offset = offsets.get(tokenIndex);
            if (offset != null
                    && offset.length >= 2
                    && offset[0] >= 0
                    && offset[1] > offset[0]) {
                activeTokenIndices.add(tokenIndex);
            }
        }

        if (activeTokenIndices.isEmpty()) {
            return new DecodeResult(
                    List.of(),
                    0,
                    0,
                    0
            );
        }

        int illegalArgmaxTransitions =
                countIllegalArgmaxTransitions(
                        logits,
                        activeTokenIndices
                );

        int tokenCount = activeTokenIndices.size();
        int labelCount = LABELS.length;

        float[] previous = new float[labelCount];
        float[] current = new float[labelCount];
        Arrays.fill(previous, NEG_INF);

        int[][] backPointers =
                new int[tokenCount][labelCount];

        int firstRawIndex = activeTokenIndices.get(0);
        validateLogitWidth(logits[firstRawIndex]);

        for (int state = 0; state < labelCount; state++) {
            if (isInside(state)) {
                previous[state] = NEG_INF;
                backPointers[0][state] = -1;
            } else {
                previous[state] =
                        logits[firstRawIndex][state];
                backPointers[0][state] = -1;
            }
        }

        for (int activeIndex = 1;
             activeIndex < tokenCount;
             activeIndex++) {
            int rawIndex = activeTokenIndices.get(activeIndex);
            validateLogitWidth(logits[rawIndex]);

            int unrestrictedBestState = argmax(previous);
            float unrestrictedBestScore =
                    previous[unrestrictedBestState];

            for (int state = 0;
                 state < labelCount;
                 state++) {
                if (!isInside(state)) {
                    current[state] =
                            unrestrictedBestScore
                                    + logits[rawIndex][state];
                    backPointers[activeIndex][state] =
                            unrestrictedBestState;
                    continue;
                }

                int beginState = state - 1;
                int insideState = state;

                int bestPrior =
                        previous[beginState]
                                >= previous[insideState]
                                ? beginState
                                : insideState;

                current[state] =
                        previous[bestPrior]
                                + logits[rawIndex][state];
                backPointers[activeIndex][state] =
                        bestPrior;
            }

            float[] swap = previous;
            previous = current;
            current = swap;
            Arrays.fill(current, NEG_INF);
        }

        int[] path = new int[tokenCount];
        path[tokenCount - 1] = argmax(previous);

        for (int i = tokenCount - 1; i > 0; i--) {
            path[i - 1] =
                    backPointers[i][path[i]];
        }

        List<LabelAwareMaskingEngine.EntitySpan> spans =
                spansFromPath(
                        originalText,
                        offsets,
                        activeTokenIndices,
                        path
                );

        int changedTokenLabels = 0;
        int argmaxEntityTokens = 0;
        for (int i = 0; i < tokenCount; i++) {
            int rawIndex = activeTokenIndices.get(i);
            int rawArgmax = argmax(logits[rawIndex]);
            if (rawArgmax != 0) {
                argmaxEntityTokens++;
            }
            if (rawArgmax != path[i]) {
                changedTokenLabels++;
            }
        }

        return new DecodeResult(
                spans,
                illegalArgmaxTransitions,
                changedTokenLabels,
                argmaxEntityTokens
        );
    }

    private static List<LabelAwareMaskingEngine.EntitySpan>
            spansFromPath(
                    String text,
                    List<int[]> offsets,
                    List<Integer> activeTokenIndices,
                    int[] path
            ) {
        List<LabelAwareMaskingEngine.EntitySpan> spans =
                new ArrayList<>();

        MutableSpan current = null;

        for (int i = 0; i < path.length; i++) {
            int state = path[i];
            String label = LABELS[state];
            int[] offset =
                    offsets.get(activeTokenIndices.get(i));

            if ("O".equals(label)) {
                if (current != null) {
                    addNormalized(text, current, spans);
                    current = null;
                }
                continue;
            }

            String entityType = label.substring(2);
            if (label.startsWith("B-")) {
                if (current != null) {
                    addNormalized(text, current, spans);
                }
                current = new MutableSpan(
                        offset[0],
                        offset[1],
                        entityType
                );
                continue;
            }

            // Viterbi guarantees a valid same-entity B/I predecessor.
            if (current == null
                    || !current.entityType.equals(entityType)) {
                throw new IllegalStateException(
                        "BIO-constrained path produced invalid I-transition"
                );
            }
            current.end = offset[1];
        }

        if (current != null) {
            addNormalized(text, current, spans);
        }

        return List.copyOf(spans);
    }

    private static void addNormalized(
            String text,
            MutableSpan candidate,
            List<LabelAwareMaskingEngine.EntitySpan> output
    ) {
        int start = Math.max(
                0,
                Math.min(text.length(), candidate.start)
        );
        int end = Math.max(
                start,
                Math.min(text.length(), candidate.end)
        );

        while (start < end
                && Character.isWhitespace(text.charAt(start))) {
            start++;
        }
        while (end > start
                && Character.isWhitespace(text.charAt(end - 1))) {
            end--;
        }

        if (start < end) {
            output.add(
                    new LabelAwareMaskingEngine.EntitySpan(
                            start,
                            end,
                            candidate.entityType
                    )
            );
        }
    }

    private static int countIllegalArgmaxTransitions(
            float[][] logits,
            List<Integer> activeTokenIndices
    ) {
        int illegal = 0;
        int previous = -1;

        for (int i = 0;
             i < activeTokenIndices.size();
             i++) {
            int rawIndex = activeTokenIndices.get(i);
            validateLogitWidth(logits[rawIndex]);
            int current = argmax(logits[rawIndex]);

            if (isInside(current)) {
                if (i == 0
                        || (previous != current
                        && previous != current - 1)) {
                    illegal++;
                }
            }

            previous = current;
        }
        return illegal;
    }

    private static boolean isInside(int state) {
        return state > 0 && state % 2 == 0;
    }

    private static int argmax(float[] scores) {
        int best = 0;
        for (int i = 1; i < scores.length; i++) {
            if (scores[i] > scores[best]) {
                best = i;
            }
        }
        return best;
    }

    private static void validateLogitWidth(float[] scores) {
        if (scores.length < LABELS.length) {
            throw new IllegalStateException(
                    "Expected at least "
                            + LABELS.length
                            + " label logits, got "
                            + scores.length
            );
        }
    }

    record DecodeResult(
            List<LabelAwareMaskingEngine.EntitySpan> spans,
            int illegalArgmaxTransitions,
            int changedTokenLabels,
            int argmaxEntityTokens
    ) {
        DecodeResult {
            spans = List.copyOf(spans);
        }
    }

    private static final class MutableSpan {
        private int start;
        private int end;
        private final String entityType;

        private MutableSpan(
                int start,
                int end,
                String entityType
        ) {
            this.start = start;
            this.end = end;
            this.entityType = entityType;
        }
    }
}
