package com.securelogx.ner.impl;

import com.securelogx.ner.TokenizerEngine;
import com.securelogx.ner.TokenizedInput;

public class ParallelTokenizer implements TokenizerEngine {

    private final PureJavaTokenizer tokenizer;

    public ParallelTokenizer(String tokenizerPath) throws Exception {
        this.tokenizer = new PureJavaTokenizer(tokenizerPath);
    }

    @Override
    public TokenizedInput tokenize(String text) {
        return tokenizer.encode(text);
    }
}
