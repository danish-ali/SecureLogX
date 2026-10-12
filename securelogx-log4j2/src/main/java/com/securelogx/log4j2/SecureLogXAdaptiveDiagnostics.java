package com.securelogx.log4j2;

import com.securelogx.detection.MlRoutingTelemetry;
import org.apache.logging.log4j.core.LoggerContext;

/**
 * Read-only ML routing/candidate diagnostics for a Log4j2 deployment.
 *
 * This method never creates a masker or loads a model. The caller must
 * provide the already-running Log4j2 context and its configured environment.
 * Snapshots contain no logged values, but operational access should still be
 * restricted because field names can reveal application schema.
 */
public final class SecureLogXAdaptiveDiagnostics {

    private SecureLogXAdaptiveDiagnostics() {
    }

    public static MlRoutingTelemetry.Snapshot snapshot(
            LoggerContext context,
            String environment
    ) {
        if (context == null) {
            throw new IllegalArgumentException(
                    "LoggerContext is required"
            );
        }
        return SecureMaskerRegistry.mlTelemetrySnapshot(
                context,
                environment
        );
    }
}
