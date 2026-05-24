package com.securelogx.ner;

import java.util.List;

/**
 * Holds the token ID + attention mask arrays
 */
public class TokenizedInput {
    private final int[] inputIds;
    private final int[] attentionMask;
    private final List<int[]> offsets;

    public TokenizedInput(int[] inputIds, int[] attentionMask, List<int[]> offsets) {
        this.inputIds = inputIds;
        this.attentionMask = attentionMask;
        this.offsets = offsets;
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
}
