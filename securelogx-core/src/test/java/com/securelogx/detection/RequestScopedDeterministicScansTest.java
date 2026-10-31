package com.securelogx.detection;

import com.securelogx.model.LogEvent;
import com.securelogx.model.LogLevel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Model-free Phase 2 contract: caller/worker policy and event identity,
 * immutable ordered reuse, and explicit worker scanning for skipped inputs.
 */
class RequestScopedDeterministicScansTest {

    private static LogEvent field(String value, long sequence) {
        return new LogEvent(
                value, LogLevel.INFO, false,
                "phase2-request", sequence, 1_780_000_000_000L
        );
    }

    @Test
    void mlEscalatedFieldsReuseExactCallerEvidenceWithoutRescanning() {
        HybridMaskingPipeline policy = new HybridMaskingPipeline();
        var router = new PreQueueDeterministicRouter(policy);
        var direct = field("status=ok phase=accepted", 1);
        var ambiguous = field("name=Jane Doe action=login", 2);
        var unknown = field("unknownKey=123-45-6789", 3);

        var plan = router.route(List.of(direct, ambiguous, unknown));
        assertEquals(2, plan.unresolvedFields().size());
        assertEquals(2, plan.reusableScans().reusedScanCandidates());
        var reuse = plan.reusableScans();
        reuse.validate(policy, plan.unresolvedFields());

        for (int index = 0; index < plan.unresolvedFields().size(); index++) {
            LogEvent event = plan.unresolvedFields().get(index);
            DeterministicScanResult fromCaller =
                    reuse.scanFor(policy, event, index).orElseThrow();
            assertTrue(fromCaller.requiresMl());
            assertEquals(policy.scan(event.getMessage()), fromCaller,
                    "Reused evidence must equal the unoptimized worker scan");
            assertEquals(
                    policy.maskWithMl(event.getMessage(), fromCaller,
                            List.of(), false),
                    policy.maskWithMl(event.getMessage(),
                            policy.scan(event.getMessage()),
                            List.of(), false),
                    "ML-resolution with identical evidence must preserve masking parity");
        }
    }

    @Test
    void longAndMultilineInputsNeverAcquireCallerEvidence() {
        var policy = new HybridMaskingPipeline();
        var longField = field("name=Jane Doe " + "a".repeat(2000), 10);
        var multiline = field("name=Jane Doe\nemail=jane@example.com", 11);
        var shortField = field("name=Jane Doe", 12);
        var plan = new PreQueueDeterministicRouter(policy).route(
                List.of(longField, multiline, shortField));

        assertEquals(3, plan.unresolvedFields().size());
        assertEquals(Optional.empty(),
                plan.reusableScans().scanFor(policy, longField, 0));
        assertEquals(Optional.empty(),
                plan.reusableScans().scanFor(policy, multiline, 1));
        assertTrue(plan.reusableScans().scanFor(policy, shortField, 2)
                .orElseThrow().requiresMl());
        assertEquals(1, plan.reusableScans().reusedScanCandidates());
    }

    @Test
    void policyIdentityAndOriginalEventOrderingAreMandatory() {
        var policy = new HybridMaskingPipeline();
        var a = field("name=Jane Doe", 1);
        var b = field("unknownKey=123-45-6789", 2);
        var plan = new PreQueueDeterministicRouter(policy).route(
                List.of(a, b));
        var reuse = plan.reusableScans();
        assertThrows(IllegalStateException.class,
                () -> reuse.validate(new HybridMaskingPipeline(),
                        plan.unresolvedFields()));
        assertThrows(IllegalStateException.class,
                () -> reuse.validate(policy, List.of(b, a)));
        assertThrows(IllegalStateException.class,
                () -> reuse.scanFor(policy, b, 0));
        assertThrows(IllegalStateException.class,
                () -> reuse.scanFor(new HybridMaskingPipeline(), a, 0));

        LogEvent equalTextOtherRequest = field("name=Jane Doe", 1);
        assertThrows(IllegalStateException.class,
                () -> reuse.validate(policy,
                        List.of(equalTextOtherRequest, b)));
    }

    @Test
    void requestSnapshotCannotBeChangedAfterRouting() {
        var policy = new HybridMaskingPipeline();
        var events = new ArrayList<LogEvent>();
        events.add(field("name=Jane Doe", 1));
        events.add(field("unknownKey=123-45-6789", 2));
        var plan = new PreQueueDeterministicRouter(policy).route(events);
        var snap = plan.reusableScans();

        events.clear();
        assertEquals(2, plan.unresolvedFields().size());
        snap.validate(policy, plan.unresolvedFields());
        assertThrows(UnsupportedOperationException.class,
                () -> plan.unresolvedFields().clear());
        assertEquals(2, snap.reusedScanCandidates());
    }

    @Test
    void malformedNonMlReuseIsRejectedRatherThanWeakeningPolicy() {
        var policy = new HybridMaskingPipeline();
        var safe = field("status=ok phase=accepted", 1);
        var decision = policy.scan(safe.getMessage());
        assertFalse(decision.requiresMl());
        assertThrows(IllegalArgumentException.class,
                () -> new RequestScopedDeterministicScans(
                        policy, List.of(safe), List.of(Optional.of(decision))));
    }
}
