package com.securelogx.log4j2;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SecureLogXSequenceAuditTest {

    private static final String INSTANCE_A =
            "11111111-1111-4111-8111-111111111111";
    private static final String INSTANCE_B =
            "22222222-2222-4222-8222-222222222222";

    @Test
    void sortsNumericallyWithinInstanceDespitePhysicalReordering() {
        String output = line(INSTANCE_A, 10, "bench-later")
                + line(INSTANCE_A, 2, "bench-earlier");
        SecureLogXSequenceAudit.Audit audit =
                SecureLogXSequenceAudit.inspect(output);

        assertEquals(2, audit.records());
        assertEquals(1, audit.instances());
        assertEquals(1, audit.physicalOrderInversions());
        List<SecureLogXSequenceAudit.Stamp> reconstructed =
                audit.reconstructedByInstance().get(INSTANCE_A);
        assertEquals(2, reconstructed.get(0).sequence());
        assertEquals(10, reconstructed.get(1).sequence());
        assertEquals(1, reconstructed.get(0).physicalLineIndex());
        assertEquals(0, reconstructed.get(1).physicalLineIndex());
    }

    @Test
    void restartIdentityRequiresIndependentNumericSequenceOrder() {
        String output = line(INSTANCE_A, 19, "a-late")
                + line(INSTANCE_B, 1, "b-start")
                + line(INSTANCE_A, 11, "a-early")
                + line(INSTANCE_B, 2, "b-next");
        var audit = SecureLogXSequenceAudit.inspect(output);
        assertEquals(4, audit.records());
        assertEquals(2, audit.instances());
        assertEquals(1, audit.physicalOrderInversions());
        assertEquals(11,
                audit.reconstructedByInstance().get(INSTANCE_A)
                        .get(0).sequence());
        assertEquals(1,
                audit.reconstructedByInstance().get(INSTANCE_B)
                        .get(0).sequence());
    }

    @Test
    void failsOnDuplicateOrMismatchedIngressIdentities() {
        String record = line(INSTANCE_A, 3, "first");
        assertThrows(IllegalStateException.class,
                () -> SecureLogXSequenceAudit.inspect(record + record));

        String mismatched = record.replace(
                "|ingressId=" + INSTANCE_A + "-3",
                "|ingressId=" + INSTANCE_A + "-4"
        );
        assertThrows(IllegalStateException.class,
                () -> SecureLogXSequenceAudit.inspect(mismatched));
    }

    @Test
    void failsOnMissingInvalidOrNonPositiveSequence() {
        String record = line(INSTANCE_A, 3, "first");
        assertThrows(IllegalStateException.class,
                () -> SecureLogXSequenceAudit.inspect(
                        record.replace("|seq=3|", "|seq=0|")
                ));
        assertThrows(IllegalStateException.class,
                () -> SecureLogXSequenceAudit.inspect(
                        record.replace("|seq=3|", "|seq=three|")
                ));
        assertThrows(IllegalStateException.class,
                () -> SecureLogXSequenceAudit.inspect(
                        record.replace("|seq=3|", "")
                ));
        assertThrows(IllegalStateException.class,
                () -> SecureLogXSequenceAudit.inspect(
                        record.replace(INSTANCE_A, "not-a-valid-uuid")
                ));
    }

    @Test
    void failClosedOutputStillCarriesReconstructableMetadata() {
        String record = line(INSTANCE_A, 8, "")
                .replace("|failClosed=false|", "|failClosed=true|");
        var audit = SecureLogXSequenceAudit.inspect(record);
        assertEquals(1, audit.records());
        assertEquals(INSTANCE_A + "-8",
                audit.physicalOrder().get(0).ingressId());
    }

    private static String line(
            String instance,
            long sequence,
            String eventId
    ) {
        return "eventId=" + eventId + "|level=INFO"
                + "|instanceId=" + instance
                + "|seq=" + sequence
                + "|ingressId=" + instance + "-" + sequence
                + "|ml=false|failClosed=false|reason=DETERMINISTIC_RESOLVED"
                + "|customer=|stack=|msg=protected|throwable=\n";
    }
}
