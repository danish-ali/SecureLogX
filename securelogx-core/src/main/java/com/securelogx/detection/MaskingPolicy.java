package com.securelogx.detection;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Applies final resolved MASK decisions while preserving text length and
 * punctuation so offsets remain stable across the pipeline.
 */
public final class MaskingPolicy {

    public String apply(
            String originalText,
            List<ResolvedSpan> resolved,
            boolean showLastFour
    ) {
        StringBuilder masked = new StringBuilder(originalText);

        List<ResolvedSpan> maskSpans = new ArrayList<>();
        for (ResolvedSpan span : resolved) {
            if (span.action() == ResolutionAction.MASK) {
                maskSpans.add(span);
            }
        }

        maskSpans.sort(
                Comparator.comparingInt(ResolvedSpan::start)
                        .reversed()
                        .thenComparing(
                                Comparator.comparingInt(ResolvedSpan::end).reversed()
                        )
        );

        for (ResolvedSpan span : maskSpans) {
            if (span.start() < 0
                    || span.end() <= span.start()
                    || span.end() > masked.length()) {
                continue;
            }

            String original = masked.substring(span.start(), span.end());
            masked.replace(
                    span.start(),
                    span.end(),
                    maskSpan(original, span.entityType(), showLastFour)
            );
        }

        return masked.toString();
    }

    private static String maskSpan(
            String text,
            String entityType,
            boolean showLastFour
    ) {
        boolean allowLastFour = showLastFour && (
                entityType.equals("SSN")
                        || entityType.equals("ITIN")
                        || entityType.equals("TAX_ID")
                        || entityType.equals("PHONE")
                        || entityType.equals("CREDIT_CARD_NUMBER")
                        || entityType.equals("BANK_ACCOUNT_NUMBER")
        );

        int digits = 0;
        for (int i = 0; i < text.length(); i++) {
            if (Character.isDigit(text.charAt(i))) {
                digits++;
            }
        }

        int revealStart = -1;
        if (allowLastFour && digits >= 4) {
            int seen = 0;
            for (int i = text.length() - 1; i >= 0; i--) {
                if (Character.isDigit(text.charAt(i))) {
                    seen++;
                    if (seen == 4) {
                        revealStart = i;
                        break;
                    }
                }
            }
        }

        StringBuilder result = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                if (allowLastFour && revealStart >= 0 && i >= revealStart) {
                    result.append(c);
                } else {
                    result.append('*');
                }
            } else {
                result.append(c);
            }
        }
        return result.toString();
    }
}
