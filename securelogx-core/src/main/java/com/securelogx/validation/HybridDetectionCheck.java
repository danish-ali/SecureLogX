package com.securelogx.validation;

import com.securelogx.detection.DetectionEvidence;
import com.securelogx.detection.DeterministicScanResult;
import com.securelogx.detection.DeterministicSensitiveDataDetector;
import com.securelogx.detection.HybridContextResolver;
import com.securelogx.detection.MaskingPolicy;
import com.securelogx.detection.ResolutionAction;
import com.securelogx.detection.ResolvedSpan;
import com.securelogx.ner.impl.LabelAwareMaskingEngine;
import org.json.JSONObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Fast logic checks for the hybrid detector/resolver without ONNX inference.
 */
public final class HybridDetectionCheck {

    private HybridDetectionCheck() {
    }

    public static void main(String[] args) throws Exception {
        Path resultPath = args.length > 0
                ? Path.of(args[0])
                : Path.of("reports/hybrid-detection-check/result.json");

        DeterministicSensitiveDataDetector detector =
                new DeterministicSensitiveDataDetector();
        HybridContextResolver resolver = new HybridContextResolver();
        MaskingPolicy policy = new MaskingPolicy();

        int cases = 0;

        assertMaskWithoutMl(
                detector,
                resolver,
                policy,
                "email=jane.doe@example.com status=ok",
                "EMAIL"
        );
        cases++;

        assertEscalatesWithEvidence(
                detector,
                "ssn=123-45-6789 status=verified",
                "SSN"
        );
        cases++;

        assertMaskWithoutMl(
                detector,
                resolver,
                policy,
                "card=4111 1111 1111 1111 status=declined",
                "CREDIT_CARD_NUMBER"
        );
        cases++;

        assertEscalatesWithEvidence(
                detector,
                "routing=021000021 status=pending",
                "ROUTING_NUMBER"
        );
        cases++;

        assertMaskWithoutMl(
                detector,
                resolver,
                policy,
                "iban=GB82WEST12345698765432 status=pending",
                "IBAN"
        );
        cases++;

        assertMaskWithoutMl(
                detector,
                resolver,
                policy,
                "remoteIp=10.20.30.40 status=blocked",
                "IP_ADDRESS"
        );
        cases++;

        assertAllowedWithoutMl(
                detector,
                resolver,
                policy,
                "releaseVersion=10.20.30.40 deployment=canary",
                "10.20.30.40"
        );
        cases++;

        assertRequiresMl(
                detector,
                "value=10.20.30.40 status=unknown"
        );
        cases++;

        assertRequiresMl(
                detector,
                "customerId=CUST-938271 lifecycle=active"
        );
        cases++;

        assertRequiresMl(
                detector,
                "name=Jane Doe action=login"
        );
        cases++;

        assertRequiresMl(
                detector,
                "routing=123456789 status=pending"
        );
        cases++;

        // A valid routing number does not bypass ML when the same record
        // contains an unknown field. The gate stays conservative.
        assertRequiresMl(
                detector,
                "routing=021000021 transfer=pending"
        );
        cases++;

        assertRequiresMl(
                detector,
                "email=jane@example.com owner=Jane Doe status=active"
        );
        cases++;

        assertRequiresMl(
                detector,
                "Contact jane@example.com for Jane Doe immediately"
        );
        cases++;

        // Luhn validity alone is not sufficient evidence that an arbitrary
        // numeric value is a payment card.
        assertRequiresMl(
                detector,
                "reference=4111111111111111 status=active"
        );
        cases++;

        // Loose nearby words must not turn a real network IP into ALLOW.
        assertMaskWithoutMl(
                detector,
                resolver,
                policy,
                "operation=versionCheck remoteIp=208.210.232.230 status=blocked",
                "IP_ADDRESS"
        );
        cases++;

        assertMaskWithoutMl(
                detector,
                resolver,
                policy,
                "{\"@timestamp\":\"2026-09-21T10:00:00Z\",\"log.level\":\"INFO\",\"service.name\":\"payments\",\"message\":\"email=jane.doe@example.com status=ok\"}",
                "EMAIL"
        );
        cases++;

        assertRequiresMl(
                detector,
                "{\"@timestamp\":\"2026-09-21T10:00:00Z\",\"log.level\":\"INFO\",\"service.name\":\"payments\",\"customer.secret\":\"ABC123\",\"message\":\"email=jane.doe@example.com status=ok\"}"
        );
        cases++;

        assertFastPathWithoutMask(
                detector,
                "status=SUCCESS action=healthcheck service=payments result=ok"
        );
        cases++;

        assertFastPathWithoutMask(
                detector,
                "2026-09-27 15:00:00 INFO payment-service operation=refresh status=SUCCESS"
        );
        cases++;

        assertRequiresMl(
                detector,
                "status=SUCCESS reason=Jane_Doe"
        );
        cases++;

        assertRequiresMl(
                detector,
                "service=10.20.30.40 status=ok"
        );
        cases++;

        assertMlCanExtendDeterministicMask(detector, resolver, policy);
        cases++;

        assertPartialAllowCannotSuppressLargerMl(detector, resolver, policy);
        cases++;

        assertNegativeEvidenceSuppressesMl(detector, resolver, policy);
        cases++;

        JSONObject result = new JSONObject();
        result.put("status", "HYBRID DETECTION CHECK PASSED");
        result.put("passed", true);
        result.put("cases", cases);
        result.put("sealed_challenge_inference", false);
        result.put("onnx_inference", false);

        Path parent = resultPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(
                resultPath,
                result.toString(2) + System.lineSeparator()
        );

        System.out.println("HYBRID DETECTION CHECK PASSED");
        System.out.println("Cases: " + cases);
        System.out.println("Result: " + resultPath);
    }

