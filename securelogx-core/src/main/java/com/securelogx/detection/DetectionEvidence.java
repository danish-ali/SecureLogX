package com.securelogx.detection;

/**
 * One piece of evidence about a character span before final masking policy.
 */
public record DetectionEvidence(
        int start,
        int end,
        String entityType,
        DetectionSource source,
        ResolutionAction action,
        double confidence,
        String reason
) {
    public DetectionEvidence {
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("Invalid evidence span: [" + start + "," + end + ")");
        }
        if (entityType == null || entityType.isBlank()) {
            throw new IllegalArgumentException("entityType is required");
        }
        if (source == null || action == null) {
            throw new IllegalArgumentException("source and action are required");
        }
        if (confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be between 0 and 1");
        }
        reason = reason == null ? "" : reason;
    }

    public boolean overlaps(int otherStart, int otherEnd) {
        return Math.max(start, otherStart) < Math.min(end, otherEnd);
    }
}
