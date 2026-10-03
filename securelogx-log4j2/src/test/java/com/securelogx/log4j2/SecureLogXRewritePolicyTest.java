package com.securelogx.log4j2;

import com.securelogx.api.MaskedResult;
import com.securelogx.api.MaskReasonCode;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.ThreadContext;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.message.ParameterizedMessage;
import org.apache.logging.log4j.message.SimpleMessage;
import org.apache.logging.log4j.spi.MutableThreadContextStack;
import org.apache.logging.log4j.util.SortedArrayStringMap;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SecureLogXRewritePolicyTest {

    private static final String RAW = "123-45-6789";

    @Test
    void masksMessageParametersMdcStackAndThrowable() {
        SecureMaskingService service = texts -> {
            List<MaskedResult> results = new ArrayList<>();
            long sequence = 0;
            for (String text : texts) {
                results.add(
                        new MaskedResult(
                                text.replace(RAW, "[MASKED]"),
                                false,
                                false,
                                MaskReasonCode.DETERMINISTIC_RESOLVED,
                                ++sequence,
                                "test-masker"
                        )
                );
            }
            return List.copyOf(results);
        };

        SecureLogXRewritePolicy policy =
                new SecureLogXRewritePolicy(
                        "test",
                        "SecureLogXRewrite",
                        false,
                        false,
                        null,
                        service
                );

        SortedArrayStringMap context = new SortedArrayStringMap();
        context.putValue("patient", RAW);
        context.freeze();

        MutableThreadContextStack stack =
                new MutableThreadContextStack(
                        List.of("request=" + RAW)
                );
        stack.freeze();

        long eventMillis = 1_780_000_000_000L;
        LogEvent source = new Log4jLogEvent.Builder()
                .setLoggerName("example")
                .setLevel(Level.INFO)
                .setMessage(
                        new ParameterizedMessage(
                                "patient ssn={}",
                                RAW
                        )
                )
                .setContextData(context)
                .setContextStack(stack)
                .setThrown(
                        new IllegalStateException(
                                "patient=" + RAW
                        )
                )
                .setTimeMillis(eventMillis)
                .build();

        LogEvent rewritten = policy.rewrite(source);

        assertEquals(Level.INFO, rewritten.getLevel());
        assertEquals(eventMillis, rewritten.getTimeMillis());
        assertFalse(rewritten.getMessage()
                .getFormattedMessage().contains(RAW));
        assertFalse(String.valueOf(
                rewritten.getMessage().getParameters()[0]
        ).contains(RAW));
        assertFalse(
                String.valueOf(
                        rewritten.getContextData()
                                .getValue("patient")
                ).contains(RAW)
        );
        assertFalse(rewritten.getContextStack()
                .asList().get(0).contains(RAW));
        assertNotNull(rewritten.getThrown());
        assertFalse(rewritten.getThrown()
                .toString().contains(RAW));

        assertEquals(
                "false",
                rewritten.getContextData()
                        .getValue("securelogx.failClosed")
        );
        assertNotNull(
                rewritten.getContextData()
                        .getValue("securelogx.seq")
        );
        assertNotNull(
                rewritten.getContextData()
                        .getValue("securelogx.instanceId")
        );
    }

    @Test
    void failsWholeEventClosedWhenAnyFieldFails() {
        SecureMaskingService service = texts -> {
            List<MaskedResult> results = new ArrayList<>();
            long sequence = 0;
            for (int i = 0; i < texts.size(); i++) {
                boolean fail = i == 0;
                results.add(
                        new MaskedResult(
                                fail
                                        ? "[SECURELOGX_REDACTED_PROCESSING_FAILURE]"
                                        : texts.get(i).replace(RAW, "[MASKED]"),
                                false,
                                fail,
                                fail
                                        ? MaskReasonCode.PROCESSING_FAILURE
                                        : MaskReasonCode.DETERMINISTIC_RESOLVED,
                                ++sequence,
                                "test-masker"
                        )
                );
            }
            return List.copyOf(results);
        };

        SecureLogXRewritePolicy policy =
                new SecureLogXRewritePolicy(
                        "test",
                        "SecureLogXRewrite",
                        false,
                        false,
                        null,
                        service
                );

        SortedArrayStringMap context = new SortedArrayStringMap();
        context.putValue("patient", RAW);
        context.freeze();

        LogEvent source = new Log4jLogEvent.Builder()
                .setLoggerName("example")
                .setLevel(Level.ERROR)
                .setMessage(new SimpleMessage("patient=" + RAW))
                .setContextData(context)
                .setThrown(new RuntimeException("patient=" + RAW))
                .build();

        LogEvent rewritten = policy.rewrite(source);

        assertEquals(
                "[SECURELOGX_REDACTED_PROCESSING_FAILURE]",
                rewritten.getMessage().getFormattedMessage()
        );
        assertEquals(
                "true",
                rewritten.getContextData()
                        .getValue("securelogx.failClosed")
        );
        assertEquals(
                "FIELD_PROCESSING_FAILURE",
                rewritten.getContextData()
                        .getValue("securelogx.reason")
        );
        assertNull(rewritten.getContextData().getValue("patient"));
        assertEquals(0, rewritten.getContextStack().getDepth());
        assertFalse(rewritten.getThrown().toString().contains(RAW));
    }

    @Test
    void sequenceAndInstanceSurvivePolicyRecreation() {
        SecureMaskingService service = texts ->
                texts.stream()
                        .map(text -> new MaskedResult(
                                text,
                                false,
                                false,
                                MaskReasonCode.DETERMINISTIC_RESOLVED,
                                1L,
                                "test-masker"
                        ))
                        .toList();

        SecureLogXRewritePolicy firstPolicy =
                new SecureLogXRewritePolicy(
                        "test",
                        "SecureLogXRewrite",
                        false,
                        false,
                        null,
                        service
                );
        SecureLogXRewritePolicy secondPolicy =
                new SecureLogXRewritePolicy(
                        "test",
                        "SecureLogXRewrite",
                        false,
                        false,
                        null,
                        service
                );

        LogEvent source = new Log4jLogEvent.Builder()
                .setLoggerName("example")
                .setLevel(Level.INFO)
                .setMessage(new SimpleMessage("safe"))
                .setContextStack(ThreadContext.EMPTY_STACK)
                .build();

        LogEvent first = firstPolicy.rewrite(source);
        LogEvent second = secondPolicy.rewrite(source);

        long firstSequence = Long.parseLong(
                first.getContextData().getValue("securelogx.seq")
        );
        long secondSequence = Long.parseLong(
                second.getContextData().getValue("securelogx.seq")
        );

        assertTrue(secondSequence > firstSequence);
        String firstInstanceId = String.valueOf(
                first.getContextData()
                        .getValue("securelogx.instanceId")
        );
        String secondInstanceId = String.valueOf(
                second.getContextData()
                        .getValue("securelogx.instanceId")
        );

        assertEquals(firstInstanceId, secondInstanceId);
    }
}
