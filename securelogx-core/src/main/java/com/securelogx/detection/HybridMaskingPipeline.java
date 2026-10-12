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
    private final OrganizationRuleRegistry organizationRules;

    public HybridMaskingPipeline() {
        this(OrganizationRuleRegistry.disabled());
    }

    public HybridMaskingPipeline(
            OrganizationRuleRegistry organizationRules
    ) {
        if (organizationRules == null) {
            throw new IllegalArgumentException(
                    "organizationRules must not be null"
            );
        }
        this.detector = new DeterministicSensitiveDataDetector();
        this.resolver = new HybridContextResolver();
        this.maskingPolicy = new MaskingPolicy();
        this.organizationRules = organizationRules;
    }

    public DeterministicScanResult scan(String text) {
        // Approved rules can bypass ML only for a completely resolved
        // single structured value. Everything else follows the existing
        // conservative deterministic + ML gate.
        DeterministicScanResult approved =
                organizationRules.scanExact(text);
        return approved == null ? detector.scan(text) : approved;
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
