package com.securelogx.detection;

/**
 * Final span decision after deterministic and ML evidence are reconciled.
 */
public record ResolvedSpan(
        int start,
        int end,
        String entityType,
        ResolutionAction action,
        DetectionSource winningSource,
        String reason
) {
}
