package com.securelogx.ner.impl;

import com.securelogx.ner.TokenizerEngine;
import com.securelogx.ner.TokenizedInput;

public class ParallelTokenizer implements TokenizerEngine {

    private final PureJavaTokenizer tokenizer;

    public ParallelTokenizer(String tokenizerPath) throws Exception {
        this(tokenizerPath, 384);
    }

    public ParallelTokenizer(String tokenizerPath, int maxSequenceLength) throws Exception {
        this.tokenizer = new PureJavaTokenizer(tokenizerPath, maxSequenceLength);
    }

    @Override
    public TokenizedInput tokenize(String text) {
        return tokenizer.encode(text);
    }
}
