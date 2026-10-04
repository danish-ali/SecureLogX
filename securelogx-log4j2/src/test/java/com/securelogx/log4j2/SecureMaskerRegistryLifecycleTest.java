package com.securelogx.log4j2;

import com.securelogx.api.MaskedResult;
import com.securelogx.api.MaskReasonCode;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.AbstractConfiguration;
import org.apache.logging.log4j.core.config.ConfigurationSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SecureMaskerRegistryLifecycleTest {

    @AfterEach
    void cleanup() {
        SecureMaskerRegistry.closeAll();
    }

    @Test
    void reusesMaskerAcrossConfigurationReloadAndClosesOnContextShutdown()
            throws Exception {
        LoggerContext context =
                new LoggerContext("securelogx-lifecycle-reload");
        TestConfiguration first =
                new TestConfiguration(context);
        TestConfiguration second =
                new TestConfiguration(context);

        AtomicInteger created = new AtomicInteger();
        AtomicInteger closed = new AtomicInteger();

        SecureMaskerRegistry.MaskerFactory factory =
                environment -> {
                    created.incrementAndGet();
                    SecureMaskingService service =
                            texts -> safeResults(texts);
                    return new SecureMaskerRegistry.MaskerHandle(
                            service,
                            closed::incrementAndGet
                    );
                };

        SecureMaskingService firstService =
                SecureMaskerRegistry.get(
                        "dev",
                        first,
                        factory
                );
        SecureMaskingService secondService =
                SecureMaskerRegistry.get(
                        "dev",
                        second,
                        factory
                );

        assertSame(firstService, secondService);
        assertEquals(1, created.get());
        assertEquals(
                1,
                SecureMaskerRegistry.ownedContextCount()
        );
        assertEquals(
                1,
                SecureMaskerRegistry.ownedMaskerCount()
        );

        context.stop();

        assertEquals(1, closed.get());
        assertEquals(
                0,
                SecureMaskerRegistry.ownedContextCount()
        );
        assertEquals(
                0,
                SecureMaskerRegistry.ownedMaskerCount()
        );
    }

    @Test
    void stoppingOneContextDoesNotCloseAnotherContextsMasker()
            throws Exception {
        LoggerContext firstContext =
                new LoggerContext("securelogx-lifecycle-one");
        LoggerContext secondContext =
                new LoggerContext("securelogx-lifecycle-two");

        TestConfiguration first =
                new TestConfiguration(firstContext);
        TestConfiguration second =
                new TestConfiguration(secondContext);

        AtomicInteger firstClosed = new AtomicInteger();
        AtomicInteger secondClosed = new AtomicInteger();

        SecureMaskerRegistry.get(
                "dev",
                first,
                environment -> new SecureMaskerRegistry.MaskerHandle(
                        texts -> safeResults(texts),
                        firstClosed::incrementAndGet
                )
        );

        SecureMaskerRegistry.get(
                "dev",
                second,
                environment -> new SecureMaskerRegistry.MaskerHandle(
                        texts -> safeResults(texts),
                        secondClosed::incrementAndGet
                )
        );

        assertEquals(
                2,
                SecureMaskerRegistry.ownedContextCount()
        );
        assertEquals(
                2,
                SecureMaskerRegistry.ownedMaskerCount()
        );

        firstContext.stop();

        assertEquals(1, firstClosed.get());
        assertEquals(0, secondClosed.get());
        assertEquals(
                1,
                SecureMaskerRegistry.ownedContextCount()
        );
        assertEquals(
                1,
                SecureMaskerRegistry.ownedMaskerCount()
        );

        secondContext.stop();

        assertEquals(1, secondClosed.get());
        assertEquals(
                0,
                SecureMaskerRegistry.ownedContextCount()
        );
        assertEquals(
                0,
                SecureMaskerRegistry.ownedMaskerCount()
        );
    }

    private static List<MaskedResult> safeResults(
            List<String> texts
    ) {
        return texts.stream()
                .map(text -> new MaskedResult(
                        "[MASKED]",
                        false,
                        false,
                        MaskReasonCode.DETERMINISTIC_RESOLVED,
                        1L,
                        "lifecycle-test"
                ))
                .toList();
    }

    private static final class TestConfiguration
            extends AbstractConfiguration {

        private TestConfiguration(
                LoggerContext context
        ) {
            super(
                    context,
                    ConfigurationSource.NULL_SOURCE
            );
        }

        @Override
        protected void doConfigure() {
            // No appenders required for registry lifecycle tests.
        }
    }
}
