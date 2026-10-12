package com.securelogx.api;

import com.securelogx.model.MaskingFieldOrigin;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MaskingRequestDeduplicatorTest {

    @Test
    void reusesExactTextWithinOneCallAndPreservesSourceSequences() {
        var request = MaskingRequestDeduplicator.of(
                List.of("ssn=123-45-6789", "ssn=123-45-6789", "version=1.2.3.4"),
                List.of(
                        MaskingFieldOrigin.MESSAGE,
                        MaskingFieldOrigin.FORMAT,
                        MaskingFieldOrigin.MDC
                )
        );

        assertEquals(2, request.uniqueTexts().size());
        assertEquals(1, request.savedFields());
        assertEquals(3, request.originalCount());
        assertEquals(MaskingFieldOrigin.MESSAGE, request.firstOrigins().get(0));

        List<MaskedResult> expanded = request.expand(
                List.of(
                        new MaskedResult(
                                "ssn=***-**-****",
                                true, false,
                                MaskReasonCode.ML_RESOLVED, 100, "runtime"
                        ),
                        new MaskedResult(
                                "version=1.2.3.4",
                                false, false,
                                MaskReasonCode.DETERMINISTIC_RESOLVED, 102, "runtime"
                        )
                ),
                new long[]{100, 101, 102}
        );

        assertEquals(3, expanded.size());
        assertEquals("ssn=***-**-****", expanded.get(1).maskedText());
        assertTrue(expanded.get(1).mlInvoked());
        assertEquals(101, expanded.get(1).sequence());
        assertEquals(102, expanded.get(2).sequence());
    }

    @Test
    void failClosedOutcomePropagatesToEveryDuplicate() {
        var request = MaskingRequestDeduplicator.of(
                List.of("secret", "secret"),
                List.of(
                        MaskingFieldOrigin.MESSAGE,
                        MaskingFieldOrigin.FORMAT
                )
        );
        List<MaskedResult> expanded = request.expand(
                List.of(new MaskedResult(
                        "[SECURELOGX_REDACTED_PROCESSING_FAILURE]",
                        false, true,
                        MaskReasonCode.DEADLINE_EXCEEDED, 1, "runtime"
                )),
                new long[]{1, 2}
        );
        assertTrue(expanded.stream().allMatch(MaskedResult::failClosed));
        assertEquals(2, expanded.get(1).sequence());
    }

    @Test
    void normalizationAndInputValidationDoNotPermitMissingResults() {
        var request = MaskingRequestDeduplicator.of(
                java.util.Arrays.asList(null, ""),
                List.of(
                        MaskingFieldOrigin.OTHER, MaskingFieldOrigin.MDC
                )
        );
        assertEquals(1, request.uniqueTexts().size());
        assertEquals(1, request.savedFields());
        assertThrows(IllegalArgumentException.class,
                () -> request.expand(List.of(), new long[]{1, 2}));
        assertThrows(IllegalArgumentException.class,
                () -> MaskingRequestDeduplicator.of(
                        List.of("a"), List.of()
                ));
    }

    @Test
    void separateRequestsNeverShareDeduplicationState() {
        var first = MaskingRequestDeduplicator.of(
                List.of("repeat", "repeat"),
                List.of(MaskingFieldOrigin.MESSAGE, MaskingFieldOrigin.FORMAT)
        );
        var second = MaskingRequestDeduplicator.of(
                List.of("repeat"),
                List.of(MaskingFieldOrigin.MESSAGE)
        );
        assertEquals(1, first.savedFields());
        assertEquals(0, second.savedFields());
    }
}
