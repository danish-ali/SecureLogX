package com.securelogx.ner.impl;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decodes the frozen SecureLogX ML-v1.3 51-label BIO ontology and masks
 * detected entity spans.
 */
public class LabelAwareMaskingEngine {

    private static final String[] LABEL_MAP = {
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

    private static final Pattern SSN_PATTERN =
            Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b");
    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("\\b[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}\\b");

    private final boolean enableFallback;

    public LabelAwareMaskingEngine(boolean enableFallback) {
        this.enableFallback = enableFallback;
    }

    public LabelAwareMaskingEngine() {
        this(false);
    }

    public List<EntitySpan> decodeSpans(
            String originalText,
            float[][][] logits,
            List<int[]> offsets
    ) {
        if (logits == null || logits.length == 0) {
            return List.of();
        }

        int totalTokens = Math.min(logits[0].length, offsets.size());
        List<EntitySpan> spans = new ArrayList<>();
        MutableSpan current = null;

        for (int i = 0; i < totalTokens; i++) {
            int[] offset = offsets.get(i);
            if (offset == null || offset.length < 2) {
                continue;
            }

            int start = offset[0];
            int end = offset[1];
            if (start < 0 || end <= start) {
                continue;
            }

            int predictedId = argmax(logits[0][i]);
            String label = predictedId < LABEL_MAP.length ? LABEL_MAP[predictedId] : "O";

            if ("O".equals(label)) {
                if (current != null) {
                    addNormalizedSpan(originalText, current, spans);
                    current = null;
                }
                continue;
            }

            String prefix = label.substring(0, 1);
            String entityType = label.substring(2);

            if ("B".equals(prefix)) {
                if (current != null) {
                    addNormalizedSpan(originalText, current, spans);
                }
                current = new MutableSpan(start, end, entityType);
                continue;
            }

            if ("I".equals(prefix)
                    && current != null
                    && current.entityType.equals(entityType)) {
                current.end = end;
                continue;
            }

            // Match Python decode_bio_spans: an invalid I-transition starts a
            // new span of that entity type rather than silently dropping it.
            if (current != null) {
                addNormalizedSpan(originalText, current, spans);
            }
            current = new MutableSpan(start, end, entityType);
        }

        if (current != null) {
            addNormalizedSpan(originalText, current, spans);
        }

        return spans;
    }

    public String mask(
            String originalText,
            int[] inputIds,
            float[][][] logits,
            List<int[]> offsets,
            boolean showLastFour
    ) {
        String textForAI = enableFallback ? fallbackMask(originalText) : originalText;
        StringBuilder maskedText = new StringBuilder(textForAI);

        List<EntitySpan> spans = new ArrayList<>(decodeSpans(originalText, logits, offsets));
        spans.sort(Comparator.comparingInt(EntitySpan::start).reversed());

        for (EntitySpan span : spans) {
            if (span.start() < 0
                    || span.end() <= span.start()
                    || span.end() > maskedText.length()) {
                continue;
            }

            String original = maskedText.substring(span.start(), span.end());
            String masked = maskSpan(original, span.entityType(), showLastFour);
            maskedText.replace(span.start(), span.end(), masked);
        }

        return maskedText.toString();
    }

    private static void addNormalizedSpan(
            String text,
            MutableSpan candidate,
            List<EntitySpan> output
    ) {
        int start = Math.max(0, Math.min(text.length(), candidate.start));
        int end = Math.max(start, Math.min(text.length(), candidate.end));

        while (start < end && Character.isWhitespace(text.charAt(start))) {
            start++;
        }
        while (end > start && Character.isWhitespace(text.charAt(end - 1))) {
            end--;
        }

        if (start < end) {
            output.add(new EntitySpan(start, end, candidate.entityType));
        }
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

    private String fallbackMask(String text) {
        String result = text;

        Matcher ssnMatcher = SSN_PATTERN.matcher(result);
        result = ssnMatcher.replaceAll(match -> maskSpan(match.group(), "SSN", true));

        Matcher emailMatcher = EMAIL_PATTERN.matcher(result);
        result = emailMatcher.replaceAll(match -> maskSpan(match.group(), "EMAIL", false));

        return result;
    }

    private static String maskSpan(String text, String entityType, boolean showLastFour) {
        if (text == null || text.isEmpty()) {
            return text;
        }

        boolean allowLastFour = showLastFour && (
                entityType.equals("SSN")
                        || entityType.equals("ITIN")
                        || entityType.equals("TAX_ID")
                        || entityType.equals("PHONE")
                        || entityType.equals("CREDIT_CARD_NUMBER")
                        || entityType.equals("BANK_ACCOUNT_NUMBER")
        );

        int digitsFound = 0;
        for (int i = text.length() - 1; i >= 0; i--) {
            if (Character.isDigit(text.charAt(i))) {
                digitsFound++;
            }
        }

        int revealStartPosition = -1;
        if (allowLastFour && digitsFound >= 4) {
            int count = 0;
            for (int i = text.length() - 1; i >= 0; i--) {
                if (Character.isDigit(text.charAt(i))) {
                    count++;
                    if (count == 4) {
                        revealStartPosition = i;
                        break;
                    }
                }
            }
        }

        StringBuilder masked = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                if (allowLastFour && revealStartPosition >= 0 && i >= revealStartPosition) {
                    masked.append(c);
                } else {
                    masked.append('*');
                }
            } else {
                masked.append(c);
            }
        }
        return masked.toString();
    }

    public record EntitySpan(int start, int end, String entityType) {
    }

    private static final class MutableSpan {
        private int start;
        private int end;
        private final String entityType;

        private MutableSpan(int start, int end, String entityType) {
            this.start = start;
            this.end = end;
            this.entityType = entityType;
        }
    }
}
