package com.securelogx.detection;

import com.securelogx.ner.impl.LabelAwareMaskingEngine;

import java.util.List;

/**
 * Coordinates deterministic scanning, ML gating, conflict resolution, and
 * final masking policy.
 */
public final class HybridMaskingPipeline {

    private final DeterministicSensitiveDataDetector detector;
    private final HybridContextResolver resolver;
    private final MaskingPolicy maskingPolicy;

    public HybridMaskingPipeline() {
        this.detector = new DeterministicSensitiveDataDetector();
        this.resolver = new HybridContextResolver();
        this.maskingPolicy = new MaskingPolicy();
    }

    public DeterministicScanResult scan(String text) {
        return detector.scan(text);
    }

    public String maskWithoutMl(
            String text,
            DeterministicScanResult scan,
            boolean showLastFour
    ) {
        List<ResolvedSpan> resolved = resolver.resolve(
                scan.evidence(),
                List.of()
        );
        return maskingPolicy.apply(text, resolved, showLastFour);
    }

    public String maskWithMl(
            String text,
            DeterministicScanResult scan,
            List<LabelAwareMaskingEngine.EntitySpan> mlSpans,
            boolean showLastFour
    ) {
        List<ResolvedSpan> resolved = resolver.resolve(
                scan.evidence(),
                mlSpans
        );
        return maskingPolicy.apply(text, resolved, showLastFour);
    }
}
