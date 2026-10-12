package com.securelogx.detection;

/**
 * Aggregated, per-masker stage timings in monotonic nanoseconds.
 *
 * These counters never retain any log payload, token, credential or span.
 * Each stage is mutually exclusive within the processing path. The native
 * windowed ONNX duration is a subset of nativeOnnxRunNanos, not an
 * additional additive stage.
 */
public record InferenceStageStats(
        long deterministicNanos,
        long tokenizationNanos,
        long windowAssemblyNanos,
        long tensorPreparationNanos,
        long nativeOnnxRunNanos,
        long windowedNativeOnnxRunNanos,
        long decodingNanos,
        long resolutionAndMaskingNanos
) {
    public InferenceStageStats minus(InferenceStageStats before) {
        return new InferenceStageStats(
                deterministicNanos - before.deterministicNanos,
                tokenizationNanos - before.tokenizationNanos,
                windowAssemblyNanos - before.windowAssemblyNanos,
                tensorPreparationNanos - before.tensorPreparationNanos,
                nativeOnnxRunNanos - before.nativeOnnxRunNanos,
                windowedNativeOnnxRunNanos
                        - before.windowedNativeOnnxRunNanos,
                decodingNanos - before.decodingNanos,
                resolutionAndMaskingNanos
                        - before.resolutionAndMaskingNanos
        );
    }
}
