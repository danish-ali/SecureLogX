package com.securelogx.log4j2;

import com.securelogx.api.SecureMasker;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shares SecureMasker instances across Log4j2 reconfiguration cycles.
 *
 * RewritePolicy instances may be recreated while the JVM stays alive; keeping
 * one masker per SecureLogX environment avoids opening duplicate ONNX sessions.
 *
 * No JVM shutdown hook is registered here. A shutdown hook owned by a library
 * classloader can prevent application-server/classloader reclamation across
 * redeploys. The process will reclaim native resources at JVM exit; explicit
 * lifecycle closure is handled separately during integration hardening.
 */
final class SecureMaskerRegistry {

    private static final Map<String, SecureMasker> MASKERS =
            new ConcurrentHashMap<>();

    private SecureMaskerRegistry() {
    }

    static SecureMaskingService get(String environment) throws Exception {
        String key = normalize(environment);

        SecureMasker existing = MASKERS.get(key);
        if (existing != null) {
            return existing::maskAll;
        }

        synchronized (MASKERS) {
            existing = MASKERS.get(key);
            if (existing != null) {
                return existing::maskAll;
            }

            SecureMasker created = new SecureMasker(key);
            MASKERS.put(key, created);
            return created::maskAll;
        }
    }

    static void closeAll() {
        for (SecureMasker masker : MASKERS.values()) {
            try {
                masker.close();
            } catch (Exception ignored) {
                // Shutdown path: never emit application payload.
            }
        }
        MASKERS.clear();
    }

    private static String normalize(String environment) {
        return environment == null || environment.isBlank()
                ? "dev"
                : environment.trim();
    }
}
