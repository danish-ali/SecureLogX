package com.securelogx.ner;

import java.util.List;

/**
 * Converts input log text to token IDs and character offsets.
 */
public interface TokenizerEngine {

    TokenizedInput tokenize(String text);

    /**
     * Returns complete inference windows using original-text character offsets.
     *
     * Implementations that do not support windowing fall back to a single
     * tokenized input; callers must still inspect isTruncated().
     */
    default List<TokenizedInput> tokenizeWindows(
            String text,
            int overlapContentTokens
    ) {
        return List.of(tokenize(text));
    }
}
