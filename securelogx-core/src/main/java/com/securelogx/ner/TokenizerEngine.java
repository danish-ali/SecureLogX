package com.securelogx.ner;

/**
 * Converts input log text → token IDs
 */

public interface TokenizerEngine {
    TokenizedInput tokenize(String text);
}
