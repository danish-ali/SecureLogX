package com.securelogx.api;

import java.util.List;

/**
 * Aggregate queue waiting time, not per-event logs or sensitive payloads.
 * Buckets are mutually exclusive and represent elapsed time before the
 * bounded native inference worker begins processing an admitted task.
 * The final bucket is greater than the last upper bound.
 */
public record MaskingQueueWaitStats(
        long observedStarts,
        long totalWaitNanos,
        long maxWaitNanos,
        List<Long> upperBoundsMillis,
        List<Long> bucketCounts
) {
    public MaskingQueueWaitStats {
        upperBoundsMillis = List.copyOf(upperBoundsMillis);
        bucketCounts = List.copyOf(bucketCounts);
        if (bucketCounts.size() != upperBoundsMillis.size() + 1) {
            throw new IllegalArgumentException(
                    "Queue wait histogram bucket count mismatch"
            );
        }
    }

    public double averageWaitMillis() {
        return observedStarts == 0 ? 0.0
                : totalWaitNanos / (observedStarts * 1_000_000.0);
    }

    public double maxWaitMillis() {
        return maxWaitNanos / 1_000_000.0;
    }
}
