package com.securelogx.ner;

import java.util.List;

/**
 * Holds token IDs, attention mask, character offsets, and truncation metadata.
 */
public class TokenizedInput {
    private final int[] inputIds;
    private final int[] attentionMask;
    private final List<int[]> offsets;
    private final boolean truncated;
    private final int coveredCharacterEnd;

    public TokenizedInput(
            int[] inputIds,
            int[] attentionMask,
            List<int[]> offsets
    ) {
        this(
                inputIds,
                attentionMask,
                offsets,
                false,
                inferCoveredCharacterEnd(offsets)
        );
    }

    public TokenizedInput(
            int[] inputIds,
            int[] attentionMask,
            List<int[]> offsets,
            boolean truncated,
            int coveredCharacterEnd
    ) {
        this.inputIds = inputIds;
        this.attentionMask = attentionMask;
        this.offsets = List.copyOf(offsets);
        this.truncated = truncated;
        this.coveredCharacterEnd = Math.max(0, coveredCharacterEnd);
    }

    public int[] getInputIds() {
        return inputIds;
    }

    public int[] getAttentionMask() {
        return attentionMask;
    }

    public List<int[]> getOffsets() {
        return offsets;
    }

    public boolean isTruncated() {
        return truncated;
    }

    public int getCoveredCharacterEnd() {
        return coveredCharacterEnd;
    }

    private static int inferCoveredCharacterEnd(List<int[]> offsets) {
        int coveredEnd = 0;
        for (int[] offset : offsets) {
            if (offset != null && offset.length >= 2) {
                coveredEnd = Math.max(coveredEnd, offset[1]);
            }
        }
        return coveredEnd;
    }
}
