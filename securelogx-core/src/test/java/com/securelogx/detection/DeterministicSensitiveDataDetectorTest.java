package com.securelogx.detection;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeterministicSensitiveDataDetectorTest {

    private final DeterministicSensitiveDataDetector detector =
            new DeterministicSensitiveDataDetector();

    @Test
    void masksExplicitBankAccountFieldDeterministically() {
        String value = "9876543210";
        String text = "bankAccount=" + value;

        DeterministicScanResult result = detector.scan(text);

        assertTrue(
                result.evidence().stream().anyMatch(
                        evidence ->
                                evidence.entityType().equals(
                                        "BANK_ACCOUNT_NUMBER"
                                )
                                        && evidence.action()
                                        == ResolutionAction.MASK
                                        && text.substring(
                                                evidence.start(),
                                                evidence.end()
                                        ).equals(value)
                )
        );
        assertFalse(result.requiresMl());
    }

    @Test
    void doesNotAssumeGenericAccountFieldIsBankAccount() {
        DeterministicScanResult result =
                detector.scan("account=9876543210");

        assertFalse(
                result.evidence().stream().anyMatch(
                        evidence ->
                                evidence.entityType().equals(
                                        "BANK_ACCOUNT_NUMBER"
                                )
                                        && evidence.action()
                                        == ResolutionAction.MASK
                )
        );
        assertTrue(result.requiresMl());
    }
}
