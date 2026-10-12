package com.securelogx.log4j2;

import org.apache.logging.log4j.core.LoggerContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SecureLogXInferenceDiagnosticsTest {

    @Test
    void readOnlyLookupDoesNotCreateMaskerOrNativeSession() {
        LoggerContext context = new LoggerContext(
                "inference-stats-no-runtime-test"
        );
        int existing = SecureMaskerRegistry.ownedMaskerCount();
        assertTrue(
                SecureLogXInferenceDiagnostics.snapshot(
                        context, "dev"
                ).isEmpty()
        );
        assertEquals(existing, SecureMaskerRegistry.ownedMaskerCount());
    }

    @Test
    void rejectsAbsentLoggerContext() {
        assertThrows(
                IllegalArgumentException.class,
                () -> SecureLogXInferenceDiagnostics.snapshot(null, "dev")
        );
    }
}
