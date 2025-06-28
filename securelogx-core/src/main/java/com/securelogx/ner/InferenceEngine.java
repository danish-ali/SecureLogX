package com.securelogx.ner;

/**
 * Runs ONNX model → detects sensitive entities
 */

public interface InferenceEngine {
    String run(TokenizedInput input, String originalText, boolean showLastFour);
}
