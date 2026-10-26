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

    /**
     * Read-only bounded-executor counters for already initialized maskers.
     * Includes admission rejection, deadline, peak queue and active requests.
     */
    public static Optional<com.securelogx.api.MaskingRuntimeStats>
    runtimeStats(
            LoggerContext context,
            String environment
    ) {
        if (context == null) {
            throw new IllegalArgumentException(
                    "LoggerContext is required"
            );
        }
        return SecureMaskerRegistry.runtimeSnapshot(context, environment);
    }

    /**
     * Aggregate elapsed queue waits for started native masking jobs.
     * Reading this does not initialize ONNX or reveal application data.
     */
    public static Optional<com.securelogx.api.MaskingQueueWaitStats>
    queueWaitStats(LoggerContext context, String environment) {
        if (context == null) {
            throw new IllegalArgumentException("LoggerContext is required");
        }
        return SecureMaskerRegistry.queueWaitSnapshot(
                context, environment
        );
    }

    /**
     * Begin a post-warmup observation window. Never changes masking routing,
     * native worker count, queue capacity or inference sessions.
     */
    public static boolean beginQueueWaitWindow(
            LoggerContext context, String environment
    ) {
        if (context == null) {
            throw new IllegalArgumentException("LoggerContext is required");
        }
        return SecureMaskerRegistry.beginQueueWaitWindow(
                context, environment
        );
    }

    public static java.util.OptionalDouble maxMeasuredQueueWaitMillis(
            LoggerContext context, String environment
    ) {
        if (context == null) {
            throw new IllegalArgumentException("LoggerContext is required");
        }
        return SecureMaskerRegistry.maxMeasuredQueueWaitMillis(
                context, environment
        );
    }

    /**
     * Read-only counters for the disabled-by-default pre-queue experiment.
     * Values contain no payloads, tokens, org rules or detected spans.
     */
    public static Optional<com.securelogx.api.PreQueueRoutingStats>
    preQueueRoutingStats(LoggerContext context, String environment) {
        if (context == null) {
            throw new IllegalArgumentException("LoggerContext is required");
        }
        return SecureMaskerRegistry.preQueueRoutingSnapshot(
                context, environment
        );
    }

    /**
     * Observation-only Phase 1: caller routing, queue lifecycle and actual
     * native run attempts, without retaining payloads or initializing ONNX.
     */
    public static Optional<PhaseOneSnapshot> phaseOneStats(
            LoggerContext context, String environment
    ) {
        if (context == null) {
            throw new IllegalArgumentException("LoggerContext is required");
        }
        return SecureMaskerRegistry.phaseOneSnapshot(context, environment);
    }

    public record PhaseOneSnapshot(
            com.securelogx.api.PreQueueRoutingStats preQueue,
            com.securelogx.api.MaskingLifecycleDiagnostics lifecycle,
            com.securelogx.detection.NativeInferenceDiagnostics nativeInference
    ) {
    }

    public record Snapshot(
            HybridRuntimeStats routing,
            InferenceStageStats stages
    ) {
    }
}
