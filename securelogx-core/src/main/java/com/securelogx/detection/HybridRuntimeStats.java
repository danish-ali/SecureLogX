package com.securelogx.detection;

public record HybridRuntimeStats(
        long deterministicOnlyItems,
        long mlInferenceItems,
        long windowedMlItems,
        long mlInferenceWindows,
        long onnxInferenceCalls,
        long maxInferenceWindowsPerCallObserved,
        long windowedOnnxInferenceCalls,
        long maxWindowedInferenceWindowsPerCallObserved,
        long truncatedFailClosedItems
) {
    public long totalRoutedItems() {
        return deterministicOnlyItems
                + mlInferenceItems
                + truncatedFailClosedItems;
    }

    public double mlInvocationRate() {
        long successfulRoutes = deterministicOnlyItems + mlInferenceItems;
        return successfulRoutes == 0
                ? 0.0
                : (double) mlInferenceItems / successfulRoutes;
    }

    public double truncatedFailClosedRate() {
        long total = totalRoutedItems();
        return total == 0
                ? 0.0
                : (double) truncatedFailClosedItems / total;
    }

    public double averageWindowsPerMlItem() {
        return mlInferenceItems == 0
                ? 0.0
                : (double) mlInferenceWindows / mlInferenceItems;
    }

    public double averageWindowsPerOnnxCall() {
        return onnxInferenceCalls == 0
                ? 0.0
                : (double) mlInferenceWindows / onnxInferenceCalls;
    }
}