    private static void assertMaskWithoutMl(
            DeterministicSensitiveDataDetector detector,
            HybridContextResolver resolver,
            MaskingPolicy policy,
            String text,
            String entityType
    ) {
        DeterministicScanResult scan = detector.scan(text);
        if (scan.requiresMl()) {
            throw new IllegalStateException(
                    "Expected deterministic-only resolution for: " + text
            );
        }

        boolean found = scan.evidence().stream().anyMatch(
                evidence -> evidence.entityType().equals(entityType)
                        && evidence.action() == ResolutionAction.MASK
        );
        if (!found) {
            throw new IllegalStateException(
                    "Missing deterministic MASK evidence for "
                            + entityType
                            + ": "
                            + text
            );
        }

        List<ResolvedSpan> resolved = resolver.resolve(
                scan.evidence(),
                List.of()
        );
        String masked = policy.apply(text, resolved, false);

        for (DetectionEvidence evidence : scan.evidence()) {
            if (evidence.action() != ResolutionAction.MASK
                    || !evidence.entityType().equals(entityType)) {
                continue;
            }

            String raw = text.substring(evidence.start(), evidence.end());
            String actual = masked.substring(evidence.start(), evidence.end());
            if (raw.equals(actual)) {
                throw new IllegalStateException(
                        "Expected masked span for "
                                + entityType
                                + ": "
                                + text
                );
            }
        }
    }

    private static void assertAllowedWithoutMl(
            DeterministicSensitiveDataDetector detector,
            HybridContextResolver resolver,
            MaskingPolicy policy,
            String text,
            String expectedUnchanged
    ) {
        DeterministicScanResult scan = detector.scan(text);
        if (scan.requiresMl()) {
            throw new IllegalStateException(
                    "Expected deterministic ALLOW without ML for: " + text
            );
        }

        boolean allow = scan.evidence().stream().anyMatch(
                evidence -> evidence.action() == ResolutionAction.ALLOW
        );
        if (!allow) {
            throw new IllegalStateException(
                    "Expected deterministic ALLOW evidence for: " + text
            );
        }

        String output = policy.apply(
                text,
                resolver.resolve(scan.evidence(), List.of()),
                false
        );
        if (!output.contains(expectedUnchanged)) {
            throw new IllegalStateException(
                    "Negative technical reference was unexpectedly masked: "
                            + output
            );
        }
    }

    private static void assertEscalatesWithEvidence(
            DeterministicSensitiveDataDetector detector,
            String text,
            String entityType
    ) {
        DeterministicScanResult scan = detector.scan(text);
        if (!scan.requiresMl()) {
            throw new IllegalStateException(
                    "Expected ML escalation for: " + text
            );
        }

        boolean found = scan.evidence().stream().anyMatch(
                evidence -> evidence.entityType().equals(entityType)
                        && evidence.action() == ResolutionAction.ESCALATE
        );
        if (!found) {
            throw new IllegalStateException(
                    "Expected ESCALATE evidence for "
                            + entityType
                            + ": "
                            + text
            );
        }
    }

    private static void assertFastPathWithoutMask(
            DeterministicSensitiveDataDetector detector,
            String text
    ) {
        DeterministicScanResult scan = detector.scan(text);
        if (scan.requiresMl()) {
            throw new IllegalStateException(
                    "Expected strict metadata fast path for: " + text
            );
        }

        boolean masks = scan.evidence().stream().anyMatch(
                evidence -> evidence.action() == ResolutionAction.MASK
        );
        if (masks) {
            throw new IllegalStateException(
                    "Metadata-only fast path unexpectedly produced MASK evidence: "
                            + text
            );
        }
    }

