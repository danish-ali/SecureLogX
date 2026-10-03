package com.securelogx.log4j2;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Process/classloader-scoped identity for the Log4j2 integration.
 *
 * RewritePolicy instances can be recreated during Log4j2 reconfiguration.
 * Keeping sequence and instance identity here preserves monotonic ingress
 * ordering across those policy instances.
 */
final class SecureLogXLog4j2Runtime {

    private static final String INSTANCE_ID =
            UUID.randomUUID().toString();
    private static final AtomicLong SEQUENCE =
            new AtomicLong();

    private SecureLogXLog4j2Runtime() {
    }

    static long nextSequence() {
        return SEQUENCE.incrementAndGet();
    }

    static String instanceId() {
        return INSTANCE_ID;
    }
}
