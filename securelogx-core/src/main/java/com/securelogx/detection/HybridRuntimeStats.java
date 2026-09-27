package com.securelogx.detection;

public record HybridRuntimeStats(
        long deterministicOnlyItems,
        long mlInferenceItems,
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
}
