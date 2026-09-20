package com.securelogx.detection;

public record HybridRuntimeStats(
        long deterministicOnlyItems,
        long mlInferenceItems
) {
    public long totalRoutedItems() {
        return deterministicOnlyItems + mlInferenceItems;
    }

    public double mlInvocationRate() {
        long total = totalRoutedItems();
        return total == 0 ? 0.0 : (double) mlInferenceItems / total;
    }
}
