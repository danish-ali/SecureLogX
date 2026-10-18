package com.securelogx.log4j2;

import com.securelogx.api.SecureMasker;
import com.securelogx.model.MaskingFieldOrigin;
import com.securelogx.detection.MlRoutingTelemetry;
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
                    SecureMaskingService service =
                            new SecureMaskingService() {
                                @Override
                                public java.util.List<com.securelogx.api.MaskedResult> maskAll(
                                        java.util.List<String> texts
                                ) {
                                    return masker.maskAll(texts);
                                }

                                @Override
                                public java.util.List<com.securelogx.api.MaskedResult> maskAllWithOrigins(
                                        java.util.List<String> texts,
                                        java.util.List<MaskingFieldOrigin> origins
                                ) {
                                    return masker.maskAllWithOrigins(
                                            texts, origins
                                    );
                                }
                            };
                    return new MaskerHandle(
                            service,
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

    static java.util.Optional<SecureLogXInferenceDiagnostics.Snapshot>
    inferenceSnapshot(
            LoggerContext context,
            String environment
    ) {
        synchronized (LOCK) {
            Map<String, MaskerHandle> byEnvironment =
                    MASKERS_BY_CONTEXT.get(context);
            if (byEnvironment != null) {
                MaskerHandle handle =
                        byEnvironment.get(normalize(environment));
                if (handle != null
                        && handle.closeable() instanceof SecureMasker masker) {
                    return java.util.Optional.of(
                            new SecureLogXInferenceDiagnostics.Snapshot(
                                    masker.getHybridRuntimeStats(),
                                    masker.getInferenceStageStats()
                            )
                    );
                }
            }
        }
        // No implicit session initialization for read-only diagnostics.
        return java.util.Optional.empty();
    }

    static java.util.Optional<com.securelogx.api.MaskingRuntimeStats>
    runtimeSnapshot(
            LoggerContext context,
            String environment
    ) {
        synchronized (LOCK) {
            Map<String, MaskerHandle> byEnvironment =
                    MASKERS_BY_CONTEXT.get(context);
            if (byEnvironment != null) {
                MaskerHandle handle =
                        byEnvironment.get(normalize(environment));
                if (handle != null
                        && handle.closeable() instanceof SecureMasker masker) {
                    return java.util.Optional.of(
                            masker.getRuntimeStats()
                    );
                }
            }
        }
        // Read-only: never initialize ONNX merely to inspect counters.
        return java.util.Optional.empty();
    }

    static java.util.Optional<com.securelogx.api.MaskingQueueWaitStats>
    queueWaitSnapshot(LoggerContext context, String environment) {
        synchronized (LOCK) {
            Map<String, MaskerHandle> byEnvironment =
                    MASKERS_BY_CONTEXT.get(context);
            if (byEnvironment != null) {
                MaskerHandle handle =
                        byEnvironment.get(normalize(environment));
                if (handle != null
                        && handle.closeable() instanceof SecureMasker masker) {
                    return java.util.Optional.of(
                            masker.getMaskingQueueWaitStats()
                    );
                }
            }
        }
        return java.util.Optional.empty();
    }

    static MlRoutingTelemetry.Snapshot mlTelemetrySnapshot(
            LoggerContext context,
            String environment
    ) {
        synchronized (LOCK) {
            Map<String, MaskerHandle> byEnvironment =
                    MASKERS_BY_CONTEXT.get(context);
            if (byEnvironment != null) {
                MaskerHandle handle =
                        byEnvironment.get(normalize(environment));
                if (handle != null
                        && handle.closeable() instanceof SecureMasker masker) {
                    return masker.getMlRoutingTelemetrySnapshot();
                }
            }
        }
        // Do not create a masker or native ONNX session just to query.
        return new MlRoutingTelemetry("", false).snapshot();
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
