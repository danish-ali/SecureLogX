package com.securelogx.util;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Verifies immutable ML deployment artifacts before the runtime loads them.
 */
public final class ArtifactIntegrityVerifier {

    private ArtifactIntegrityVerifier() {
    }

    public static void verifySha256(String label, String pathValue, String expectedSha256)
            throws Exception {
        if (pathValue == null || pathValue.isBlank()) {
            throw new IllegalStateException(label + " path is not configured");
        }
        if (expectedSha256 == null || expectedSha256.isBlank()) {
            throw new IllegalStateException(label + " SHA-256 is not configured");
        }

        Path path = Path.of(pathValue);
        if (!Files.exists(path)) {
            throw new IllegalStateException(label + " artifact is missing: " + path);
        }

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        String actual = HexFormat.of().formatHex(digest.digest(Files.readAllBytes(path)));
        if (!actual.equalsIgnoreCase(expectedSha256.trim())) {
            throw new IllegalStateException(
                    label + " SHA-256 mismatch. expected="
                            + expectedSha256.trim()
                            + " actual="
                            + actual
            );
        }
    }
}
