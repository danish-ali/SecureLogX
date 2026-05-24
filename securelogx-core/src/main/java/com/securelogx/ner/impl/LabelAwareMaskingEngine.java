package com.securelogx.ner.impl;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LabelAwareMaskingEngine
 * - First masks using regex fallback (SSN, EMAIL) on original text
 * - Then applies AI model masking (from ONNX)
 * - Decides to show last 4 digits only for specific entity types
 */
public class LabelAwareMaskingEngine {

    private final String[] labelMap;
    private final boolean enableFallback;
    // Fallback regex patterns
    private static final Pattern SSN_PATTERN = Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("\\b[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}\\b");

    public LabelAwareMaskingEngine(boolean enableFallback) {
        this.enableFallback = enableFallback;
        this.labelMap = new String[]{
                "O", "B-EMAIL", "I-EMAIL", "B-SSN", "I-SSN",
                "B-NPI", "I-NPI", "B-ADDRESS", "I-ADDRESS",
                "B-PHONE", "I-PHONE"
        };
    }

    /** Default: fallback enabled */
    public LabelAwareMaskingEngine() {
        this(false);
    }

    /**
     * @param originalText   the raw log text (plain, XML or JSON)
     * @param inputIds       token IDs from your HF tokenizer
     * @param logits         ONNX model outputs
     * @param offsets        token→char offsets
     * @param showLastFour   whether to reveal last-4 digits
     */
        public String mask(String originalText, int[] inputIds, float[][][] logits, List<int[]> offsets, boolean showLastFour) {
            // 1) optionally apply regex fallback
            String textForAI = enableFallback
                    ? fallbackMask(originalText)
                    : originalText;

            // 2) AI-driven masking on textForAI
            StringBuilder maskedText = new StringBuilder(textForAI);
            Set<Integer> maskedPositions = new HashSet<>();

            int totalTokens = Math.min(logits[0].length, offsets.size());

        List<int[]> spans = new ArrayList<>();
        int i = 0;
        while (i < totalTokens) {
            int pred = argmax(logits[0][i]);
            if (pred >= labelMap.length) { i++; continue; }

            String label = labelMap[pred];
            if (label.startsWith("B-")) {
                int start = offsets.get(i)[0];
                int end = offsets.get(i)[1];
                String entityType = label.substring(2);

                int j = i + 1;
                while (j < totalTokens) {
                    int nextPred = argmax(logits[0][j]);
                    String nextLabel = nextPred < labelMap.length ? labelMap[nextPred] : "O";
                    if (nextLabel.equals("I-" + entityType)) {
                        end = offsets.get(j)[1];
                        j++;
                    } else {
                        break;
                    }
                }

                if (start >= 0 && end > start && end <= maskedText.length()) {
                    spans.add(new int[]{start, end, pred});
                    for (int pos = start; pos < end; pos++) {
                        maskedPositions.add(pos);
                    }
                }
                i = j;
            } else {
                i++;
            }
        }

        spans.sort((a, b) -> Integer.compare(b[0], a[0]));
        Set<String> types = new HashSet<>();
        for (int[] span : spans) {
            types.add(labelMap[span[2]].replaceAll("^[BI]-", ""));
            int start = span[0];
            int end = span[1];
            String label = labelMap[span[2]];
            String original = maskedText.substring(start, end);
            String masked = maskSpan(original, label, showLastFour);
            maskedText.replace(start, end, masked);
        }

      //  System.out.println("[SUMMARY] Types masked (Regex + AI): " + types);
        return maskedText.toString();
    }

    private int argmax(float[] scores) {
        int best = 0;
        for (int i = 1; i < scores.length; i++) {
            if (scores[i] > scores[best]) {
                best = i;
            }
        }
        return best;
    }

    private String fallbackMask(String text) {
        String result = text;

        // Mask SSNs
        Matcher ssnMatcher = SSN_PATTERN.matcher(result);
        result = ssnMatcher.replaceAll(match -> maskSpan(match.group(), "SSN", true));

        // Mask Emails
        Matcher emailMatcher = EMAIL_PATTERN.matcher(result);
        result = emailMatcher.replaceAll(match -> maskSpan(match.group(), "EMAIL", false));

        return result;
    }

    private String maskSpan(String text, String label, boolean showLastFour) {
        if (text == null || text.isEmpty()) {
            return text;
        }

        String entityType = label.replaceAll("^[BI]-", ""); // Remove B- or I- from label

        boolean allowLastFour = showLastFour && (
                entityType.equalsIgnoreCase("SSN") ||
                        entityType.equalsIgnoreCase("NPI") ||
                        entityType.equalsIgnoreCase("PHONE") ||
                        entityType.equalsIgnoreCase("CARD")
        );

        StringBuilder masked = new StringBuilder();
        int revealDigits = 4;
        int digitsFound = 0;

        for (int i = text.length() - 1; i >= 0; i--) {
            if (Character.isDigit(text.charAt(i))) {
                digitsFound++;
            }
        }

        int revealStartPosition = -1;
        if (allowLastFour && digitsFound >= revealDigits) {
            int count = 0;
            for (int i = text.length() - 1; i >= 0; i--) {
                if (Character.isDigit(text.charAt(i))) {
                    count++;
                    if (count == revealDigits) {
                        revealStartPosition = i;
                        break;
                    }
                }
            }
        }

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);

            if (Character.isLetterOrDigit(c)) {
                if (allowLastFour && revealStartPosition != -1 && i >= revealStartPosition) {
                    masked.append(c);
                } else {
                    masked.append('•');
                }
            } else {
                masked.append(c); // Keep punctuation
            }
        }

        return masked.toString();
    }
}