    private static void assertRequiresMl(
            DeterministicSensitiveDataDetector detector,
            String text
    ) {
        DeterministicScanResult scan = detector.scan(text);
        if (!scan.requiresMl()) {
            throw new IllegalStateException(
                    "Expected ML escalation for: " + text
            );
        }
    }

    private static void assertMlCanExtendDeterministicMask(
            DeterministicSensitiveDataDetector detector,
            HybridContextResolver resolver,
            MaskingPolicy policy
    ) {
        String text =
                "authorization=Bearer AbCdEfGhIjKlMnOpQrStUvWx status=denied";
        DeterministicScanResult scan = detector.scan(text);

        int mlStart = text.indexOf("Bearer");
        int mlEnd = text.indexOf(" status=");
        if (mlStart < 0 || mlEnd <= mlStart) {
            throw new IllegalStateException(
                    "Invalid resolver regression fixture"
            );
        }

        List<LabelAwareMaskingEngine.EntitySpan> simulatedMl = List.of(
                new LabelAwareMaskingEngine.EntitySpan(
                        mlStart,
                        mlEnd,
                        "AUTH_TOKEN"
                )
        );

        List<ResolvedSpan> resolved = resolver.resolve(
                scan.evidence(),
                simulatedMl
        );

        boolean extendedMlMask = resolved.stream().anyMatch(
                span -> span.action() == ResolutionAction.MASK
                        && span.winningSource()
                                == com.securelogx.detection.DetectionSource.ML
                        && span.start() == mlStart
                        && span.end() == mlEnd
        );
        if (!extendedMlMask) {
            throw new IllegalStateException(
                    "Overlapping deterministic MASK incorrectly suppressed "
                            + "the larger ML AUTH_TOKEN span"
            );
        }

        String output = policy.apply(text, resolved, false);
        if (output.contains("Bearer")
                || output.contains("AbCdEfGhIjKlMnOpQrStUvWx")) {
            throw new IllegalStateException(
                    "Extended AUTH_TOKEN span was not fully masked: "
                            + output
            );
        }
    }

    private static void assertPartialAllowCannotSuppressLargerMl(
            DeterministicSensitiveDataDetector detector,
            HybridContextResolver resolver,
            MaskingPolicy policy
    ) {
        String text =
                "releaseVersion=10.20.30.40-extra deployment=canary";
        DeterministicScanResult scan = detector.scan(text);

        int mlStart = text.indexOf("10.20.30.40");
        int mlEnd = text.indexOf(" deployment=");
        List<LabelAwareMaskingEngine.EntitySpan> simulatedMl = List.of(
                new LabelAwareMaskingEngine.EntitySpan(
                        mlStart,
                        mlEnd,
                        "IP_ADDRESS"
                )
        );

        List<ResolvedSpan> resolved = resolver.resolve(
                scan.evidence(),
                simulatedMl
        );

        boolean mlMaskPresent = resolved.stream().anyMatch(
                span -> span.action() == ResolutionAction.MASK
                        && span.winningSource()
                                == com.securelogx.detection.DetectionSource.ML
                        && span.start() == mlStart
                        && span.end() == mlEnd
        );
        if (!mlMaskPresent) {
            throw new IllegalStateException(
                    "Partial deterministic ALLOW incorrectly suppressed "
                            + "a larger ML span"
            );
        }

        String output = policy.apply(text, resolved, false);
        if (output.contains("10.20.30.40-extra")) {
            throw new IllegalStateException(
                    "Larger ML span was not masked after partial ALLOW overlap"
            );
        }
    }

    private static void assertNegativeEvidenceSuppressesMl(
            DeterministicSensitiveDataDetector detector,
            HybridContextResolver resolver,
            MaskingPolicy policy
    ) {
        String text = "releaseVersion=10.20.30.40 deployment=canary";
        DeterministicScanResult scan = detector.scan(text);

        int start = text.indexOf("10.20.30.40");
        int end = start + "10.20.30.40".length();

        List<LabelAwareMaskingEngine.EntitySpan> simulatedMl = List.of(
                new LabelAwareMaskingEngine.EntitySpan(
                        start,
                        end,
                        "IP_ADDRESS"
                )
        );

        String output = policy.apply(
                text,
                resolver.resolve(scan.evidence(), simulatedMl),
                false
        );

        if (!output.contains("10.20.30.40")) {
            throw new IllegalStateException(
                    "Deterministic ALLOW failed to suppress overlapping ML IP prediction"
            );
        }
    }
}
