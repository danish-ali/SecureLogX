package com.securelogx.detection;

import com.securelogx.api.MaskReasonCode;
import com.securelogx.api.MaskedResult;
import com.securelogx.model.LogEvent;
import com.securelogx.model.LogLevel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PreQueueDeterministicRouterTest {

    @TempDir
    Path temp;

    private PreQueueDeterministicRouter approvedRouter() throws Exception {
        Path rules = temp.resolve("approved-org-rules.json");
        Files.writeString(rules, """
                {
                  "organizationId":"ORG42",
                  "version":1,
                  "rules":[
                    {"id":"customer-reference","status":"APPROVED",
                     "field":"customerReference","prefix":"CUST-",
                     "digits":6,"entityType":"BUSINESS_ID"}
                  ],
                  "safeMetadata":[
                    {"id":"event-ref","status":"APPROVED",
                     "field":"eventId","prefix":"bench-","digits":2}
                  ]
                }
                """);
        return new PreQueueDeterministicRouter(
                new HybridMaskingPipeline(
                        OrganizationRuleRegistry.load("ORG42", rules.toString())
                )
        );
    }

    private static LogEvent event(String text, int sequence) {
        return new LogEvent(
                text, LogLevel.INFO, false, "test-prequeue",
                sequence, 1_780_000_000_000L
        );
    }

    @Test
    void resolvedMaskAndAllowBypassOnlyForEntireStructuredFields()
            throws Exception {
        PreQueueDeterministicRouter router = approvedRouter();
        LogEvent protectedId =
                event("customerReference=CUST-834729", 1);
        LogEvent allowedId = event("eventId=bench-12", 2);
        LogEvent unknown =
                event("customerReference=CUST-834729 email=alice@example.com", 3);
        LogEvent freeText =
                event("Customer Alice Smith called about account 12345", 4);

        var plan = router.route(List.of(
                protectedId, allowedId, unknown, freeText
        ));
        assertEquals(2, plan.deterministicFields());
        assertEquals(List.of(unknown, freeText), plan.unresolvedFields());

        var complete = plan.merge(List.of(
                masked(unknown, "[PROTECTED]"),
                masked(freeText, "[PROTECTED]")
        ));
        assertEquals("customerReference=****-******",
                complete.get(0).maskedText());
        assertEquals("eventId=bench-12",
                complete.get(1).maskedText());
        assertEquals("[PROTECTED]", complete.get(2).maskedText());
        assertEquals("[PROTECTED]", complete.get(3).maskedText());
        assertTrue(complete.stream().noneMatch(MaskedResult::failClosed));
        for (int i = 0; i < complete.size(); i++) {
            assertEquals(i + 1, complete.get(i).sequence());
        }
    }

    @Test
    void deterministicParityUsesSameFrozenPipelineAndNegativeControls()
            throws Exception {
        HybridMaskingPipeline base = new HybridMaskingPipeline();
        PreQueueDeterministicRouter router =
                new PreQueueDeterministicRouter(base);
        for (String structured : List.of(
                "email=jane.doe@example.com",
                "ssn=123-45-6789",
                "bankAccount=9876543210",
                "releaseVersion=1.2.3.4",
                "status=ok phase=accepted"
        )) {
            DeterministicScanResult original = base.scan(structured);
            if (!original.requiresMl()) {
                LogEvent item = event(structured, 1);
                var plan = router.route(List.of(item));
                assertEquals(1, plan.deterministicFields());
                assertEquals(
                        base.maskWithoutMl(structured, original, false),
                        plan.merge(List.of()).get(0).maskedText()
                );
            }
        }
        // These free-text or ambiguous messages cannot be ALLOW-bypassed.
        for (String ambiguous : List.of(
                "name=Jane Doe action=login",
                "customerId=CUST-834729 lifecycle=active",
                "ssn 123-45-6789 from caller",
                "unknownKey=123-45-6789",
                "hello Ms Alice Smith"
        )) {
            assertEquals(1, router.route(List.of(event(ambiguous, 2)))
                    .unresolvedFields().size(), ambiguous);
        }
    }

    @Test
    void longAndMultilineFieldsStayOnTheBoundedNativeWorker()
            throws Exception {
        var router = approvedRouter();
        LogEvent longField =
                event("status=ok " + "a".repeat(2048), 1);
        LogEvent multiline =
                event("eventId=bench-12\npassword=do-not-forward", 2);
        var plan = router.route(List.of(longField, multiline));
        assertEquals(0, plan.deterministicFields());
        assertEquals(List.of(longField, multiline), plan.unresolvedFields());
    }

    @Test
    void anyWorkerDeadlineFailureFailsTheWholeStructuredEventClosed()
            throws Exception {
        var router = approvedRouter();
        LogEvent deterministic =
                event("customerReference=CUST-834729", 1);
        LogEvent sensitive = event("name=Jane Doe action=login", 2);
        var plan = router.route(List.of(deterministic, sensitive));
        var merged = plan.merge(List.of(new MaskedResult(
                "[SECURELOGX_REDACTED_PROCESSING_FAILURE]",
                true, true, MaskReasonCode.DEADLINE_EXCEEDED,
                sensitive.getSequenceNumber(), sensitive.getInstanceId()
        )));
        assertEquals(2, merged.size());
        assertTrue(merged.stream().allMatch(MaskedResult::failClosed));
        assertTrue(merged.stream().allMatch(
                x -> x.reasonCode() == MaskReasonCode.DEADLINE_EXCEEDED
        ));
        assertTrue(merged.stream().noneMatch(x -> x.maskedText()
                .contains("834729")));
        assertTrue(merged.stream().noneMatch(x -> x.maskedText()
                .contains("Jane")));
    }

    @Test
    void missingOrMismappedMlWorkerResultFailsAllFieldsClosed()
            throws Exception {
        var router = approvedRouter();
        LogEvent direct = event("eventId=bench-12", 1);
        LogEvent unresolved = event("name=Jane Doe", 2);
        var plan = router.route(List.of(direct, unresolved));
        assertTrue(plan.merge(List.of()).stream()
                .allMatch(MaskedResult::failClosed));
        var other = event("name=Jane Doe", 999);
        var out = plan.merge(List.of(masked(other, "[MASKED]")));
        assertTrue(out.stream().allMatch(MaskedResult::failClosed));
        assertTrue(out.stream().allMatch(x ->
                x.reasonCode() == MaskReasonCode.PROCESSING_FAILURE));
        assertFalse(out.get(0).maskedText().contains("bench-12"));
    }

    @Test
    void inconsistentWorkerSuccessNeverReleasesUnsanitizedField()
            throws Exception {
        var plan = approvedRouter().route(List.of(
                event("eventId=bench-12", 1),
                event("name=Jane Doe", 2),
                event("customerReference=CUST-834729", 3)
        ));
        var unknown = plan.unresolvedFields().get(0);
        for (MaskReasonCode unsafeReason : List.of(
                MaskReasonCode.MASKING_DISABLED,
                MaskReasonCode.PROCESSING_FAILURE,
                MaskReasonCode.DEADLINE_EXCEEDED,
                MaskReasonCode.OVERLOAD_REJECTED
        )) {
            var malformed = new MaskedResult(
                    unknown.getMessage(), false, false, unsafeReason,
                    unknown.getSequenceNumber(), unknown.getInstanceId()
            );
            var results = plan.merge(List.of(malformed));
            assertEquals(3, results.size());
            assertTrue(results.stream().allMatch(MaskedResult::failClosed));
            assertTrue(results.stream().allMatch(x ->
                    x.reasonCode() == MaskReasonCode.PROCESSING_FAILURE));
            for (var result : results) {
                assertFalse(result.maskedText().contains("Jane"));
                assertFalse(result.maskedText().contains("CUST-834729"));
                assertFalse(result.maskedText().contains("bench-12"));
            }
        }
    }

    @Test
    void incorrectMlOrderingCannotReleaseAnyDeterministicAllowOrMask()
            throws Exception {
        var plan = approvedRouter().route(List.of(
                event("eventId=bench-12", 1),
                event("name=Jane Doe", 2),
                event("customerReference=CUST-834729", 3),
                event("email=alice@example.com name=Jane Doe", 4)
        ));
        assertEquals(2, plan.unresolvedFields().size());
        var reversed = plan.merge(List.of(
                masked(plan.unresolvedFields().get(1), "[MASKED-B]"),
                masked(plan.unresolvedFields().get(0), "[MASKED-A]")
        ));
        assertEquals(4, reversed.size());
        assertTrue(reversed.stream().allMatch(MaskedResult::failClosed));
        assertTrue(reversed.stream().allMatch(x ->
                x.reasonCode() == MaskReasonCode.PROCESSING_FAILURE));
        for (int i = 0; i < reversed.size(); i++) {
            assertEquals(i + 1, reversed.get(i).sequence());
            assertFalse(reversed.get(i).maskedText().contains("bench-12"));
            assertFalse(reversed.get(i).maskedText().contains("CUST-834729"));
            assertFalse(reversed.get(i).maskedText().contains("Jane"));
            assertFalse(reversed.get(i).maskedText().contains("alice@example.com"));
        }
    }

    @Test
    void disabledRegistryNeverApprovesUnknownOrganizationMetadata() {
        var router = new PreQueueDeterministicRouter(
                new HybridMaskingPipeline()
        );
        var plan = router.route(List.of(
                event("customerReference=CUST-834729", 1),
                event("eventId=bench-12", 2)
        ));
        assertEquals(0, plan.deterministicFields());
        assertEquals(2, plan.unresolvedFields().size());
    }

    private static MaskedResult masked(LogEvent event, String output) {
        return new MaskedResult(
                output, true, false, MaskReasonCode.ML_RESOLVED,
                event.getSequenceNumber(), event.getInstanceId()
        );
    }
}
