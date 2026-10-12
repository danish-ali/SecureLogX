package com.securelogx.log4j2;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecureLogXApplicationLogBenchmarkTest {

    @Test
    void acceptsTwelveUniqueSafeEvents() {
        StringBuilder output = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            output.append(eventLine(i));
        }

        var result =
                SecureLogXApplicationLogBenchmark.validateOutput(
                        output.toString(), 12
                );
        assertEquals(12, result.measuredOutputEvents());
        assertEquals(1, result.mlInvokedEvents());
        assertEquals(0, result.failClosedEvents());
    }

    @Test
    void reportsFailClosedReasonBeforeCountMismatch() {
        StringBuilder output = new StringBuilder();
        for (int i = 0; i < 11; i++) {
            output.append(eventLine(i));
        }
        // RewritePolicy removes original eventId from fail-closed events.
        output.append(
                "eventId=|level=DEBUG|ml=false|failClosed=true"
                        + "|reason=DEADLINE_EXCEEDED|customer=|stack="
                        + "|msg=[SECURELOGX_REDACTED_PROCESSING_FAILURE]"
                        + "|throwable=\n"
        );

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> SecureLogXApplicationLogBenchmark.validateOutput(
                        output.toString(), 12
                )
        );
        assertTrue(exception.getMessage().contains("fail-closed 1"));
        assertTrue(exception.getMessage().contains("DEADLINE_EXCEEDED=1"));
    }

    @Test
    void reportsMissingEventIdentity() {
        StringBuilder output = new StringBuilder();
        for (int i = 0; i < 11; i++) {
            output.append(eventLine(i));
        }

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> SecureLogXApplicationLogBenchmark.validateOutput(
                        output.toString(), 12
                )
        );
        assertTrue(exception.getMessage().contains("missingBenchIds=[11]"));
    }

    @Test
    void rejectsDuplicateEventEvenWhenCountMatches() {
        StringBuilder output = new StringBuilder();
        for (int i = 0; i < 11; i++) {
            output.append(eventLine(i));
        }
        output.append(eventLine(10));

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> SecureLogXApplicationLogBenchmark.validateOutput(
                        output.toString(), 12
                )
        );
        assertTrue(exception.getMessage().contains("missingBenchIds=[11]"));
        assertTrue(exception.getMessage().contains("duplicateBenchIds=[10]"));
    }

    private static String eventLine(int number) {
        String level = switch (number % 4) {
            case 0 -> "INFO";
            case 1 -> "WARN";
            case 2 -> "ERROR";
            default -> "DEBUG";
        };
        String message = number == 6
                ? "version=1.2.3.4"
                : "safe-control";
        return "eventId=bench-" + number
                + "|level=" + level
                + "|ml=" + (number == 0)
                + "|failClosed=false|reason=DETERMINISTIC_RESOLVED"
                + "|customer=|stack=|msg=" + message
                + "|throwable=\n";
    }
}
