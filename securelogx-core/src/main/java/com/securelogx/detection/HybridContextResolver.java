package com.securelogx.detection;

import com.securelogx.ner.impl.LabelAwareMaskingEngine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Reconciles deterministic evidence with contextual ML spans.
 *
 * Precedence:
 * 1. Deterministic ALLOW is authoritative for overlapping ML spans.
 * 2. Deterministic MASK is a protection floor, not a span ceiling: an
 *    overlapping ML MASK may extend coverage beyond the deterministic span.
 * 3. ESCALATE leaves the decision to ML.
 * 4. Non-overlapping ML spans are masked by default.
 */
public final class HybridContextResolver {

    public List<ResolvedSpan> resolve(
            List<DetectionEvidence> deterministicEvidence,
            List<LabelAwareMaskingEngine.EntitySpan> mlSpans
    ) {
        List<ResolvedSpan> resolved = new ArrayList<>();

        for (DetectionEvidence evidence : deterministicEvidence) {
            if (evidence.action() == ResolutionAction.MASK
                    || evidence.action() == ResolutionAction.ALLOW) {
                resolved.add(
                        new ResolvedSpan(
                                evidence.start(),
                                evidence.end(),
                                evidence.entityType(),
                                evidence.action(),
                                DetectionSource.DETERMINISTIC,
                                evidence.reason()
                        )
                );
            }
        }

        for (LabelAwareMaskingEngine.EntitySpan mlSpan : mlSpans) {
            DetectionEvidence allow = firstOverlappingAllow(
                    deterministicEvidence,
                    mlSpan.start(),
                    mlSpan.end()
            );

            if (allow != null) {
                continue;
            }

            resolved.add(
                    new ResolvedSpan(
                            mlSpan.start(),
                            mlSpan.end(),
                            mlSpan.entityType(),
                            ResolutionAction.MASK,
                            DetectionSource.ML,
                            hasOverlappingMask(
                                    deterministicEvidence,
                                    mlSpan.start(),
                                    mlSpan.end()
                            )
                                    ? "contextual-ml-v1.3-extends-deterministic-mask"
                                    : "contextual-ml-v1.3"
                    )
            );
        }

        resolved.sort(
                Comparator.comparingInt(ResolvedSpan::start)
                        .thenComparingInt(ResolvedSpan::end)
                        .thenComparing(ResolvedSpan::entityType)
        );

        return resolved;
    }

    private static DetectionEvidence firstOverlappingAllow(
            List<DetectionEvidence> evidence,
            int start,
            int end
    ) {
        for (DetectionEvidence item : evidence) {
            if (item.action() == ResolutionAction.ALLOW
                    && item.overlaps(start, end)) {
                return item;
            }
        }
        return null;
    }

    private static boolean hasOverlappingMask(
            List<DetectionEvidence> evidence,
            int start,
            int end
    ) {
        for (DetectionEvidence item : evidence) {
            if (item.action() == ResolutionAction.MASK
                    && item.overlaps(start, end)) {
                return true;
            }
        }
        return false;
    }
}
