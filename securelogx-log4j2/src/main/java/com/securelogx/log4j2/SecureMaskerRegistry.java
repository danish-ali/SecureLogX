package com.securelogx.log4j2;

import com.securelogx.api.SecureMasker;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shares SecureMasker instances across Log4j2 reconfiguration cycles.
 *
 * RewritePolicy instances may be recreated while the JVM stays alive; keeping
 * one masker per SecureLogX environment avoids opening duplicate ONNX sessions.
 */
final class SecureMaskerRegistry {

    private static final Map<String, SecureMasker> MASKERS =
            new ConcurrentHashMap<>();

    static {
        Runtime.getRuntime().addShutdownHook(
                new Thread(
                        SecureMaskerRegistry::closeAll,
                        "SecureLogX-Log4j2-Shutdown"
                )
        );
    }

    private SecureMaskerRegistry() {
    }

    static SecureMasker get(String environment) throws Exception {
        String key = normalize(environment);

        SecureMasker existing = MASKERS.get(key);
        if (existing != null) {
            return existing;
        }

        synchronized (MASKERS) {
            existing = MASKERS.get(key);
            if (existing != null) {
                return existing;
            }

            SecureMasker created = new SecureMasker(key);
            MASKERS.put(key, created);
            return created;
        }
    }

    private static String normalize(String environment) {
        return environment == null || environment.isBlank()
                ? "dev"
                : environment.trim();
    }

    private static void closeAll() {
        for (SecureMasker masker : MASKERS.values()) {
            try {
                masker.close();
            } catch (Exception ignored) {
                // Shutdown path: never emit application payload.
            }
        }
        MASKERS.clear();
    }
}
