package com.securelogx.api;

import com.securelogx.model.MaskingFieldOrigin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reuse exact text within ONE masking call only; never cache across events,
 * tenants, policies, sessions or model revisions. No persistent raw values.
 *
 * A duplicate is safe to reuse because the masking model receives the same
 * text and no external per-field attributes influence its decisions.
 */
final class MaskingRequestDeduplicator {
    private final List<String> uniqueTexts;
    private final List<MaskingFieldOrigin> firstOrigins;
    private final int[] originalToUnique;
    private final List<Integer> firstOriginalIndices;

    private MaskingRequestDeduplicator(
            List<String> uniqueTexts,
            List<MaskingFieldOrigin> firstOrigins,
            int[] originalToUnique,
            List<Integer> firstOriginalIndices
    ) {
        this.uniqueTexts = List.copyOf(uniqueTexts);
        this.firstOrigins = List.copyOf(firstOrigins);
        this.originalToUnique = originalToUnique.clone();
        this.firstOriginalIndices = List.copyOf(firstOriginalIndices);
    }

    static MaskingRequestDeduplicator of(
            List<String> texts,
            List<MaskingFieldOrigin> origins
    ) {
        if (texts == null || origins == null
                || texts.size() != origins.size()) {
            throw new IllegalArgumentException(
                    "Masking fields and origins must have matching sizes"
            );
        }
        Map<String, Integer> seen = new LinkedHashMap<>();
        List<String> uniqueTexts = new ArrayList<>();
        List<MaskingFieldOrigin> firstOrigins = new ArrayList<>();
        List<Integer> firstOriginalIndices = new ArrayList<>();
        int[] mapping = new int[texts.size()];

        for (int i = 0; i < texts.size(); i++) {
            String text = texts.get(i) == null ? "" : texts.get(i);
            Integer index = seen.get(text);
            if (index == null) {
                index = uniqueTexts.size();
                seen.put(text, index);
                uniqueTexts.add(text);
                firstOriginalIndices.add(i);
                firstOrigins.add(origins.get(i) == null
                        ? MaskingFieldOrigin.OTHER : origins.get(i));
            }
            mapping[i] = index;
        }
        return new MaskingRequestDeduplicator(
                uniqueTexts, firstOrigins, mapping, firstOriginalIndices
        );
    }

    List<String> uniqueTexts() {
        return uniqueTexts;
    }

    List<MaskingFieldOrigin> firstOrigins() {
        return firstOrigins;
    }

    int firstOriginalIndex(int uniqueIndex) {
        return firstOriginalIndices.get(uniqueIndex);
    }

    int originalCount() {
        return originalToUnique.length;
    }

    int savedFields() {
        return originalToUnique.length - uniqueTexts.size();
    }

    List<MaskedResult> expand(
            List<MaskedResult> uniqueResults,
            long[] originalSequences
    ) {
        if (uniqueResults.size() != uniqueTexts.size()
                || originalSequences.length != originalToUnique.length) {
            throw new IllegalArgumentException(
                    "Unique masking results or sequences have wrong size"
            );
        }

        List<MaskedResult> result =
                new ArrayList<>(originalToUnique.length);
        for (int i = 0; i < originalToUnique.length; i++) {
            MaskedResult canonical =
                    uniqueResults.get(originalToUnique[i]);
            result.add(new MaskedResult(
                    canonical.maskedText(),
                    canonical.mlInvoked(),
                    canonical.failClosed(),
                    canonical.reasonCode(),
                    originalSequences[i],
                    canonical.instanceId()
            ));
        }
        return List.copyOf(result);
    }
}
