package com.securelogx.detection;

import java.util.List;

public record DeterministicScanResult(
        List<DetectionEvidence> evidence,
        boolean requiresMl,
        String gateReason
) {
    public DeterministicScanResult {
        evidence = List.copyOf(evidence);
        gateReason = gateReason == null ? "" : gateReason;
    }
}
