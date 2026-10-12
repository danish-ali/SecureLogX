package com.securelogx.log4j2;

import com.securelogx.detection.HybridRuntimeStats;
import com.securelogx.detection.InferenceStageStats;
import org.apache.logging.log4j.core.LoggerContext;

import java.util.Optional;

/**
 * Read-only statistics for a context-owned, already-running SecureMasker.
 * Never creates a native ONNX session or stores sensitive text.
 */
public final class SecureLogXInferenceDiagnostics {

    private SecureLogXInferenceDiagnostics() {
    }

    public static Optional<Snapshot> snapshot(
            LoggerContext context,
            String environment
    ) {
        if (context == null) {
            throw new IllegalArgumentException(
                    "LoggerContext is required"
            );
        }
        return SecureMaskerRegistry.inferenceSnapshot(
                context, environment
        );
    }

    public record Snapshot(
            HybridRuntimeStats routing,
            InferenceStageStats stages
    ) {
    }
}
