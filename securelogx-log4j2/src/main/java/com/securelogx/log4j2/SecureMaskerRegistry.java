package com.securelogx.log4j2;

import com.securelogx.api.SecureMasker;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Owns SecureMasker instances by Log4j LoggerContext + SecureLogX environment.
 *
 * A LoggerContext represents the application logging lifecycle. Configuration
 * reloads reuse the same context, so the masker/ONNX session is preserved
 * across reloads. Context shutdown (including application-server undeploy)
 * closes the masker and releases its bounded executor and native ONNX session.
 *
 * No JVM shutdown hook is used.
 */
final class SecureMaskerRegistry {

    private static final Object LOCK = new Object();

    private static final IdentityHashMap<
            LoggerContext,
            Map<String, MaskerHandle>> MASKERS_BY_CONTEXT =
            new IdentityHashMap<>();

    private SecureMaskerRegistry() {
    }

    static SecureMaskingService get(
            String environment,
            Configuration configuration
    ) throws Exception {
        return get(
                environment,
                configuration,
                normalizedEnvironment -> {
                    SecureMasker masker =
                            new SecureMasker(normalizedEnvironment);
                    return new MaskerHandle(
                            masker::maskAll,
                            masker
                    );
                }
        );
    }

    static SecureMaskingService get(
            String environment,
            Configuration configuration,
            MaskerFactory factory
    ) throws Exception {
        if (configuration == null
                || configuration.getLoggerContext() == null) {
            throw new IllegalStateException(
                    "SecureLogX requires a Log4j LoggerContext "
                            + "for native-resource lifecycle ownership"
            );
        }

        String key = normalize(environment);
        LoggerContext context =
                configuration.getLoggerContext();

        synchronized (LOCK) {
            Map<String, MaskerHandle> contextMaskers =
                    MASKERS_BY_CONTEXT.get(context);

            if (contextMaskers == null) {
                contextMaskers = new HashMap<>();
                MASKERS_BY_CONTEXT.put(
                        context,
                        contextMaskers
                );

                LoggerContext capturedContext = context;
                context.addShutdownListener(
                        ignored -> closeContext(
                                capturedContext
                        )
                );
            }

            MaskerHandle existing =
                    contextMaskers.get(key);
            if (existing != null) {
                return existing.service();
            }

            MaskerHandle created = factory.create(key);
            contextMaskers.put(key, created);
            return created.service();
        }
    }

    static void closeAll() {
        IdentityHashMap<
                LoggerContext,
                Map<String, MaskerHandle>> snapshot;

        synchronized (LOCK) {
            snapshot = new IdentityHashMap<>(
                    MASKERS_BY_CONTEXT
            );
            MASKERS_BY_CONTEXT.clear();
        }

        for (Map<String, MaskerHandle> entries
                : snapshot.values()) {
            closeEntries(entries);
        }
    }

    static int ownedContextCount() {
        synchronized (LOCK) {
            return MASKERS_BY_CONTEXT.size();
        }
    }

    static int ownedMaskerCount() {
        synchronized (LOCK) {
            return MASKERS_BY_CONTEXT.values()
                    .stream()
                    .mapToInt(Map::size)
                    .sum();
        }
    }

    private static void closeContext(
            LoggerContext context
    ) {
        Map<String, MaskerHandle> entries;

        synchronized (LOCK) {
            entries = MASKERS_BY_CONTEXT.remove(context);
        }

        closeEntries(entries);
    }

    private static void closeEntries(
            Map<String, MaskerHandle> entries
    ) {
        if (entries == null) {
            return;
        }

        for (MaskerHandle handle : entries.values()) {
            try {
                handle.closeable().close();
            } catch (Exception ignored) {
                // Lifecycle shutdown path: never emit application payload.
            }
        }
        entries.clear();
    }

    private static String normalize(String environment) {
        return environment == null || environment.isBlank()
                ? "dev"
                : environment.trim();
    }

    @FunctionalInterface
    interface MaskerFactory {
        MaskerHandle create(String environment)
                throws Exception;
    }

    record MaskerHandle(
            SecureMaskingService service,
            AutoCloseable closeable
    ) {
        MaskerHandle {
            if (service == null || closeable == null) {
                throw new IllegalArgumentException(
                        "MaskerHandle requires service and closeable"
                );
            }
        }
    }
}
